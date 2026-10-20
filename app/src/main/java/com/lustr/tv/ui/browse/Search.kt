package com.lustr.tv.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.Load
import com.lustr.tv.core.MovieDetail
import com.lustr.tv.core.Video
import com.lustr.tv.ui.components.CenteredMessage
import kotlinx.coroutines.launch

/**
 * Search results for the whole site: matching performers (a row), movies (a row) and videos
 * (the grid; lustr's search syntax works, e.g. cast:jane*). OK on a movie opens it here.
 */
@Composable
fun SearchResults(container: AppContainer, query: String, imageUrl: ImageUrl, onPlay: (Video) -> Unit, onCast: (String) -> Unit) {
    val vm: SearchViewModel = viewModel(key = "search:$query") { SearchViewModel(container, query) }
    val videos by vm.videos.state.collectAsStateWithLifecycle()
    val cast by vm.cast.collectAsStateWithLifecycle()
    val movies by vm.movies.collectAsStateWithLifecycle()
    val client = remember { container.client }
    val scope = rememberCoroutineScope()
    var openMovie by remember { mutableStateOf<Load<MovieDetail>?>(null) }

    openMovie?.let { m ->
        BackHandler { openMovie = null }
        when (m) {
            Load.Loading -> CenteredMessage("Loading…")
            is Load.Failed -> CenteredMessage(m.message) { Button(onClick = { openMovie = null }) { Text("Back") } }
            is Load.Ready -> MovieDetailContent(m.value, imageUrl, onPlay, onBack = { openMovie = null })
        }
        return
    }

    PagedGrid(
        state = videos,
        onLoadMore = vm.videos::loadMore,
        onRetry = vm.videos::retry,
        key = { it.path },
        emptyText = "No videos match \"$query\".",
        header = {
            if (cast.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Cast") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(cast, key = { it.name }) { c ->
                            PersonCard(
                                name = c.name,
                                photoUrl = if (c.hasPhoto) client.castPhotoUrl(c.name) else null,
                                favorite = container.castFavorites[c.name] ?: c.isFavorite,
                                onClick = { onCast(c.name) },
                                subtitle = "${c.count} videos",
                                modifier = Modifier.width(130.dp),
                            )
                        }
                    }
                }
            }
            if (movies.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Movies") }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(movies, key = { it.id }) { m ->
                            PictureCard(
                                title = m.displayTitle,
                                imageUrl = m.cover?.let(imageUrl),
                                aspect = 2f / 3f,
                                subtitle = listOfNotNull(m.year?.takeIf { it.isNotBlank() }, "${m.count} scenes").joinToString(" · "),
                                onClick = {
                                    openMovie = Load.Loading
                                    scope.launch { openMovie = container.load { movie(m.id) } }
                                },
                                modifier = Modifier.width(130.dp),
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 8.dp)) {
                    SectionTitle(if (videos.total >= 0) "Videos (${videos.total})" else "Videos")
                }
            }
        },
    ) { video ->
        VideoCard(video, imageUrl, onClick = { onPlay(video) })
    }
}
