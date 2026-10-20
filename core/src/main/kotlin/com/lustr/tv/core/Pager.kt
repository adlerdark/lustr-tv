package com.lustr.tv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * What a paged grid shows: a window of the whole list ([items] start at position [offset]),
 * and whether more is coming either way.
 */
data class PagedState<T>(
    val items: List<T> = emptyList(),
    /** Total on the server; -1 until the first page arrives. */
    val total: Int = -1,
    val loading: Boolean = false,
    val error: String? = null,
    /** Position of items[0] in the whole list (> 0 after a jump to a letter). */
    val offset: Int = 0,
    /** Letters of the whole list (jump bar), when the server sends them. */
    val letters: List<LetterIndex> = emptyList(),
    internal val firstPage: Int = 1,
    internal val page: Int = 0,
    internal val lastPageEmpty: Boolean = false,
    internal val loadingPrevious: Boolean = false,
) {
    val endReached: Boolean get() = lastPageEmpty || (total >= 0 && offset + items.size >= total)

    /** Pages before the first one shown can still be loaded (scrolling up after a jump). */
    val hasPrevious: Boolean get() = firstPage > 1
}

/** One page from the server: the items, the total, and the letters (if any). */
data class Page<T>(val items: List<T>, val total: Int, val letters: List<LetterIndex> = emptyList())

/**
 * Loads a list page by page (a library, a collection, movies): [loadMore] when the grid nears
 * its end, [loadPrevious] near its start, [jumpTo] for the letter bar, [reset] when the filters
 * change. [onError] sees every failure (e.g. to go to login).
 */
class Pager<T>(
    private val scope: CoroutineScope,
    val pageSize: Int,
    private val onError: (IOException) -> Unit = {},
    private val fetch: suspend (page: Int, limit: Int) -> Page<T>,
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
        if (s.loading || s.loadingPrevious || s.endReached || s.error != null) return
        _state.value = s.copy(loading = true)
        job = scope.launch {
            val next = s.page + 1
            load(next) { cur, p ->
                cur.copy(items = cur.items + p.items, total = p.total, page = next, loading = false, lastPageEmpty = p.items.isEmpty(),
                    letters = merge(cur.letters, p.letters))
            }
        }
    }

    /** Load the page before the first one shown (after a jump), unless busy or at the start. */
    fun loadPrevious() {
        val s = _state.value
        if (!s.hasPrevious || s.loading || s.loadingPrevious || s.error != null) return
        _state.value = s.copy(loadingPrevious = true)
        job = scope.launch {
            val prev = s.firstPage - 1
            load(prev) { cur, p ->
                cur.copy(items = p.items + cur.items, offset = (cur.offset - p.items.size).coerceAtLeast(0), firstPage = prev,
                    total = p.total, loadingPrevious = false, letters = merge(cur.letters, p.letters))
            }
        }
    }

    /**
     * Make sure position [index] is loaded: nothing to do when it already is; otherwise start
     * over at the page that holds it. Returns true when it's loaded already.
     */
    fun jumpTo(index: Int): Boolean {
        val s = _state.value
        if (index >= s.offset && index < s.offset + s.items.size) return true
        job?.cancel()
        val page = index / pageSize + 1
        _state.value = PagedState(total = s.total, letters = s.letters, offset = (page - 1) * pageSize, firstPage = page, page = page - 1)
        loadMore()
        return false
    }

    /** After an error: try that page again. */
    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }

    /**
     * Letters from several pages: the server sends the whole list with every page (same each time);
     * letters worked out on the TV come page by page. Each letter keeps its first position.
     */
    private fun merge(a: List<LetterIndex>, b: List<LetterIndex>): List<LetterIndex> =
        (a + b).groupBy { it.letter }.map { (letter, at) -> LetterIndex(letter, at.minOf { it.index }) }.sortedBy { it.index }

    private suspend fun load(page: Int, apply: (PagedState<T>, Page<T>) -> PagedState<T>) {
        try {
            val p = fetch(page, pageSize)
            _state.update { apply(it, p) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            _state.update { it.copy(loading = false, loadingPrevious = false, error = e.message ?: "Can't reach the server") }
            onError(e)
        }
    }
}
