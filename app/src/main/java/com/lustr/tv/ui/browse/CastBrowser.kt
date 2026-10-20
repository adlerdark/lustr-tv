package com.lustr.tv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.Load
import com.lustr.tv.core.CastBrowse
import com.lustr.tv.core.CastCategoryFilter
import com.lustr.tv.core.CastGenderFilter
import com.lustr.tv.core.CastMember
import com.lustr.tv.core.CastSort
import com.lustr.tv.core.PagedState
import com.lustr.tv.core.after
import com.lustr.tv.ui.theme.LustrColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Everyone in the cast (the server sends them all at once; searching and sorting happen on the TV). */
class CastBrowserViewModel(private val container: AppContainer) : ViewModel() {
    private val _cast = MutableStateFlow<Load<List<CastMember>>>(Load.Loading)
    val cast: StateFlow<Load<List<CastMember>>> = _cast

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { _cast.value = container.load { cast() } }
    }
}

/** Home's Cast tab: performers with photos; OK opens their bio. */
@Composable
fun CastBrowserContent(container: AppContainer, onCast: (String) -> Unit) {
    val vm: CastBrowserViewModel = viewModel(key = "cast-browser") { CastBrowserViewModel(container) }
    val cast by vm.cast.collectAsStateWithLifecycle()
    val client = remember { container.client }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(CastSort.MOST_VIDEOS) }
    var gender by remember { mutableStateOf(CastGenderFilter.ALL) }
    var category by remember { mutableStateOf(CastCategoryFilter.ALL) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }

    // favorites changed on this TV count straight away
    val shown: Load<List<CastMember>> = when (val c = cast) {
        is Load.Ready -> Load.Ready(
            CastBrowse.filter(
                c.value.map { m -> container.castFavorites[m.name]?.let { m.copy(isFavorite = it) } ?: m },
                query, favoritesOnly, gender, sort, category,
            ),
        )
        else -> c
    }

    Column {
        Row(
            Modifier.padding(start = 24.dp, end = EDGE, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { searching = true }) { Text(if (query.isBlank()) "Search" else "Search: $query", maxLines = 1) }
            OutlinedButton(onClick = { sort = CastSort.entries.after(sort) }) { Text("Sort: ${sort.label}") }
            OutlinedButton(onClick = { gender = CastGenderFilter.entries.after(gender) }) { Text(gender.label) }
            OutlinedButton(onClick = { category = CastCategoryFilter.entries.after(category) }) { Text(category.label) }
            OutlinedButton(onClick = { favoritesOnly = !favoritesOnly }) { Text(if (favoritesOnly) "★ Favorites only" else "☆ Everyone") }
            Spacer(Modifier.weight(1f))
            (shown as? Load.Ready)?.let { Text("${it.value.size} performers", color = LustrColors.textSecondary) }
        }
        PagedGrid(
            state = when (shown) {
                Load.Loading -> PagedState<CastMember>(loading = true)
                is Load.Failed -> PagedState<CastMember>(error = shown.message)
                is Load.Ready -> PagedState<CastMember>(items = shown.value, total = shown.value.size)
            },
            onLoadMore = {},
            onRetry = vm::load,
            key = { it.name },
            emptyText = "Nobody matches.",
            minCell = 150.dp,
        ) { m ->
            PersonCard(
                name = m.name,
                photoUrl = if (m.hasPhoto) client.castPhotoUrl(m.name) else null,
                favorite = m.isFavorite,
                onClick = { onCast(m.name) },
                subtitle = "${m.count} video${if (m.count == 1) "" else "s"}",
            )
        }
    }

    if (searching) {
        SearchDialog(
            title = "Search the cast",
            initial = query,
            hint = "Part of a name or alias",
            onSearch = { query = it; searching = false },
            onDismiss = { searching = false },
        )
    }
}
