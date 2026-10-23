package com.lustr.tv.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.Load
import com.lustr.tv.core.Letters
import com.lustr.tv.core.MovieDetail
import com.lustr.tv.core.MovieSort
import com.lustr.tv.core.PagedState
import com.lustr.tv.core.after
import com.lustr.tv.core.SortOption
import com.lustr.tv.core.Video
import com.lustr.tv.ui.components.CenteredMessage
import com.lustr.tv.ui.theme.LustrColors

/** A one-shot list shown in a PagedGrid. */
private fun <T> Load<List<T>>.asPaged(): PagedState<T> = when (this) {
    Load.Loading -> PagedState(loading = true)
    is Load.Failed -> PagedState(error = message)
    is Load.Ready -> PagedState(items = value, total = value.size)
}

// ---- Recommended (and Home) --------------------------------------------------------------

@Composable
fun RecommendedTab(container: AppContainer, library: String?, imageUrl: ImageUrl, onPlay: (Video) -> Unit) {
    val vm: RowsViewModel = viewModel(key = "rows:${library ?: "<home>"}") { RowsViewModel(container, library) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }     // also after coming back from the player

    when (val r = rows) {
        Load.Loading -> CenteredMessage("Loading…")
        is Load.Failed -> CenteredMessage(r.message) { Button(onClick = vm::load) { Text("Try again") } }
        is Load.Ready ->
            if (r.value.isEmpty()) {
                CenteredMessage("Nothing to recommend yet. Play something, or add videos in lustr's web app.")
            } else {
                VideoRows(r.value, imageUrl, onPlay)
            }
    }
}

// ---- Library ------------------------------------------------------------------------------

@Composable
fun LibraryTabContent(container: AppContainer, library: String, imageUrl: ImageUrl, onPlay: (Video) -> Unit) {
    val vm: LibraryViewModel = viewModel(key = "library:$library") { LibraryViewModel(container, library) }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val state by vm.pager.state.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }    // "search" / "sort" / "filters"

    Column {
        Row(
            Modifier.padding(start = 24.dp, end = EDGE, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { dialog = "search" }, modifier = Modifier.widthIn(max = 260.dp)) {
                Text(
                    if (filter.query.isBlank()) "Search" else "Search: ${filter.query}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val sort = SortOption.of(filter.sortBy)
            OutlinedButton(onClick = { dialog = "sort" }) { Text("Sort: ${sort.label} ${if (filter.descending) "↓" else "↑"}") }
            OutlinedButton(onClick = { vm.loadCounts(); dialog = "filters" }) {
                Text(if (filter.activeCount > 0) "Filters (${filter.activeCount})" else "Filters")
            }
            if (filter.clearable) {   // keeps the length filter
                OutlinedButton(onClick = { vm.setFilter(filter.clearFilters().copy(query = "")) }) { Text("Clear") }
            }
            Spacer(Modifier.weight(1f))
            if (state.total >= 0) Text("${state.total} videos", color = LustrColors.textSecondary)
        }
        PagedGrid(
            state = state,
            onLoadMore = vm.pager::loadMore,
            onRetry = vm.pager::retry,
            onLoadPrevious = vm.pager::loadPrevious,
            letters = if (filter.sortBy in Letters.SORTS) state.letters else emptyList(),     // #-Z bar
            onJump = { vm.pager.jumpTo(it) },
            key = { it.path },
            emptyText = if (filter.activeCount > 0 || filter.query.isNotBlank()) "No videos match these filters." else "No videos here yet.",
        ) { video ->
            VideoCard(video, imageUrl, onClick = { onPlay(video) })
        }
    }

    when (dialog) {
        "search" -> SearchDialog(
            title = "Search $library",
            initial = filter.query,
            hint = "Words, or lustr's search syntax: cast:jane*  site:(a,b)  path:2023",
            onSearch = { vm.setFilter(filter.copy(query = it)); dialog = null },
            onDismiss = { dialog = null },
        )
        "sort" -> SortDialog(
            current = filter,
            onPick = { vm.setFilter(it); dialog = null },
            onDismiss = { dialog = null },
        )
        "filters" -> FilterDialog(
            filter = filter,
            counts = counts,
            onChange = vm::setFilter,
            onDismiss = { dialog = null },
        )
    }
}

// ---- Collections --------------------------------------------------------------------------

@Composable
fun CollectionsTab(container: AppContainer, library: String, imageUrl: ImageUrl, onPlay: (Video) -> Unit) {
    val vm: CollectionsViewModel = viewModel(key = "collections:$library") { CollectionsViewModel(container, library) }
    val list by vm.list.collectAsStateWithLifecycle()
    val open by vm.open.collectAsStateWithLifecycle()
    val items by vm.items.state.collectAsStateWithLifecycle()

    val opened = open
    if (opened == null) {
        PagedGrid(
            state = list.asPaged(),
            onLoadMore = {},
            onRetry = vm::load,
            key = { it.id },
            emptyText = "No collections here. Make them in lustr's web app (Collections tab).",
        ) { c ->
            PictureCard(
                title = c.name,
                imageUrl = c.cover?.let(imageUrl),
                onClick = { vm.open(c) },
                subtitle = (if (c.isSmart) "Smart · " else "") + "${c.count} video${if (c.count == 1) "" else "s"}",
            )
        }
    } else {
        BackHandler { vm.close() }
        PagedGrid(
            state = items,
            onLoadMore = vm.items::loadMore,
            onRetry = vm.items::retry,
            key = { it.path },
            emptyText = "This collection is empty.",
            header = {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DetailHeader(
                        title = opened.name,
                        lines = listOfNotNull(
                            listOfNotNull(
                                items.total.takeIf { it >= 0 }?.let { "$it videos" },
                                "smart collection".takeIf { opened.isSmart },
                            ).joinToString(" · ").ifEmpty { null },
                            opened.description?.takeIf { it.isNotBlank() },
                        ),
                        onBack = vm::close,
                    )
                }
            },
        ) { video ->
            VideoCard(video, imageUrl, onClick = { onPlay(video) })
        }
    }
}

// ---- Movies -------------------------------------------------------------------------------

@Composable
fun MoviesTab(container: AppContainer, library: String, imageUrl: ImageUrl, onPlay: (Video) -> Unit) {
    val vm: MoviesViewModel = viewModel(key = "movies:$library") { MoviesViewModel(container, library) }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val movies by vm.movies.state.collectAsStateWithLifecycle()
    val open by vm.open.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }    // "search" / "filters"
    val filtered = filter.activeCount > 0 || filter.query.isNotBlank()

    when (val o = open) {
        null -> Column {
            Row(
                Modifier.padding(start = 24.dp, end = EDGE, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { dialog = "search" }, modifier = Modifier.widthIn(max = 240.dp)) {
                    Text(if (filter.query.isBlank()) "Search" else "Search: ${filter.query}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { vm.setSort(MovieSort.entries.after(sort)) }) { Text("Sort: ${sort.label}") }
                OutlinedButton(onClick = { vm.loadCounts(); dialog = "filters" }) {
                    Text(if (filter.activeCount > 0) "Filters (${filter.activeCount})" else "Filters")
                }
                if (filter.clearable) OutlinedButton(onClick = { vm.setFilter(filter.clearFilters().copy(query = "")) }) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                if (movies.total >= 0) Text("${movies.total} movies", color = LustrColors.textSecondary)
            }
            // posters stand upright (2:3); a movie without a cover shows a scene, cropped
            PagedGrid(
                state = movies,
                onLoadMore = vm.movies::loadMore,
                onRetry = vm.movies::retry,
                onLoadPrevious = vm.movies::loadPrevious,
                letters = movies.letters,                       // only when sorted by title
                onJump = { vm.movies.jumpTo(it) },
                key = { it.id },
                emptyText = if (filtered) "No movies match." else "No movies here. lustr builds them from scenes (web app: Movies tab).",
                minCell = 150.dp,
            ) { m ->
                PictureCard(
                    title = m.displayTitle,
                    imageUrl = m.cover?.let(imageUrl),
                    onClick = { vm.open(m) },
                    aspect = 2f / 3f,
                    subtitle = listOfNotNull(m.year?.takeIf { it.isNotBlank() }, "${m.count} scene${if (m.count == 1) "" else "s"}")
                        .joinToString(" · "),
                ) {
                    if (m.favorite) {
                        Text("★", color = LustrColors.favorite, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                    }
                }
            }
        }
        else -> {
            BackHandler { vm.close() }
            when (o) {
                Load.Loading -> CenteredMessage("Loading…")
                is Load.Failed -> CenteredMessage(o.message) { Button(onClick = vm::close) { Text("Back") } }
                is Load.Ready -> MovieDetailContent(o.value, imageUrl, onPlay, onBack = vm::close)
            }
        }
    }

    when (dialog) {
        "search" -> SearchDialog(
            title = "Search movies",
            initial = filter.query,
            hint = "Part of a movie title or studio",
            onSearch = { vm.setFilter(filter.copy(query = it)); dialog = null },
            onDismiss = { dialog = null },
        )
        "filters" -> FilterDialog(
            filter = filter,
            counts = counts,
            onChange = vm::setFilter,
            onDismiss = { dialog = null },
            showLength = false,
        )
    }
}

/** One movie: its details, then its scenes in order (landscape cards). Used by the Movies tab and search. */
@Composable
fun MovieDetailContent(detail: MovieDetail, imageUrl: ImageUrl, onPlay: (Video) -> Unit, onBack: () -> Unit) {
    val movie = detail.movie
    PagedGrid(
        state = PagedState(items = detail.items, total = detail.items.size),
        onLoadMore = {},
        onRetry = {},
        key = { it.path },
        emptyText = "No scenes.",
        header = {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DetailHeader(
                    title = movie?.displayTitle.orEmpty(),
                    lines = listOfNotNull(
                        listOfNotNull(
                            movie?.year?.takeIf { it.isNotBlank() },
                            movie?.studio?.takeIf { it.isNotBlank() },
                            movie?.runtime?.let { "${it.toInt()} min" },
                            "${detail.items.size} scenes",
                        ).joinToString(" · "),
                        movie?.director?.takeIf { it.isNotBlank() }?.let { "Director: $it" },
                        movie?.description?.takeIf { it.isNotBlank() },
                    ),
                    onBack = onBack,
                )
            }
        },
    ) { video ->
        val label = video.movieScene?.takeIf { it.isNotBlank() }?.let { "Scene $it" }
        VideoCard(video, imageUrl, onClick = { onPlay(video) }, label = label)
    }
}

/** Title, a few lines of details and a Back button, above an opened collection / movie. */
@Composable
internal fun DetailHeader(title: String, lines: List<String>, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onBack) { Text("‹ Back") }
            SectionTitle(title)
        }
        for (line in lines) {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = LustrColors.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}
