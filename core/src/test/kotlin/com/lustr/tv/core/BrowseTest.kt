package com.lustr.tv.core

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Sidebar, tabs and filters. Fixtures come from a lustr 0.6.1 server with a nested group, a movie and collections. */
class BrowseTest {
    private val server = MockWebServer()
    private lateinit var client: LustrClient

    @Before
    fun setUp() {
        server.start()
        client = LustrClient(server.url("/"), OkHttpClient.Builder().cookieJar(PersistentCookieJar(MemoryStorage())).build())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name.json")!!.readText()
    private fun reply(name: String) = server.enqueue(MockResponse().setBody(fixture(name)))
    private fun names(nodes: List<SidebarNode>) = nodes.map { "  ".repeat(it.depth) + it.name + if (it.isGroup) "/" else "" }

    // ---- sidebar -------------------------------------------------------------------------

    @Test
    fun treeLikeTheWebApp() = runTest {
        reply("init_groups")          // order: Studios, TestLib, LibA, LibB; Studios = [LibA, Extras]; Extras = [LibB]
        val tree = LibraryTree.build(client.init())
        // LibA / LibB are in groups, so not repeated at the top; Extras is a sub-group, so only inside Studios
        assertEquals(listOf("Home", "All Videos", "Studios/", "TestLib"), names(LibraryTree.visible(tree, emptySet())))
        assertEquals(
            listOf("Home", "All Videos", "Studios/", "  LibA", "  Extras/", "TestLib"),
            names(LibraryTree.visible(tree, setOf("Studios"))),
        )
        assertEquals(
            listOf("Home", "All Videos", "Studios/", "  LibA", "  Extras/", "    LibB", "TestLib"),
            names(LibraryTree.visible(tree, setOf("Studios", "Extras"))),
        )
        // an expanded sub-group inside a collapsed group stays out of sight
        assertEquals(listOf("Home", "All Videos", "Studios/", "TestLib"), names(LibraryTree.visible(tree, setOf("Extras"))))
    }

    @Test
    fun hiddenLibrariesAndDisplayFilter() {
        val init = InitData(
            libraries = mapOf("Straight" to emptyList(), "Gay" to emptyList(), "Mixed" to emptyList(), "Hidden" to emptyList(), "Loose" to emptyList()),
            libraryOrder = listOf("G", "Hidden", "Mixed"),
            hiddenLibraries = listOf("Hidden", "All Videos"),
            libraryGroups = mapOf("G" to LibraryGroup(listOf("Straight", "Gay")), "Empty" to LibraryGroup(listOf("Gay"), parent = "G")),
            libraryTypes = mapOf("Straight" to "straight", "Gay" to "gay", "Mixed" to "mixed"),
            displayTypes = listOf("straight", "gay"),
            displayFilter = listOf("straight"),
        )
        val tree = LibraryTree.build(init)
        // no All Videos (hidden), Gay filtered out, Hidden hidden, Loose (not in the order) at the end
        assertEquals(listOf("Home", "G/", "  Straight", "Mixed", "Loose"), names(LibraryTree.visible(tree, setOf("G"))))

        // without filtering, empty groups stay and every type shows
        val all = LibraryTree.build(init.copy(displayFilter = listOf("straight", "gay")))
        assertEquals(listOf("Home", "G/", "  Straight", "  Gay", "Mixed", "Loose"), names(LibraryTree.visible(all, setOf("G"))))
    }

    @Test
    fun groupInsideItselfDoesNotLoop() {
        val init = InitData(
            libraries = mapOf("A" to emptyList()),
            libraryOrder = listOf("G"),
            libraryGroups = mapOf("G" to LibraryGroup(listOf("A", "G"))),
        )
        assertEquals(listOf("Home", "All Videos", "G/", "  A"), names(LibraryTree.visible(LibraryTree.build(init), setOf("G"))))
    }

    @Test
    fun groupClickRules() {
        val group = SidebarNode("Studios", isGroup = true)
        val lib = SidebarNode("TestLib", isGroup = false)
        var s = SidebarState()
        assertEquals(HOME, s.active)
        assertTrue(s.expanded.isEmpty())                                   // groups start collapsed

        s = s.click(group)                                                 // collapsed -> expand + open
        assertEquals(SidebarState("Studios", setOf("Studios")), s)
        s = s.click(group)                                                 // open + expanded -> collapse, still open
        assertEquals(SidebarState("Studios", emptySet()), s)
        s = s.click(group)                                                 // collapsed again -> expand (still open)
        assertEquals(SidebarState("Studios", setOf("Studios")), s)

        s = s.click(lib)                                                   // a library opens; the group stays expanded
        assertEquals(SidebarState("TestLib", setOf("Studios")), s)
        s = s.click(group)                                                 // expanded, not open -> just open it
        assertEquals(SidebarState("Studios", setOf("Studios")), s)

        // double click on a collapsed group: ends collapsed and open
        val double = SidebarState("TestLib").click(group).click(group)
        assertEquals(SidebarState("Studios", emptySet()), double)
    }

    // ---- filters --------------------------------------------------------------------------

    @Test
    fun filterCyclesAndParams() = runTest {
        var f = VideoFilter(query = "cast:jane*", sortBy = "Recently Added", descending = true)
        f = f.cycleTag("outdoor")                       // include
        f = f.cycleTag("pov").cycleTag("pov")           // include -> exclude
        f = f.cycleSite("studiox").cycleSite("studiox").cycleSite("studiox")   // back to off
        f = f.toggleLength("2400-28800").toggleLength("0-120").copy(favoritesOnly = true)
        assertEquals(mapOf("outdoor" to true, "pov" to false), f.tags)
        assertTrue(f.sites.isEmpty())
        assertEquals(4, f.activeCount)                  // 2 tags + length (2 ranges count once) + favorites

        server.enqueue(MockResponse().setBody(fixture("videos")))
        client.videos("Studios", f, page = 2, limit = 30, seed = 7)
        val url = server.takeRequest().requestUrl!!
        assertEquals("Studios", url.queryParameter("library"))
        assertEquals("cast:jane*", url.queryParameter("query"))
        assertEquals("outdoor", url.queryParameter("tags"))
        assertEquals("pov", url.queryParameter("tags_exclude"))
        assertNull(url.queryParameter("sites"))
        assertEquals("true", url.queryParameter("favorites_only"))
        assertEquals("0-120,2400-28800", url.queryParameter("length_filter"))   // in the web app's order
        assertEquals("Recently Added", url.queryParameter("sort_by"))
        assertEquals("true", url.queryParameter("desc"))
        assertEquals("2", url.queryParameter("page"))
        assertEquals("7", url.queryParameter("seed"))

        assertEquals(0, f.clearFilters().activeCount)
        assertEquals("cast:jane*", f.clearFilters().query)
    }

    @Test
    fun availableFilters() = runTest {
        reply("filters")
        val counts = client.availableFilters("Studios", VideoFilter())
        assertEquals(mapOf("outdoor" to 2), counts.tags)
        assertEquals(mapOf("studiox" to 2), counts.sites)
        assertEquals("/api/available_filters", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun sortOptions() {
        assertEquals(SortOption.MOST_PLAYED, SortOption.of("Most Played"))
        assertEquals(SortOption.TITLE, SortOption.of("nonsense"))
    }

    // ---- collections and movies --------------------------------------------------------

    @Test
    fun collections() = runTest {
        reply("collections")
        val list = client.collections(ALL_VIDEOS)
        assertEquals(listOf("Outdoor smart", "Picks"), list.map { it.name })
        assertTrue(list[0].isSmart)
        assertEquals(2, list[0].count)
        assertTrue(list[0].cover!!.startsWith("/cache/"))
        assertNull(list[1].cover)

        reply("collection_items")
        val items = client.collectionItems(4)
        assertEquals("Outdoor smart", items.collection!!.name)
        assertEquals(2, items.total)
        assertEquals(listOf("Jane Doe"), items.items[0].cast)
        assertEquals(ALL_VIDEOS, server.takeRequest().requestUrl!!.queryParameter("library"))
        assertEquals("/api/collections/4/items", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun movies() = runTest {
        reply("movies")
        val list = client.movies("Studios")
        assertEquals(1, list.total)
        val m = list.movies[0]
        assertEquals("Big Movie", m.displayTitle)
        assertEquals("", m.year)
        assertFalse(m.hasCover)
        assertEquals(2, m.count)

        reply("movie")
        val detail = client.movie(1)
        assertNull(detail.movie!!.year)                   // null here, "" in the list
        assertEquals(40.0, detail.movie!!.runtime!!, 0.0)
        assertEquals(listOf("1", "2"), detail.items.map { it.movieScene })
    }

    @Test
    fun yearAsNumberIsAccepted() {
        val m = LustrClient.json.decodeFromString(Movie.serializer(), """{"id": 3, "title": "X", "year": 2019}""")
        assertEquals("2019", m.year)
    }
}
