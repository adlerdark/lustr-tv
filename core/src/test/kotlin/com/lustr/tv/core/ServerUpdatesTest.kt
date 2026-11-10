package com.lustr.tv.core

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What lustr 0.9 - 0.27 added that the TV uses. Fixtures come from a lustr 0.27.0 server. */
class ServerUpdatesTest {
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

    // ---- multi-part videos (0.25) --------------------------------------------------------

    @Test
    fun multiPartStreams() = runTest {
        reply("streams_parts")
        val info = client.streams("/media/TestLib/Feature/Feature cd1.mp4")
        assertEquals(24.0, info.duration, 0.0)                         // the whole video
        assertEquals(listOf(1, 2), info.parts.map { it.part })
        assertEquals(listOf(0.0, 12.0), info.parts.map { it.offset })
        assertTrue(info.sprite!!.vtt!!.startsWith("/api/player/sprites/joined.vtt"))
        // each part's own file for the quality picked from the common list
        val direct = info.sources.first { it.isDirect }
        val files = Playback.partSources(info, direct)!!
        assertEquals(2, files.size)
        assertTrue(files[0].src.endsWith("Feature%20cd1.mp4"))
        assertTrue(files[1].src.endsWith("Feature%20cd2.mp4"))
        val hls = info.sources.first { it.isHls }
        assertTrue(Playback.partSources(info, hls)!![1].src.contains("cd2.mp4"))
        // a quality one part lacks, and a normal file
        assertNull(Playback.partSources(info, direct.copy(res = "720")))
        assertNull(Playback.partSources(info.copy(parts = emptyList()), direct))
    }

    @Test
    fun multiPartInListings() {
        val v = LustrClient.json.decodeFromString(
            Video.serializer(),
            """{"path": "/a cd1.mp4", "length": "00:00:24", "parts": [
                {"path": "/a cd1.mp4", "filename": "a cd1.mp4", "part": 1, "length": "00:00:12", "length_seconds": 12, "cache_id": "x"},
                {"path": "/a cd2.mp4", "filename": "a cd2.mp4", "part": 2, "length": "00:00:12"}]}""",
        )
        assertEquals(listOf("a cd1.mp4", "a cd2.mp4"), v.parts.map { it.filename })
        assertEquals("00:00:24", v.length)
    }

    // ---- what a library opens with (0.16), images-only libraries (0.20) ---------------

    @Test
    fun openingDefaults() = runTest {
        reply("init_0_27")       // default: Library tab, Recently Added (newest first); TestLib: Movies, by Cast; Studios: tab "images"
        val init = client.init()
        assertEquals(LibraryOpening(LibraryTab.MOVIES, SortOption.CAST, false), init.openingFor("TestLib"))
        assertEquals(LibraryOpening(LibraryTab.LIBRARY, SortOption.RECENTLY_ADDED, true), init.openingFor("LibA"))
        // no Images tab on the TV: the default tab; its sort is the default too
        assertEquals(LibraryOpening(null, SortOption.RECENTLY_ADDED, true), init.openingFor("Studios"))
        // an older server (no config) or a sort the TV doesn't have: the TV's own defaults
        assertEquals(LibraryOpening(), init.copy(config = null).openingFor("TestLib"))
        val odd = LustrClient.json.decodeFromString(InitData.serializer(),
            """{"config": {"default_sort": "Markers", "default_tab": "nonsense", "library_defaults": []}}""")
        assertEquals(LibraryOpening(), odd.openingFor("X"))
    }

    @Test
    fun imagesOnlyLibrariesLeftOut() = runTest {
        reply("init_0_27")       // LibB holds only images; Extras = [LibB]
        val init = client.init()
        assertEquals("images", init.libraryContent("LibB"))
        assertNull(init.libraryContent("LibA"))
        val tree = LibraryTree.build(init)
        val shown = LibraryTree.visible(tree, setOf("Studios", "Extras")).map { it.name }
        assertTrue("LibB" !in shown)
        assertTrue("Extras" !in shown)                                   // a group left with nothing in it goes too
        assertTrue("LibA" in shown && "TestLib" in shown)
    }

    // ---- resolution / chapters filters (0.17, 0.19) --------------------------------------

    @Test
    fun keywordFilters() = runTest {
        var f = VideoFilter(query = "jane", resolution = ResolutionFilter.HD, chapters = ChaptersFilter.HAS_MARKERS)
        assertEquals("jane res:hd markers:yes", f.serverQuery)
        assertEquals(2, f.activeCount)
        assertEquals("res:4k", VideoFilter(resolution = ResolutionFilter.UHD).serverQuery)
        assertEquals("", VideoFilter().serverQuery)

        server.enqueue(MockResponse().setBody(fixture("videos")))
        client.videos("TestLib", f)
        assertEquals("jane res:hd markers:yes", server.takeRequest().requestUrl!!.queryParameter("query"))

        f = f.clearFilters()
        assertNull(f.resolution)
        assertNull(f.chapters)
        assertEquals("jane", f.query)
    }

    // ---- movie page: cast and tags ----------------------------------------------------

    @Test
    fun movieCast() = runTest {
        reply("movie_0_27")      // not linked to ThePornDB: the cast of its scenes
        assertEquals(listOf("Jane Doe"), client.movie(1).movie!!.performers)
        val linked = LustrClient.json.decodeFromString(Movie.serializer(), """{"id": 2, "tags": ["feature"], "cast": [],
            "ext_cast": [{"name": "A B", "local": null, "image": "https://cdn.example/a.jpg"},
                         {"name": "Jane D", "credited": "", "local": "Jane Doe", "in_movie": false},
                         {"name": "Kim", "local": "Kim", "in_movie": true}]}""")
        assertEquals(listOf("Kim", "Jane Doe"), linked.performers)        // only yours, the ones in the movie first
        assertEquals(listOf("feature"), linked.tags)
    }

    // ---- caption look (a TV setting) ------------------------------------------------------

    @Test
    fun captionLookSettings() {
        val s = TvSettings()
        assertEquals(CaptionBackground.HALF, s.captionBackground)
        assertEquals(CaptionEdge.DROP_SHADOW, s.captionEdge)
        val changed = s.copy(captionColor = CaptionColor.YELLOW, captionPosition = CaptionPosition.TOP, captionFont = CaptionFont.MONO)
        assertEquals(changed, TvSettings.decode(TvSettings.encode(changed)))
        // settings saved by an older app keep working, with the new ones at their defaults
        assertEquals(TvSettings().copy(seekStepSeconds = 30), TvSettings.decode("""{"seekStepSeconds": 30}"""))
    }
}
