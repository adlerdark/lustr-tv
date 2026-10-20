package com.lustr.tv.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.core.CaptionTrack
import com.lustr.tv.core.LustrClient
import com.lustr.tv.core.LustrException
import com.lustr.tv.core.PlayTracker
import com.lustr.tv.core.Playback
import com.lustr.tv.core.StreamSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Plays one file. Tries Direct play first (the original file), and if the Shield can't play it,
 * moves on to the server's HLS transcodes. Saves the resume point every few seconds and on exit.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(container: AppContainer, path: String, onNeedLogin: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val client = remember { container.client }
    val needLogin by rememberUpdatedState(onNeedLogin)
    val exit by rememberUpdatedState(onExit)

    val player = remember {
        ExoPlayer.Builder(context)
            // every request (video, HLS, subtitles) goes through OkHttp, with the login cookie
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(container.http)))
            .build()
            .apply { playWhenReady = true }
    }
    val tracker = remember { PlayTracker(path) }
    var sources by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var captions by remember { mutableStateOf<List<CaptionTrack>>(emptyList()) }
    var sourceIndex by remember { mutableIntStateOf(0) }
    var duration by remember { mutableStateOf(0.0) }
    var message by remember { mutableStateOf<String?>("Loading…") }

    fun report(final: Boolean) {
        if (sources.isEmpty()) return
        val total = if (duration > 0) duration else player.duration.coerceAtLeast(0) / 1000.0
        val r = tracker.report(player.currentPosition / 1000.0, total, final) ?: return
        container.appScope.launch {
            try {
                client.reportProgress(r)
            } catch (e: IOException) {
                // a missed report is fine; the next one carries the same play_id
            }
        }
    }

    // 1. Ask the server how this file can be played, and where to resume
    LaunchedEffect(path) {
        try {
            val info = client.streams(path)
            val resumeAt = try {
                client.video(path).watchProgress
            } catch (e: LustrException.ServerError) {
                null
            }
            sources = Playback.tvOrder(info.sources)
            captions = info.captions?.tracks.orEmpty()
            duration = info.duration
            if (sources.isEmpty()) {
                message = "No way to play this file"
                return@LaunchedEffect
            }
            val startMs = (Playback.startPosition(resumeAt, info.duration) * 1000).toLong()
            player.setMediaItem(mediaItem(client, sources[0], captions), startMs)
            player.prepare()
        } catch (e: LustrException.NotLoggedIn) {
            needLogin()
        } catch (e: IOException) {
            message = "Can't play this video (${e.message})"
        }
    }

    // 2. Errors: try the next source from the same position; status messages
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val http = error.cause as? HttpDataSource.InvalidResponseCodeException
                if (http?.responseCode == 401) {
                    needLogin()
                    return
                }
                val next = sourceIndex + 1
                if (next < sources.size) {
                    val at = player.currentPosition
                    sourceIndex = next
                    message = "Switching to ${sources[next].label}…"
                    player.setMediaItem(mediaItem(client, sources[next], captions), at)
                    player.prepare()
                } else {
                    message = "Can't play this video (${error.errorCodeName})"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> message = null
                    Player.STATE_ENDED -> exit()
                    else -> Unit
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            report(final = true)
            player.release()
        }
    }

    // 3. Count the time really spent playing, and save progress every REPORT_EVERY_MS
    LaunchedEffect(player) {
        var last = System.nanoTime()
        var sinceReportMs = 0L
        while (isActive) {
            delay(1000)
            val now = System.nanoTime()
            val elapsedMs = (now - last) / 1_000_000
            last = now
            if (player.isPlaying) {
                tracker.addPlayed(elapsedMs / 1000.0)
                sinceReportMs += elapsedMs
                if (sinceReportMs >= Playback.REPORT_EVERY_MS) {
                    sinceReportMs = 0
                    report(final = false)
                }
            }
        }
    }

    // The Home button / screen off: pause and save
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player.pause()
        report(final = true)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowSubtitleButton(true)
                    keepScreenOn = true
                    // the remote's D-pad / OK / play-pause go to the player controls
                    isFocusable = true
                    isFocusableInTouchMode = true
                    post { requestFocus() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** One source as a MediaItem, with the server's WebVTT captions attached. */
@OptIn(UnstableApi::class)
private fun mediaItem(client: LustrClient, source: StreamSource, captions: List<CaptionTrack>): MediaItem =
    MediaItem.Builder()
        .setUri(client.absoluteUrl(source.src))
        // HLS is named outright rather than guessed from the URL. Direct: let ExoPlayer detect the
        // container - the server's type for MKV is "video/webm", which would mislead it.
        .setMimeType(if (source.isHls) MimeTypes.APPLICATION_M3U8 else null)
        .setSubtitleConfigurations(captions.map { track ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(client.absoluteUrl(track.src)))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(track.lang)
                .setLabel(track.label)
                .build()
        })
        .build()
