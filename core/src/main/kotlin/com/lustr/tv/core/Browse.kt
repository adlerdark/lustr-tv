package com.lustr.tv.core

/** "Home": the Home rows of everything; it has no tabs. */
const val HOME = "Home"

/** "All Videos": every library, opened like a library. */
const val ALL_VIDEOS = "All Videos"

/** The tabs of a library or group, as in the web app. */
enum class LibraryTab(val label: String) {
    RECOMMENDED("Recommended"),
    LIBRARY("Library"),
    COLLECTIONS("Collections"),
    MOVIES("Movies"),
}

/** One line of the sidebar. [depth] = how far it's indented (0 = top level). */
data class SidebarNode(
    val name: String,
    val isGroup: Boolean,
    val depth: Int = 0,
    val children: List<SidebarNode> = emptyList(),
)

/** Builds the sidebar the way the web app does (index.html: visibleLibraries). */
object LibraryTree {
    fun build(init: InitData): List<SidebarNode> {
        val groups = init.libraryGroups
        val libraries = init.libraries
        val hidden = init.hiddenLibraries.toSet()
        // display filter: libraries of an unselected type are left out, and so are groups left empty
        val filtering = init.displayFilter.size < init.displayTypes.size

        fun shownByType(name: String): Boolean {
            if (!filtering) return true
            val type = init.libraryTypes[name] ?: "mixed"
            return type == "mixed" || type in init.displayFilter
        }

        fun shownLibrary(name: String) = name in libraries && name !in hidden && shownByType(name)

        val inGroups = groups.values.flatMap { it.libraries }.filter { it in libraries }.toSet()
        val processed = mutableSetOf<String>()

        fun group(name: String, depth: Int, seen: Set<String>): SidebarNode? {
            val g = groups[name] ?: return null
            if (name in seen) return null                      // a group inside itself: stop
            val children = g.libraries.mapNotNull { item ->
                when {
                    item in groups -> group(item, depth + 1, seen + name)?.takeUnless { filtering && it.children.isEmpty() }
                    shownLibrary(item) -> SidebarNode(item, isGroup = false, depth = depth + 1).also { processed += item }
                    else -> null
                }
            }
            return SidebarNode(name, isGroup = true, depth = depth, children = children)
        }

        val out = mutableListOf(SidebarNode(HOME, isGroup = false))
        if (ALL_VIDEOS !in hidden) out += SidebarNode(ALL_VIDEOS, isGroup = false)
        for (item in init.libraryOrder) {
            val g = groups[item]
            if (g != null && g.parent.isNullOrEmpty()) {
                group(item, 0, emptySet())?.takeUnless { filtering && it.children.isEmpty() }?.let { out += it }
            } else if (shownLibrary(item) && item !in inGroups) {
                out += SidebarNode(item, isGroup = false)
                processed += item
            }
        }
        for (lib in libraries.keys) {                           // libraries missing from the order
            if (lib !in processed && lib !in inGroups && shownLibrary(lib)) out += SidebarNode(lib, isGroup = false)
        }
        return out
    }

    /** The lines to show: every node, plus the children of expanded groups. */
    fun visible(nodes: List<SidebarNode>, expanded: Set<String>): List<SidebarNode> = buildList {
        fun add(list: List<SidebarNode>) {
            for (n in list) {
                add(n)
                if (n.isGroup && n.name in expanded) add(n.children)
            }
        }
        add(nodes)
    }
}

/**
 * What's open and which groups are expanded. Groups start collapsed.
 * Clicking a group:
 *  - collapsed -> expand it and open it,
 *  - expanded but not open -> open it,
 *  - expanded and already open -> collapse it (it stays open).
 * So a double click on a group always ends collapsed and open.
 */
data class SidebarState(
    val active: String = HOME,
    val expanded: Set<String> = emptySet(),
) {
    fun click(node: SidebarNode): SidebarState = when {
        !node.isGroup -> copy(active = node.name)
        node.name !in expanded -> copy(active = node.name, expanded = expanded + node.name)
        active == node.name -> copy(expanded = expanded - node.name)
        else -> copy(active = node.name)
    }
}

/** Filters of the Library tab; the same parameters as the web app's /api/videos calls. */
data class VideoFilter(
    /** Search text, with lustr's syntax (cast:jane*, site:(a,b), path:2023, ...). */
    val query: String = "",
    val sortBy: String = SortOption.TITLE.value,
    val descending: Boolean = false,
    /** tag -> true (must have) / false (must not have) */
    val tags: Map<String, Boolean> = emptyMap(),
    val sites: Map<String, Boolean> = emptyMap(),
    val favoritesOnly: Boolean = false,
    /** Length ranges, e.g. "300-600" (see LengthRange). */
    val lengths: Set<String> = emptySet(),
) {
    /** How many filters (not search / sort) are on, for the "Filters (3)" button. Length counts once. */
    val activeCount: Int get() = tags.size + sites.size + (if (lengths.isNotEmpty()) 1 else 0) + (if (favoritesOnly) 1 else 0)

    fun cycleTag(tag: String) = copy(tags = cycle(tags, tag))
    fun cycleSite(site: String) = copy(sites = cycle(sites, site))
    fun toggleLength(range: String) = copy(lengths = if (range in lengths) lengths - range else lengths + range)
    fun clearFilters() = copy(tags = emptyMap(), sites = emptyMap(), lengths = emptySet(), favoritesOnly = false)

    internal fun include(map: Map<String, Boolean>) = map.filterValues { it }.keys.joinToString(",").ifEmpty { null }
    internal fun exclude(map: Map<String, Boolean>) = map.filterValues { !it }.keys.joinToString(",").ifEmpty { null }
    internal val lengthParam: String? get() = LengthRange.entries.map { it.value }.filter { it in lengths }.joinToString(",").ifEmpty { null }

    private companion object {
        /** off -> include -> exclude -> off */
        fun cycle(map: Map<String, Boolean>, key: String): Map<String, Boolean> = when (map[key]) {
            null -> map + (key to true)
            true -> map + (key to false)
            false -> map - key
        }
    }
}

/** The web app's length buttons. */
enum class LengthRange(val value: String, val label: String) {
    UNDER_2("0-120", "<2 min"),
    MIN_2_5("120-300", "2-5 min"),
    MIN_5_10("300-600", "5-10 min"),
    MIN_10_20("600-1200", "10-20 min"),
    MIN_20_40("1200-2400", "20-40 min"),
    OVER_40("2400-28800", "40+ min"),
}

/** Sorts of the Library tab (server names), with the direction that makes sense first. */
enum class SortOption(val value: String, val label: String, val descendingByDefault: Boolean) {
    TITLE("Title", "Title", false),
    CAST("Cast", "Cast", false),
    RECENTLY_ADDED("Recently Added", "Recently added", true),
    DATE_CREATED("Date Created", "Date created", true),
    LENGTH("Length", "Length", true),
    FILE_SIZE("File Size", "File size", true),
    RATING("Rating", "Rating", true),
    MOST_PLAYED("Most Played", "Most played", true),
    LAST_PLAYED("Last Played", "Last played", true),
    FILENAME("Filename", "File name", false),
    RANDOM("Random", "Random", false);

    companion object {
        fun of(value: String) = entries.firstOrNull { it.value == value } ?: TITLE
    }
}
