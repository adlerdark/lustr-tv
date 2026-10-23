package com.lustr.tv.ui.player

import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import com.lustr.tv.AppContainer
import com.lustr.tv.Load
import com.lustr.tv.core.CaptionTrack
import com.lustr.tv.core.LustrClient
import com.lustr.tv.core.LustrException
import com.lustr.tv.core.Marker
import com.lustr.tv.core.PlayTracker
import com.lustr.tv.core.Playback
import com.lustr.tv.core.RemoteSeek
import com.lustr.tv.core.ResumeMode
import com.lustr.tv.core.SpriteInfo
import com.lustr.tv.core.SpriteSheet
import com.lustr.tv.core.SpriteTile
import com.lustr.tv.core.StreamSource
import com.lustr.tv.core.VideoZoom
import com.lustr.tv.ui.browse.ChoiceRow
import com.lustr.tv.ui.browse.Panel
import com.lustr.tv.ui.browse.SectionTitle
import com.lustr.tv.ui.theme.LustrColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

private const val HIDE_CONTROLS_AFTER_MS = 5_000L
private const val APPLY_SEEK_AFTER_MS = 450L
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Plays one file with TV controls:
 *  - controls hidden: ◀ / ▶ jump back / forward (Settings: seek step); hold to seek fast, the jump
 *    happens when the button is let go. OK / ▲ / ▼ show the controls. Play/Pause works any time.
 *  - controls shown: the progress bar has focus (◀ / ▶ seek the same way); ▼ goes to the buttons
 *    (play/pause, -/+, CC, ⚙ quality / captions / audio / speed / picture). Back hides them.
 * Starts with Direct play (or the quality chosen in Settings) and moves on to the server's HLS
 * transcodes if a source fails. Saves progress every 10 s and on exit.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(container: AppContainer, path: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val client = remember { container.client }
    val settings = remember { container.settings.value }          // fixed for this playback
    val exit by rememberUpdatedState(onExit)
    val scope = rememberCoroutineScope()

    val player = remember {
        ExoPlayer.Builder(context)
            // every request (video, HLS, captions) goes through OkHttp, with the login cookie
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(container.http)))
            .build()
            .apply {
                playWhenReady = true
                setVideoChangeFrameRateStrategy(
                    if (settings.matchFrameRate) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                    else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF,
                )
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitlesOn)
                    .build()
            }
    }
    val tracker = remember { PlayTracker(path) }
    val seek = remember { RemoteSeek(settings.seekStepSeconds * 1000L) }

    var title by remember { mutableStateOf("") }
    var sources by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var captions by remember { mutableStateOf<List<CaptionTrack>>(emptyList()) }
    var sourceIndex by remember { mutableIntStateOf(0) }
    var durationSec by remember { mutableStateOf(0.0) }
    var message by remember { mutableStateOf<String?>("Loading…") }
    var askResumeAt by remember { mutableStateOf<Double?>(null) }

    var position by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var seekBarFocused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(settings.zoom) }
    var speed by remember { mutableFloatStateOf(1f) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    val seekBarFocus = remember { FocusRequester() }
    var markers by remember { mutableStateOf<List<Marker>>(emptyList()) }
    var tiles by remember { mutableStateOf<List<SpriteTile>>(emptyList()) }
    var sprite by remember { mutableStateOf<SpriteInfo?>(null) }

    fun durationMs(): Long = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (durationSec * 1000).toLong()

    fun report(final: Boolean) {
        if (sources.isEmpty()) return
        val total = if (durationSec > 0) durationSec else durationMs() / 1000.0
        val r = tracker.report(player.currentPosition / 1000.0, total, final) ?: return
        container.appScope.launch {
            try {
                client.reportProgress(r)
            } catch (e: IOException) {
                container.onError(e)          // a missed report is fine; a 401 means: log in again
            }
        }
    }

    fun play(index: Int, atMs: Long) {
        sourceIndex = index
        player.setMediaItem(mediaItem(client, sources[index], captions), atMs)
        player.prepare()
    }

    // 1. Ask the server how this file can be played, and where to resume
    LaunchedEffect(path) {
        try {
            val info = client.streams(path)
            val video = try {
                client.video(path)
            } catch (e: LustrException.ServerError) {
                null
            }
            title = video?.displayTitle.orEmpty()
            sprite = info.sprite
            info.videoId?.let { id ->
                (container.load { markers(id) } as? Load.Ready)?.let { markers = it.value }      // chapters
            }
            sources = Playback.order(info.sources, settings.defaultQuality)
            captions = info.captions?.tracks.orEmpty()
            durationSec = info.duration
            if (sources.isEmpty()) {
                message = "No way to play this file"
                return@LaunchedEffect
            }
            val resumeAt = Playback.startPosition(video?.watchProgress, info.duration)
            when {
                resumeAt <= 0 || settings.resume == ResumeMode.NEVER -> play(0, 0)
                settings.resume == ResumeMode.ASK -> askResumeAt = resumeAt
                else -> play(0, (resumeAt * 1000).toLong())
            }
        } catch (e: IOException) {
            container.onError(e)
            message = "Can't play this video (${e.message})"
        }
    }

    // 2. Errors: try the next source from the same position; status messages
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val http = error.cause as? HttpDataSource.InvalidResponseCodeException
                if (http?.responseCode == 401) {
                    container.onError(LustrException.NotLoggedIn("Logged out"))
                    return
                }
                val next = sourceIndex + 1
                if (next < sources.size) {
                    message = "Switching to ${sources[next].label}…"
                    play(next, player.currentPosition)
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

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
                // A track this device has no decoder for is left out silently: the clock runs with
                // no picture / sound. Treat that like an error and move on to a transcode.
                val missing = undecodable(newTracks) ?: return
                val next = sourceIndex + 1
                if (next < sources.size) {
                    message = "This device can't decode the $missing of this file – switching to ${sources[next].label}…"
                    play(next, player.currentPosition)
                } else {
                    message = "This device can't decode the $missing of this video."
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

    // 3. Count the time really spent playing, save progress every REPORT_EVERY_MS, update the clock
    LaunchedEffect(player) {
        var last = System.nanoTime()
        var sinceReportMs = 0L
        while (isActive) {
            delay(250)
            val now = System.nanoTime()
            val elapsedMs = (now - last) / 1_000_000
            last = now
            position = player.currentPosition
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

    // Controls hide themselves after a few seconds without input while playing
    LaunchedEffect(controlsVisible, lastInput, isPlaying, menuOpen) {
        if (controlsVisible && isPlaying && !menuOpen) {
            delay(HIDE_CONTROLS_AFTER_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) { if (controlsVisible) runCatching { seekBarFocus.requestFocus() } }

    // 4. The remote: seeking with ◀ / ▶ (and ⏪ / ⏩), play / pause, showing the controls
    var applyJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) {
        container.keyInterceptor = handler@{ e ->
            lastInput = System.currentTimeMillis()
            if (menuOpen || askResumeAt != null || sources.isEmpty()) return@handler false
            val code = e.keyCode
            val isMediaSeek = code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND
            val isArrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            if (isMediaSeek || (isArrow && (!controlsVisible || seekBarFocused))) {
                val dir = if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 1 else -1
                when (e.action) {
                    KeyEvent.ACTION_DOWN -> {
                        // first press and every repeat: RemoteSeek speeds up the longer the seeking goes on
                        applyJob?.cancel()
                        seekTarget = seek.press(e.eventTime, player.currentPosition, dir, durationMs())
                    }
                    KeyEvent.ACTION_UP -> {
                        applyJob = scope.launch {
                            delay(APPLY_SEEK_AFTER_MS)            // more presses in a row add up first
                            seek.take()?.let { player.seekTo(it) }
                            seekTarget = null
                        }
                    }
                }
                return@handler true
            }
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                        when {
                            code == KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
                            code == KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
                            player.isPlaying -> player.pause()
                            else -> player.play()
                        }
                        controlsVisible = true
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (!controlsVisible) {
                        if (e.action == KeyEvent.ACTION_UP) controlsVisible = true
                        true                                          // the press only shows the controls
                    } else {
                        false
                    }
                else -> false
            }
        }
        onDispose { container.keyInterceptor = null }
    }

    BackHandler(enabled = controlsVisible && !menuOpen) { controlsVisible = false }

    // The Home button / screen off: pause and save
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player.pause()
        report(final = true)
    }

    // ---- UI ---------------------------------------------------------------------------------
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false                   // our own controls (below)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    keepScreenOn = true
                    isFocusable = false
                    subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.subtitleSize.scale)
                }
            },
            update = { it.resizeMode = zoom.resizeMode() },
            modifier = Modifier.fillMaxSize(),
        )

        message?.let { Banner(it, Modifier.align(Alignment.TopCenter)) }

        val shownPosition = seekTarget ?: position
        if (controlsVisible) {
            Controls(
                title = title,
                sourceLabel = sources.getOrNull(sourceIndex)?.label.orEmpty(),
                position = shownPosition,
                duration = durationMs(),
                seeking = seekTarget != null,
                isPlaying = isPlaying,
                seekStepSeconds = settings.seekStepSeconds,
                captionsOn = textGroups(tracks).any { it.isSelected },
                hasCaptions = textGroups(tracks).isNotEmpty(),
                seekBarFocus = seekBarFocus,
                onSeekBarFocus = { seekBarFocused = it },
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onJump = { dir -> seek.tap(player.currentPosition, dir, durationMs()); seek.take()?.let { player.seekTo(it) } },
                onCaptions = { toggleCaptions(player, tracks) },
                onMenu = { menuOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter),
                above = {
                    if (markers.isNotEmpty()) {
                        Chapters(
                            markers = markers,
                            position = position,
                            path = path,
                            client = client,
                            tiles = tiles,
                            onOpen = {
                                // preview tiles: fetched when the chapters are first opened (the sheet may still be in the making)
                                if (tiles.isEmpty()) scope.launch { (container.load { spriteTiles(client.streams(path).sprite ?: sprite) } as? Load.Ready)?.let { tiles = it.value } }
                            },
                            onPick = { m -> player.seekTo((m.seconds * 1000).toLong()) },
                        )
                    }
                },
            )
        } else if (seekTarget != null) {
            // seeking without the controls: just the bar and the time
            SeekOverlay(shownPosition, durationMs(), Modifier.align(Alignment.BottomCenter))
        }
    }

    if (menuOpen) {
        PlayerMenu(
            info = nowPlaying(player, sources.getOrNull(sourceIndex)),
            sources = sources,
            current = sourceIndex,
            tracks = tracks,
            speed = speed,
            zoom = zoom,
            onSource = { i -> menuOpen = false; message = "Switching to ${sources[i].label}…"; play(i, player.currentPosition) },
            onText = { group -> selectTrack(player, C.TRACK_TYPE_TEXT, group) },
            onAudio = { group -> selectTrack(player, C.TRACK_TYPE_AUDIO, group) },
            onSpeed = { s -> speed = s; player.setPlaybackSpeed(s) },
            onZoom = { z -> zoom = z },
            onDismiss = { menuOpen = false },
        )
    }

    askResumeAt?.let { at ->
        Panel(onDismiss = { askResumeAt = null; play(0, (at * 1000).toLong()) }) {
            val first = remember { FocusRequester() }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(title.ifEmpty { "Resume?" }, Modifier.padding(bottom = 8.dp))
                ChoiceRow("Resume from ${formatTime((at * 1000).toLong())}", onClick = {
                    askResumeAt = null; play(0, (at * 1000).toLong())
                }, modifier = Modifier.focusRequester(first))
                ChoiceRow("Start from the beginning", onClick = { askResumeAt = null; play(0, 0) })
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}

// ---- pieces ---------------------------------------------------------------------------------

@Composable
private fun Banner(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = modifier
            .padding(top = 32.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Controls(
    title: String,
    sourceLabel: String,
    position: Long,
    duration: Long,
    seeking: Boolean,
    isPlaying: Boolean,
    seekStepSeconds: Int,
    captionsOn: Boolean,
    hasCaptions: Boolean,
    seekBarFocus: FocusRequester,
    onSeekBarFocus: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onJump: (Int) -> Unit,
    onCaptions: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    above: @Composable () -> Unit = {},
) {
    var barFocused by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(sourceLabel, color = LustrColors.textSecondary)
        }
        above()
        // The progress bar: focusable, ◀ / ▶ on it seek (handled in the key interceptor)
        ProgressBar(
            position, duration, seeking,
            Modifier
                .focusRequester(seekBarFocus)
                .onFocusChanged { barFocused = it.isFocused; onSeekBarFocus(it.isFocused) }
                .focusable()
                .border(2.dp, if (barFocused) LustrColors.primarySoft else Color.Transparent, RoundedCornerShape(6.dp))
                .padding(6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPlayPause) { Text(if (isPlaying) "❚❚  Pause" else "▶  Play") }
            OutlinedButton(onClick = { onJump(-1) }) { Text("−${seekStepSeconds}s") }
            OutlinedButton(onClick = { onJump(1) }) { Text("+${seekStepSeconds}s") }
            if (hasCaptions) OutlinedButton(onClick = onCaptions) { Text(if (captionsOn) "CC on" else "CC off") }
            OutlinedButton(onClick = onMenu) { Text("⚙  Settings") }
            Spacer(Modifier.weight(1f))
            Text("${formatTime(position)} / ${formatTime(duration)}", color = Color.White)
        }
    }
}

@Composable
private fun ProgressBar(position: Long, duration: Long, seeking: Boolean, modifier: Modifier = Modifier) {
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp)))
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .background(if (seeking) LustrColors.primarySoft else LustrColors.primaryLight, RoundedCornerShape(3.dp)),
        )
    }
}

/** Shown while seeking with the controls hidden. */
@Composable
private fun SeekOverlay(position: Long, duration: Long, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${formatTime(position)} / ${formatTime(duration)}", style = MaterialTheme.typography.titleLarge, color = Color.White)
        ProgressBar(position, duration, seeking = true)
    }
}

/** ⚙: quality, captions, audio track, speed, picture. */
@Composable
private fun PlayerMenu(
    info: List<String>,
    sources: List<StreamSource>,
    current: Int,
    tracks: Tracks,
    speed: Float,
    zoom: VideoZoom,
    onSource: (Int) -> Unit,
    onText: (Tracks.Group?) -> Unit,
    onAudio: (Tracks.Group?) -> Unit,
    onSpeed: (Float) -> Unit,
    onZoom: (VideoZoom) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Panel(onDismiss, side = true) {
        LazyColumn(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SectionTitle("Playback", Modifier.padding(bottom = 4.dp)) }
            item { MenuHeading("Now playing") }
            items(info) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = LustrColors.textPrimary, modifier = Modifier.padding(start = 12.dp))
            }
            item { MenuHeading("Quality") }
            items(sources.size) { i ->
                val s = sources[i]
                ChoiceRow(
                    text = if (s.isDirect) "Original file (direct play)" else s.label.replace("HLS", "Transcode"),
                    onClick = { onSource(i) },
                    marker = if (i == current) "✓" else "",
                    highlighted = i == current,
                    modifier = if (i == current) Modifier.focusRequester(first) else Modifier,
                )
            }
            val text = textGroups(tracks)
            if (text.isNotEmpty()) {
                item { MenuHeading("Captions") }
                item { ChoiceRow("Off", onClick = { onText(null) }, marker = if (text.none { it.isSelected }) "✓" else "") }
                items(text) { g -> ChoiceRow(trackLabel(g, "Captions"), onClick = { onText(g) }, marker = if (g.isSelected) "✓" else "") }
            }
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.size > 1) {
                item { MenuHeading("Audio") }
                items(audio) { g -> ChoiceRow(trackLabel(g, "Audio"), onClick = { onAudio(g) }, marker = if (g.isSelected) "✓" else "") }
            }
            item { MenuHeading("Speed") }
            items(SPEEDS) { s -> ChoiceRow(if (s == 1f) "Normal" else "${s}×", onClick = { onSpeed(s) }, marker = if (s == speed) "✓" else "") }
            item { MenuHeading("Picture") }
            items(VideoZoom.entries) { z -> ChoiceRow(z.label, onClick = { onZoom(z) }, marker = if (z == zoom) "✓" else "") }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LustrColors.textSecondary,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
    )
}

/**
 * "▲ Chapters (n)" above the progress bar; ▲ from the bar focuses it and opens a row of chapter
 * cards (starting at the current one). OK jumps there; ▼ goes back to the bar and closes the row.
 */
@Composable
private fun Chapters(
    markers: List<Marker>,
    position: Long,
    path: String,
    client: LustrClient,
    tiles: List<SpriteTile>,
    onOpen: () -> Unit,
    onPick: (Marker) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = markers.indexOfLast { it.seconds * 1000 <= position }.coerceAtLeast(0)
    val currentFocus = remember { FocusRequester() }
    if (!open) {
        var focused by remember { mutableStateOf(false) }
        Text(
            "▲  Chapters (${markers.size})",
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) Color.White else LustrColors.textSecondary,
            modifier = Modifier
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) {
                        open = true
                        onOpen()
                    }
                }
                .focusable()
                .padding(vertical = 4.dp),
        )
    } else {
        // Close only when focus was in the row and then left it (▼ to the bar). The row also hears
        // "no focus" the moment it appears, before a card has been focused - that must not close it.
        var hadFocus by remember { mutableStateOf(false) }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
            modifier = Modifier.onFocusChanged {
                if (it.hasFocus) hadFocus = true else if (hadFocus) open = false
            },
        ) {
            items(markers.size) { i ->
                val m = markers[i]
                ChapterCard(
                    marker = m,
                    tile = SpriteSheet.tileAt(tiles, m.seconds),
                    frameUrl = client.frameUrl(path, m.seconds),
                    current = i == current,
                    onClick = { onPick(m) },
                    modifier = if (i == current) Modifier.focusRequester(currentFocus) else Modifier,
                )
            }
        }
        LaunchedEffect(Unit) {
            repeat(10) {
                if (runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(25)
            }
            open = false                                   // couldn't focus a card: back to the label
        }
    }
}

@Composable
private fun ChapterCard(marker: Marker, tile: SpriteTile?, frameUrl: String, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(180.dp)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize().background(LustrColors.surfaceElevated)) {
                // the nearest seek-bar preview first; the exact frame on top when the server can make it
                tile?.let { SpriteTileImage(it, Modifier.fillMaxSize()) }
                AsyncImage(model = frameUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (current) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(LustrColors.primaryLight))
                }
            }
        }
        Text(marker.label, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
        Text(formatTime((marker.seconds * 1000).toLong()), style = MaterialTheme.typography.labelSmall, color = LustrColors.textSecondary)
    }
}

/** One tile cut out of the seek-bar preview sheet (loaded at full size, so the tile's pixel box fits). */
@Composable
private fun SpriteTileImage(tile: SpriteTile, modifier: Modifier = Modifier) {
    val request = ImageRequest.Builder(LocalPlatformContext.current).data(tile.image).size(CoilSize.ORIGINAL).build()
    val painter = rememberAsyncImagePainter(request)
    Canvas(modifier.clipToBounds()) {
        val sheet = painter.intrinsicSize
        if (sheet.isUnspecified || tile.w <= 0 || tile.h <= 0) return@Canvas
        val scale = maxOf(size.width / tile.w, size.height / tile.h)
        translate(-tile.x * scale, -tile.y * scale) {
            with(painter) { draw(Size(sheet.width * scale, sheet.height * scale)) }
        }
    }
}

// ---- helpers --------------------------------------------------------------------------------

private fun textGroups(tracks: Tracks) = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

/** "picture", "sound" or both when the file has such tracks but none could be picked (no decoder); else null. */
private fun undecodable(tracks: Tracks): String? {
    if (tracks.isEmpty) return null
    fun broken(type: Int) = tracks.groups.filter { it.type == type }.let { g -> g.isNotEmpty() && g.none { it.isSelected } }
    val picture = broken(C.TRACK_TYPE_VIDEO)
    val sound = broken(C.TRACK_TYPE_AUDIO)
    return when {
        picture && sound -> "picture and sound"
        picture -> "picture"
        sound -> "sound"
        else -> null
    }
}

/** For ⚙ → Now playing: what's being decoded right now. */
@OptIn(UnstableApi::class)
private fun nowPlaying(player: ExoPlayer, source: StreamSource?): List<String> {
    val v = player.videoFormat
    val a = player.audioFormat
    return listOf(
        "Source: " + (source?.let { if (it.isDirect) "original file (direct play)" else it.label.replace("HLS", "transcode") } ?: "-"),
        "Picture: " + (v?.let { "${it.width}×${it.height} ${it.sampleMimeType?.substringAfter('/') ?: ""}" } ?: "none"),
        "Sound: " + (a?.let { "${it.sampleMimeType?.substringAfter('/') ?: ""}, ${it.channelCount} channels" } ?: "none"),
    )
}

private fun trackLabel(g: Tracks.Group, fallback: String): String {
    val f = g.getTrackFormat(0)
    return listOfNotNull(f.label, f.language?.takeIf { it != "und" }).firstOrNull() ?: fallback
}

/** Pick one caption / audio track, or (null) turn captions off. */
private fun selectTrack(player: Player, type: Int, group: Tracks.Group?) {
    val b = player.trackSelectionParameters.buildUpon()
    if (group == null) {
        b.setTrackTypeDisabled(type, true)
    } else {
        b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
    }
    player.trackSelectionParameters = b.build()
}

/** CC: off -> the first caption track -> off. */
private fun toggleCaptions(player: Player, tracks: Tracks) {
    val groups = textGroups(tracks)
    selectTrack(player, C.TRACK_TYPE_TEXT, if (groups.any { it.isSelected }) null else groups.firstOrNull())
}

@OptIn(UnstableApi::class)
private fun VideoZoom.resizeMode(): Int = when (this) {
    VideoZoom.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoZoom.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoZoom.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
