package com.lustr.tv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.lustr.tv.core.HomeRow
import com.lustr.tv.core.PagedState
import com.lustr.tv.core.Video
import com.lustr.tv.ui.theme.LustrColors

/** Space kept free at the content's right and bottom edges (TV overscan). */
val EDGE = 40.dp

/** Turns the server's relative links (thumbnails, covers) into full URLs. */
typealias ImageUrl = (String) -> String

/**
 * A picture card (16:9, or [aspect], e.g. 2:3 for posters) with a title and a small line under it.
 * Grows when focused (Card's default); [onLongClick] = holding OK.
 */
@Composable
fun PictureCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    imageFit: ContentScale = ContentScale.Crop,
    aspect: Float = 16f / 9f,
    onLongClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Column(modifier) {
        Card(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().aspectRatio(aspect)) {
            Box(Modifier.fillMaxSize().background(LustrColors.surfaceElevated)) {
                // the initial shows until the picture loads, and stays when there is none (e.g. a 404)
                Text(
                    title.take(1).uppercase(),
                    fontSize = 40.sp,
                    color = LustrColors.textSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
                if (imageUrl != null) {
                    AsyncImage(model = imageUrl, contentDescription = null, contentScale = imageFit, modifier = Modifier.fillMaxSize())
                }
                overlay()
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = LustrColors.textBrighter,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp),
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = LustrColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/**
 * A video: thumbnail, length, resume bar, title, cast / site. [label] replaces the title (e.g. "Scene 2").
 * Holding OK opens the video menu (favorite, video info, file info; see VideoActions).
 */
@Composable
fun VideoCard(video: Video, imageUrl: ImageUrl, onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val actions = LocalVideoActions.current
    PictureCard(
        title = label ?: video.displayTitle,
        imageUrl = video.thumb?.let(imageUrl),
        onClick = onClick,
        modifier = modifier,
        subtitle = if (label != null) video.displayTitle else (video.cast.take(2) + video.site.take(1)).joinToString(" · "),
        onLongClick = actions?.let { a -> { a.openMenu(video) } },
    ) {
        if (actions?.isFavorite(video) ?: video.isFavorite) {
            Text("★", color = LustrColors.favorite, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        video.length?.let { length ->
            Text(
                length.removePrefix("00:"),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        video.progressFraction?.let { fraction ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(LustrColors.surfaceElevated)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(LustrColors.primaryLight))
            }
        }
    }
}

/** A heading like the web app's row titles (bold, light grey). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = LustrColors.textBrighter,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Home / Recommended: rows of videos scrolling sideways. */
@Composable
fun VideoRows(rows: List<HomeRow>, imageUrl: ImageUrl, onPlay: (Video) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = EDGE),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            Column {
                SectionTitle(row.title, Modifier.padding(start = 24.dp, top = 12.dp))
                LazyRow(
                    contentPadding = PaddingValues(start = 24.dp, end = EDGE, top = 14.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(row.items, key = { it.path }) { video ->
                        VideoCard(video, imageUrl, onClick = { onPlay(video) }, modifier = Modifier.width(230.dp))
                    }
                }
            }
        }
    }
}

/**
 * A grid that asks for the next page as it nears the end ([PagedState] + Pager.loadMore).
 * [header] scrolls with the grid (e.g. a movie's details).
 */
@Composable
fun <T> PagedGrid(
    state: PagedState<T>,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    key: (T) -> Any,
    emptyText: String,
    modifier: Modifier = Modifier,
    minCell: Dp = 210.dp,
    header: (LazyGridScope.() -> Unit)? = null,
    cell: @Composable (T) -> Unit,
) {
    val grid = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val info = grid.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) onLoadMore() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCell),
        state = grid,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = EDGE, top = 16.dp, bottom = EDGE),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        header?.invoke(this)
        items(state.items.size, key = { key(state.items[it]) }) { cell(state.items[it]) }
        val message = when {
            state.error != null -> "Couldn't load: ${state.error}"
            state.loading -> "Loading…"
            state.endReached && state.items.isEmpty() -> emptyText
            else -> null
        }
        if (message != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(message, color = LustrColors.textSecondary)
                    if (state.error != null) Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("Try again") }
                }
            }
        }
    }
}

/** A performer: portrait photo (or their initial), name, how many videos, ★ when a favorite. */
@Composable
fun PersonCard(
    name: String,
    photoUrl: String?,
    favorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    PictureCard(
        title = name,
        imageUrl = photoUrl,
        onClick = onClick,
        modifier = modifier,
        subtitle = subtitle,
        aspect = 3f / 4f,
    ) {
        if (favorite) {
            Text("★", color = LustrColors.favorite, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}
