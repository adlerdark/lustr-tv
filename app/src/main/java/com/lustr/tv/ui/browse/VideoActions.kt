package com.lustr.tv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.Load
import com.lustr.tv.core.FileVersion
import com.lustr.tv.core.Video
import com.lustr.tv.ui.theme.LustrColors
import kotlinx.coroutines.launch

/** What a video card can do besides playing: the long-press menu, and favorites changed on this TV. */
class VideoActions(
    val openMenu: (Video) -> Unit,
    val isFavorite: (Video) -> Boolean,
)

/** Provided around all screens by [VideoActionsHost]; VideoCard uses it for long presses and the ★. */
val LocalVideoActions = staticCompositionLocalOf<VideoActions?> { null }

/**
 * Hosts the long-press menu (play, favorite, video info, file info) and the File info window
 * for every screen inside it.
 */
@Composable
fun VideoActionsHost(
    container: AppContainer,
    onPlay: (Video) -> Unit,
    onVideoInfo: (Video) -> Unit,
    content: @Composable () -> Unit,
) {
    var menuFor by remember { mutableStateOf<Video?>(null) }
    var fileInfoFor by remember { mutableStateOf<Video?>(null) }
    val actions = remember(container) {
        VideoActions(
            openMenu = { menuFor = it },
            isFavorite = { v -> container.videoFavorites[v.path] ?: v.isFavorite },
        )
    }
    CompositionLocalProvider(LocalVideoActions provides actions) { content() }

    menuFor?.let { video ->
        VideoMenu(
            container = container,
            video = video,
            favorite = actions.isFavorite(video),
            onPlay = { menuFor = null; onPlay(video) },
            onVideoInfo = { menuFor = null; onVideoInfo(video) },
            onFileInfo = { menuFor = null; fileInfoFor = video },
            onDismiss = { menuFor = null },
        )
    }
    fileInfoFor?.let { video -> FileInfoDialog(container, video.path, onDismiss = { fileInfoFor = null }) }
}

@Composable
private fun VideoMenu(
    container: AppContainer,
    video: Video,
    favorite: Boolean,
    onPlay: () -> Unit,
    onVideoInfo: () -> Unit,
    onFileInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var error by remember { mutableStateOf<String?>(null) }
    Panel(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionTitle(video.displayTitle, Modifier.padding(bottom = 8.dp))
            ChoiceRow("Play", onClick = onPlay, marker = "▶", modifier = Modifier.focusRequester(first))
            ChoiceRow(
                if (favorite) "Remove from favorites" else "Add to favorites",
                marker = if (favorite) "★" else "☆",
                markerColor = LustrColors.favorite,
                onClick = {
                    scope.launch {
                        when (val r = container.load { toggleFavorite(video.path) }) {
                            is Load.Ready -> container.videoFavorites[video.path] = r.value
                            is Load.Failed -> error = r.message
                            Load.Loading -> Unit
                        }
                    }
                },
            )
            ChoiceRow("Video info", onClick = onVideoInfo, marker = "ⓘ")
            ChoiceRow("File info", onClick = onFileInfo, marker = "≡")
            error?.let { Text(it, color = LustrColors.danger, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** Like the web app's File Info: the video's details and each of its files. Read-only. */
@Composable
private fun FileInfoDialog(container: AppContainer, path: String, onDismiss: () -> Unit) {
    var video by remember { mutableStateOf<Load<Video>>(Load.Loading) }
    LaunchedEffect(path) { video = container.load { video(path) } }
    val first = remember { FocusRequester() }

    Panel(onDismiss, side = true) {
        LazyColumn(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { SectionTitle("File info", Modifier.padding(bottom = 4.dp)) }
            when (val v = video) {
                Load.Loading -> item { InfoRow("Loading…", "", Modifier.focusRequester(first)) }
                is Load.Failed -> item { InfoRow("Couldn't load", v.message, Modifier.focusRequester(first)) }
                is Load.Ready -> {
                    val d = v.value
                    item { InfoRow("Title", d.displayTitle, Modifier.focusRequester(first)) }
                    infoRows(
                        "Date added" to d.dateAdded?.substringBefore('.'),
                        "Library" to d.library,
                        "Movie" to d.movie,
                        "Cast" to d.cast.joinToString(", "),
                    )
                    val files = d.versions.ifEmpty {
                        listOf(FileVersion(d.path, d.filename, d.resolution, d.codec, d.extension, d.length, d.totalBitrate, d.fileSize, d.cacheId, 1))
                    }
                    files.forEachIndexed { i, f ->
                        item { FileHeading(if (files.size == 1) "File" else "Version ${i + 1}" + if (f.isPreferred != 0) " (preferred)" else "") }
                        infoRows(
                            "Filename" to f.filename,
                            "Full path" to f.path,
                            "Cache ID" to f.cacheId,
                            "Resolution" to f.resolution,
                            "Codec" to f.codec,
                            "Container" to f.extension,
                            "Size" to f.fileSize,
                            "Length" to f.length,
                            "Bitrate" to f.totalBitrate,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(video is Load.Ready) { runCatching { first.requestFocus() } }
}

private fun LazyListScope.infoRows(vararg rows: Pair<String, String?>) {
    for ((label, value) in rows) {
        if (!value.isNullOrBlank()) item { InfoRow(label, value) }
    }
}

@Composable
private fun FileHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = LustrColors.primarySoft,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * One read-only "label: value" line. It can take focus (doing nothing on OK) so that the
 * remote can scroll through a long list of them.
 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = LustrColors.textBrightest,
            focusedContainerColor = LustrColors.surfaceElevated,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Text(label, color = LustrColors.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}
