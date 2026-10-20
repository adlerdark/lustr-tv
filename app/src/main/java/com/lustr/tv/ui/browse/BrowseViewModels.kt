package com.lustr.tv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lustr.tv.AppContainer
import com.lustr.tv.Load
import com.lustr.tv.core.ALL_VIDEOS
import com.lustr.tv.core.CastBrowse
import com.lustr.tv.core.CastMember
import com.lustr.tv.core.FilterCounts
import com.lustr.tv.core.HomeRow
import com.lustr.tv.core.LibraryTab
import com.lustr.tv.core.LibraryTree
import com.lustr.tv.core.Movie
import com.lustr.tv.core.MovieDetail
import com.lustr.tv.core.MovieSort
import com.lustr.tv.core.Letters
import com.lustr.tv.core.Page
import com.lustr.tv.core.Pager
import com.lustr.tv.core.SEARCH
import com.lustr.tv.core.SidebarNode
import com.lustr.tv.core.SidebarState
import com.lustr.tv.core.SortOption
import com.lustr.tv.core.Video
import com.lustr.tv.core.VideoCollection
import com.lustr.tv.core.VideoFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.random.Random

/** Run [reload] whenever something changes what lists show (AppContainer.dataVersion, e.g. the orientation filter). */
private fun ViewModel.reloadOnDataChange(container: AppContainer, reload: () -> Unit) {
    viewModelScope.launch { container.dataVersion.drop(1).collect { reload() } }
}

/** The sidebar: libraries and groups from /api/init, what's open, which groups are expanded, each library's tab. */
class BrowseViewModel(private val container: AppContainer) : ViewModel() {
    private val _tree = MutableStateFlow<Load<List<SidebarNode>>>(Load.Loading)
    val tree: StateFlow<Load<List<SidebarNode>>> = _tree

    private val _sidebar = MutableStateFlow(SidebarState())
    val sidebar: StateFlow<SidebarState> = _sidebar

    /** The tab each library was left on (this session); a library opens on Recommended, like the web app. */
    private val _tabs = MutableStateFlow<Map<String, LibraryTab>>(emptyMap())
    val tabs: StateFlow<Map<String, LibraryTab>> = _tabs

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            if (_tree.value !is Load.Ready) _tree.value = Load.Loading
            _tree.value = when (val r = container.load { this.init() }) {
                is Load.Ready -> Load.Ready(LibraryTree.build(r.value))
                is Load.Failed -> r
                Load.Loading -> Load.Loading
            }
        }
    }

    fun click(node: SidebarNode) = _sidebar.update { it.click(node) }

    /** Home's tabs: 0 = Recommended, 1 = Cast. */
    private val _homeTab = MutableStateFlow(0)
    val homeTab: StateFlow<Int> = _homeTab
    fun selectHomeTab(i: Int) {
        _homeTab.value = i
    }

    /** The last search (Search in the sidebar); its results open as their own page. */
    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search
    fun search(query: String) {
        _search.value = query
        _sidebar.update { it.open(SEARCH) }
    }

    fun selectTab(library: String, tab: LibraryTab) = _tabs.update { it + (library to tab) }

    fun logOut(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                container.client.logout()
            } catch (e: IOException) {
                // logged out locally either way
            }
            container.forgetLogin()
            onDone()
        }
    }
}

/** Home rows (library = null) or a library's / group's Recommended tab. */
class RowsViewModel(private val container: AppContainer, private val library: String?) : ViewModel() {
    private val _rows = MutableStateFlow<Load<List<HomeRow>>>(Load.Loading)
    val rows: StateFlow<Load<List<HomeRow>>> = _rows

    /** Called whenever the tab is shown again, so Continue Watching stays current. */
    fun load() {
        viewModelScope.launch {
            val r = container.load { home(library) }
            if (r is Load.Ready || _rows.value !is Load.Ready) _rows.value = r     // keep old rows if a refresh fails
        }
    }
}

/** The Library tab: every video of a library / group, searched, sorted and filtered. */
class LibraryViewModel(private val container: AppContainer, private val library: String) : ViewModel() {
    private val seed = Random.nextInt(1, 1_000_000)          // a Random sort keeps its order between pages

    /** Starts with the default length filter from Settings (e.g. no videos under 2 minutes). */
    private val _filter = MutableStateFlow(VideoFilter(lengths = container.settings.value.defaultLengths))
    val filter: StateFlow<VideoFilter> = _filter

    val pager = Pager<Video>(viewModelScope, pageSize = 48, onError = container::onError) { page, limit ->
        container.client.videos(library, _filter.value, page, limit, seed).let { Page(it.items, it.total, it.letters) }
    }

    /** Tag / site counts for the filter panel (null while loading). */
    private val _counts = MutableStateFlow<FilterCounts?>(null)
    val counts: StateFlow<FilterCounts?> = _counts
    private var countsJob: Job? = null

    init {
        pager.reset()
        reloadOnDataChange(container) { pager.reset() }
    }

    fun setFilter(f: VideoFilter) {
        if (f == _filter.value) return
        _filter.value = f
        pager.reset()
        if (_counts.value != null || countsJob != null) loadCounts()
    }

    /** (Re)load the counts for the current filters; called when the filter panel opens and as filters change. */
    fun loadCounts() {
        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            val r = container.load { availableFilters(library, _filter.value) }
            if (r is Load.Ready) _counts.value = r.value
        }
    }
}

/** The Collections tab: the list, or one opened collection's videos. */
class CollectionsViewModel(private val container: AppContainer, private val library: String) : ViewModel() {
    private val _list = MutableStateFlow<Load<List<VideoCollection>>>(Load.Loading)
    val list: StateFlow<Load<List<VideoCollection>>> = _list

    private val _open = MutableStateFlow<VideoCollection?>(null)
    val open: StateFlow<VideoCollection?> = _open

    val items = Pager<Video>(viewModelScope, pageSize = 60, onError = container::onError) { page, limit ->
        val id = _open.value?.id ?: return@Pager Page(emptyList(), 0)
        container.client.collectionItems(id, page, limit).let { Page(it.items, it.total) }
    }

    init {
        load()
        reloadOnDataChange(container) { load(); if (_open.value != null) items.reset() }
    }

    fun load() {
        viewModelScope.launch { _list.value = container.load { collections(library) } }
    }

    fun open(c: VideoCollection) {
        _open.value = c
        items.reset()
    }

    fun close() {
        _open.value = null
    }
}

/** The Movies tab: movies of a library / group, or one opened movie with its scenes. */
class MoviesViewModel(private val container: AppContainer, private val library: String) : ViewModel() {
    /** Search, favorites, tags and sites (a movie has the tags of its scenes); lengths don't apply. */
    private val _filter = MutableStateFlow(VideoFilter())
    val filter: StateFlow<VideoFilter> = _filter

    private val _sort = MutableStateFlow(MovieSort.TITLE)
    val sort: StateFlow<MovieSort> = _sort

    /**
     * Big pages (movies are few): sorted by title, the #-Z bar comes from the server when it sends
     * letters, else from the titles loaded here (Letters.index).
     */
    val movies = Pager<Movie>(viewModelScope, pageSize = 500, onError = container::onError) { page, limit ->
        container.client.movies(library, _filter.value, _sort.value, page, limit).let { r ->
            val letters = when {
                _sort.value != MovieSort.TITLE -> emptyList()
                r.letters.isNotEmpty() -> r.letters
                else -> Letters.index(r.movies.map { it.displayTitle }, offset = (page - 1) * limit)
            }
            Page(r.movies, r.total, letters)
        }
    }

    /** Tag / site counts for the filter panel (null until it's opened). */
    private val _counts = MutableStateFlow<FilterCounts?>(null)
    val counts: StateFlow<FilterCounts?> = _counts
    private var countsJob: Job? = null

    private val _open = MutableStateFlow<Load<MovieDetail>?>(null)
    val open: StateFlow<Load<MovieDetail>?> = _open

    init {
        movies.reset()
        reloadOnDataChange(container) { movies.reset() }
    }

    fun setFilter(f: VideoFilter) {
        if (f == _filter.value) return
        _filter.value = f
        movies.reset()
        if (_counts.value != null || countsJob != null) loadCounts()
    }

    fun setSort(s: MovieSort) {
        if (s == _sort.value) return
        _sort.value = s
        movies.reset()
    }

    fun loadCounts() {
        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            val r = container.load { movieFilters(library, _filter.value) }
            if (r is Load.Ready) _counts.value = r.value
        }
    }

    fun open(m: Movie) {
        _open.value = Load.Loading
        viewModelScope.launch { _open.value = container.load { movie(m.id) } }
    }

    fun close() {
        _open.value = null
    }
}

/** Search results for the whole site: performers and movies whose names match, and every matching video. */
class SearchViewModel(private val container: AppContainer, val query: String) : ViewModel() {
    val videos = Pager<Video>(viewModelScope, pageSize = 48, onError = container::onError) { page, limit ->
        container.client.videos(ALL_VIDEOS, VideoFilter(query = query, sortBy = SortOption.TITLE.value), page, limit)
            .let { Page(it.items, it.total) }
    }

    private val _cast = MutableStateFlow<List<CastMember>>(emptyList())
    val cast: StateFlow<List<CastMember>> = _cast

    private val _movies = MutableStateFlow<List<Movie>>(emptyList())
    val movies: StateFlow<List<Movie>> = _movies

    init {
        videos.reset()
        viewModelScope.launch {
            (container.load { cast() } as? Load.Ready)?.let { _cast.value = CastBrowse.filter(it.value, query = query).take(30) }
        }
        viewModelScope.launch {
            (container.load { movies(ALL_VIDEOS, VideoFilter(query = query), limit = 30) } as? Load.Ready)?.let { _movies.value = it.value.movies }
        }
    }
}
