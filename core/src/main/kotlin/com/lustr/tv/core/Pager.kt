package com.lustr.tv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** What a paged grid shows: the items so far, and whether more are coming. */
data class PagedState<T>(
    val items: List<T> = emptyList(),
    /** Total on the server; -1 until the first page arrives. */
    val total: Int = -1,
    val loading: Boolean = false,
    val error: String? = null,
    internal val page: Int = 0,
    internal val lastPageEmpty: Boolean = false,
) {
    val endReached: Boolean get() = lastPageEmpty || (total >= 0 && items.size >= total)
}

/**
 * Loads a list page by page (a library, a collection, movies): [loadMore] when the grid nears
 * its end, [reset] when the filters change. [onError] sees every failure (e.g. to go to login).
 */
class Pager<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int,
    private val onError: (IOException) -> Unit = {},
    private val fetch: suspend (page: Int, limit: Int) -> Pair<List<T>, Int>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state
    private var job: Job? = null

    /** Start over from page 1 (dropping a page that's still loading). */
    fun reset() {
        job?.cancel()
        _state.value = PagedState()
        loadMore()
    }

    /** Load the next page, unless one is loading, the end is reached or the last one failed. */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached || s.error != null) return
        _state.value = s.copy(loading = true)
        job = scope.launch {
            try {
                val (items, total) = fetch(s.page + 1, pageSize)
                _state.update {
                    it.copy(items = it.items + items, total = total, page = s.page + 1, loading = false, lastPageEmpty = items.isEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, error = e.message ?: "Can't reach the server") }
                onError(e)
            }
        }
    }

    /** After an error: try that page again. */
    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }
}
