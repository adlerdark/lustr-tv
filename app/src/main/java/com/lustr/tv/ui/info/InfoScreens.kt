package com.lustr.tv.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.lustr.tv.AppContainer
import com.lustr.tv.Load
import com.lustr.tv.core.CastBio
import com.lustr.tv.core.PagedState
import com.lustr.tv.core.Video
import com.lustr.tv.core.categoryLabel
import com.lustr.tv.core.genderLabel
import com.lustr.tv.core.pretty
import com.lustr.tv.ui.browse.EDGE
import com.lustr.tv.ui.browse.InfoRow
import com.lustr.tv.ui.browse.LocalVideoActions
import com.lustr.tv.ui.browse.PagedGrid
import com.lustr.tv.ui.browse.PersonCard
import com.lustr.tv.ui.browse.SectionTitle
import com.lustr.tv.ui.browse.VideoCard
import com.lustr.tv.ui.components.CenteredMessage
import com.lustr.tv.ui.theme.LustrColors
import kotlinx.coroutines.launch

// ---- Video info ---------------------------------------------------------------------------------

/** A video's cast (with photos, each opens their bio) and its metadata, read-only; Play and ★. */
@Composable
fun VideoInfoScreen(container: AppContainer, path: String, onPlay: (Video) -> Unit, onCast: (String) -> Unit) {
    val client = remember { container.client }
    var video by remember { mutableStateOf<Load<Video>>(Load.Loading) }
    LaunchedEffect(path) { video = container.load { video(path) } }
    val playFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val actions = LocalVideoActions.current

    when (val v = video) {
        Load.Loading -> CenteredMessage("Loading…")
        is Load.Failed -> CenteredMessage(v.message)
        is Load.Ready -> {
            val d = v.value
            val favorite = actions?.isFavorite(d) ?: d.isFavorite
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 48.dp, end = EDGE, top = 32.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Box(Modifier.width(360.dp).aspectRatio(16f / 9f).background(LustrColors.surfaceElevated, RoundedCornerShape(8.dp))) {
                            d.thumb?.let { AsyncImage(model = client.absoluteUrl(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            SectionTitle(d.displayTitle)
                            Text(
                                listOfNotNull(d.length?.removePrefix("00:"), d.resolution, d.library, d.movie?.takeIf { it.isNotBlank() }?.let { "Movie: $it" })
                                    .joinToString(" · "),
                                color = LustrColors.textSecondary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                                Button(onClick = { onPlay(d) }, modifier = Modifier.focusRequester(playFocus)) { Text("▶  Play") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val r = container.load { toggleFavorite(d.path) }
                                        if (r is Load.Ready) container.videoFavorites[d.path] = r.value
                                    }
                                }) { Text(if (favorite) "★  Favorite" else "☆  Add to favorites") }
                            }
                        }
                    }
                }
                if (d.cast.isNotEmpty()) {
                    item { Heading("Cast") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                            items(d.cast) { name ->
                                PersonCard(
                                    name = name,
                                    photoUrl = client.castPhotoUrl(name),
                                    favorite = container.castFavorites[name] ?: false,
                                    onClick = { onCast(name) },
                                    modifier = Modifier.width(130.dp),
                                )
                            }
                        }
                    }
                }
                if (d.metadata.isNotEmpty()) {
                    item { Heading("Details") }
                    for ((label, values) in d.metadata) item { InfoRow(label, values.joinToString(", ")) }
                }
            }
            LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
        }
    }
}

// ---- Cast bio -----------------------------------------------------------------------------------

/** A performer like the web app's bio: photo, details, appearance, profile, libraries, all videos. Read-only except ★. */
@Composable
fun CastScreen(container: AppContainer, name: String, onPlay: (Video) -> Unit) {
    val client = remember { container.client }
    var bio by remember { mutableStateOf<Load<CastBio>>(Load.Loading) }
    var profile by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(name) {
        bio = container.load { castBio(name) }
        (container.load { castProfile(name) } as? Load.Ready)?.let { profile = it.value }
    }
    val favFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    when (val b = bio) {
        Load.Loading -> CenteredMessage("Loading…")
        is Load.Failed -> CenteredMessage(b.message)
        is Load.Ready -> {
            val d = b.value
            val favorite = container.castFavorites[d.name] ?: d.isFavorite
            PagedGrid(
                state = PagedState(items = d.videos, total = d.videos.size),
                onLoadMore = {},
                onRetry = {},
                key = { it.path },
                emptyText = "No videos.",
                header = {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                            Box(Modifier.width(200.dp).aspectRatio(3f / 4f).background(LustrColors.surfaceElevated, RoundedCornerShape(8.dp))) {
                                Text(d.name.take(1).uppercase(), color = LustrColors.textSecondary, style = MaterialTheme.typography.displaySmall,
                                    modifier = Modifier.align(Alignment.Center))
                                if (d.hasPhoto) {
                                    AsyncImage(model = client.castPhotoUrl(d.name), contentDescription = null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize())
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                                Text(d.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    listOf(genderLabel(d.gender), categoryLabel(d.category),
                                        "${d.stats.totalVideos} videos", "${d.stats.libraries} libraries").filter { it.isNotEmpty() }.joinToString(" · "),
                                    color = LustrColors.textSecondary,
                                )
                                if (d.aliases.isNotEmpty()) Text("Also known as: ${d.aliases.joinToString(", ")}", color = LustrColors.textPrimary)
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            if (container.load { setCastFavorite(d.name, !favorite) } is Load.Ready) {
                                                container.castFavorites[d.name] = !favorite
                                            }
                                        }
                                    },
                                    modifier = Modifier.focusRequester(favFocus).padding(top = 6.dp),
                                ) { Text(if (favorite) "★  Favorite" else "☆  Add to favorites") }
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    if (d.appearance.isNotEmpty()) {
                                        Facts("Appearance", d.appearance.map { (k, v) -> pretty(k) to v.joinToString(", ") }, Modifier.weight(1f))
                                    }
                                    if (profile.isNotEmpty()) Facts("Profile", profile, Modifier.weight(1f))
                                    if (d.libraryDistribution.isNotEmpty()) {
                                        Facts("Libraries", d.libraryDistribution.entries.sortedByDescending { it.value }.map { it.key to "${it.value}" },
                                            Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { Heading("Videos (${d.videos.size})") }
                },
            ) { video ->
                VideoCard(video, client::absoluteUrl, onClick = { onPlay(video) })
            }
            LaunchedEffect(Unit) { runCatching { favFocus.requestFocus() } }
        }
    }
}

/** A small titled list of "label: value" lines. */
@Composable
private fun Facts(title: String, rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = LustrColors.primarySoft)
        for ((label, value) in rows) {
            Row {
                Text("$label: ", style = MaterialTheme.typography.bodySmall, color = LustrColors.textSecondary)
                Text(value, style = MaterialTheme.typography.bodySmall, color = LustrColors.textBrightest)
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = LustrColors.primarySoft,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
    )
}
