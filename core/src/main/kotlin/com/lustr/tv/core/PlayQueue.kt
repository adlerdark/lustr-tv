package com.lustr.tv.core

/**
 * Videos played one after another: a collection or a movie's scenes ("Play all", or any video
 * started from inside it). [items] may be only the start of a long collection: [more] fetches the
 * next [pageSize] from the server (page numbers like /api/collections/{id}/items) when the end of
 * what's there is reached and [total] says there are more.
 */
class PlayQueue(
    /** The collection's / movie's name, shown in the player. */
    val title: String,
    items: List<Video>,
    start: Int = 0,
    total: Int = items.size,
    private val pageSize: Int = 60,
    private val more: (suspend (page: Int) -> List<Video>)? = null,
) {
    var items: List<Video> = items
        private set
    var index: Int = start.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        private set
    val total: Int = maxOf(total, items.size)

    val current: Video get() = items[index]
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index + 1 < items.size || (more != null && items.size < total)

    /** "3 of 12" */
    val positionLabel: String get() = "${index + 1} of $total"

    /** The next video (fetching more of the collection when needed), or null at the end. */
    suspend fun next(): Video? {
        if (index + 1 >= items.size && more != null && items.size < total) {
            val page = items.size / pageSize + 1
            val known = items.map { it.path }.toSet()
            val fetched = more.invoke(page).filter { it.path !in known }
            if (fetched.isEmpty()) return null
            items = items + fetched
        }
        if (index + 1 >= items.size) return null
        index++
        return current
    }

    /** The video before, or null on the first one. */
    fun previous(): Video? {
        if (index == 0) return null
        index--
        return current
    }

    companion object {
        /** A queue starting at [video] in [items] (the first one with its path; the first video when it isn't there). */
        fun startingAt(
            title: String,
            items: List<Video>,
            video: Video,
            total: Int = items.size,
            pageSize: Int = 60,
            more: (suspend (page: Int) -> List<Video>)? = null,
        ) = PlayQueue(title, items, items.indexOfFirst { it.path == video.path }.coerceAtLeast(0), total, pageSize, more)
    }
}
