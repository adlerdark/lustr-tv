package com.lustr.tv.core

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The #-Z bar, chapters (markers + thumbnails) and the cast category filter. */
class JumpAndChaptersTest {
    private val server = MockWebServer()
    private lateinit var client: LustrClient

    @Before
    fun setUp() {
        server.start()
        client = LustrClient(server.url("/"), OkHttpClient.Builder().cookieJar(PersistentCookieJar(MemoryStorage())).build())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun lettersLikeTheServer() {
        assertEquals("E", Letters.of("Émilie"))                   // accents dropped, like _letter_of
        assertEquals("A", Letters.of("  alpha"))
        assertEquals("#", Letters.of("3 Girls"))
        assertEquals("#", Letters.of(""))
        assertEquals(27, Letters.ALL.size)
        assertEquals(
            listOf(LetterIndex("#", 100), LetterIndex("A", 101), LetterIndex("B", 103)),
            Letters.index(listOf("10 Things", "Alpha", "Ångström", "beta", "Bravo"), offset = 100),
        )
    }

    @Test
    fun videoPageLetters() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[],"total":3,"letters":[{"letter":"A","index":0},{"letter":"M","index":2}]}"""))
        assertEquals(listOf(LetterIndex("A", 0), LetterIndex("M", 2)), client.videos().letters)
        assertTrue("Cast" in Letters.SORTS && "Title" in Letters.SORTS && "Recently Added" !in Letters.SORTS)
    }

    @Test
    fun markers() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"success":true,"markers":[{"id":1,"seconds":0,"end_seconds":null,"title":"Intro","tag":"","source":"chapter"},
               {"id":2,"seconds":95.5,"end_seconds":120,"title":"","tag":"Outdoor","source":"user"}],"tags":["Outdoor"]}""",
        ))
        val m = client.markers("TestLib_Alpha_Scene")
        assertEquals(listOf("Intro", "Outdoor"), m.map { it.label })        // no title: the tag
        assertEquals(95.5, m[1].seconds, 0.0)
        assertEquals("TestLib_Alpha_Scene", server.takeRequest().requestUrl!!.queryParameter("video_id"))
    }

    @Test
    fun spriteSheet() = runTest {
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:10.000
            sprite.jpg#xywh=0,0,160,90

            00:00:10.000 --> 00:01:05.500
            sprite.jpg#xywh=160,0,160,90

            01:00:00.000 --> 01:00:10.000
            sprite.jpg#xywh=320,90,160,90
        """.trimIndent()
        val tiles = SpriteSheet.parse(vtt)
        assertEquals(3, tiles.size)
        assertEquals(65.5, tiles[1].end, 0.0)
        assertEquals(3600.0, tiles[2].start, 0.0)
        assertEquals(160, SpriteSheet.tileAt(tiles, 30.0)!!.x)
        assertEquals(320, SpriteSheet.tileAt(tiles, 99999.0)!!.x)          // past the end: the last tile
        assertNull(SpriteSheet.tileAt(emptyList(), 1.0))

        // fetched next to the VTT, with the image as a full URL (and the login cookie)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(vtt)
        }
        val fetched = client.spriteTiles(SpriteInfo(ready = true, vtt = "/cache/abc/thumbs.vtt?v=3"))
        assertEquals("http://${server.hostName}:${server.port}/cache/abc/sprite.jpg", fetched[0].image)
        assertTrue(client.spriteTiles(SpriteInfo(ready = false, vtt = "/x.vtt")).isEmpty())
    }

    @Test
    fun frameUrl() {
        assertEquals(
            "http://${server.hostName}:${server.port}/api/player/frame?path=%2Fmedia%2Fa%20b.mp4&t=95.5&w=320",
            client.frameUrl("/media/a b.mp4", 95.5),
        )
    }

    @Test
    fun castCategory() {
        val cast = listOf(CastMember("A", category = "pro"), CastMember("B", category = null), CastMember("C", category = "amateur"),
            CastMember("D", category = "celeb"))
        assertEquals(listOf("A", "B"), CastBrowse.filter(cast, category = CastCategoryFilter.PRO, sort = CastSort.NAME).map { it.name })
        assertEquals(listOf("C"), CastBrowse.filter(cast, category = CastCategoryFilter.AMATEUR).map { it.name })
        assertEquals(listOf("D"), CastBrowse.filter(cast, category = CastCategoryFilter.CELEB).map { it.name })
        assertEquals(4, CastBrowse.filter(cast).size)
    }

    @Test
    fun hoverPreviewFrames() {
        val v = Video(path = "/a.mp4", cacheId = "abc", thumb = "/cache/abc/thumb_0.webp?v=17")
        val frames = HoverPreview.frames(v)
        assertEquals(8, frames.size)
        assertEquals("/cache/abc/thumb_0.webp?v=17", frames.first())
        assertEquals("/cache/abc/thumb_7.webp?v=17", frames.last())
        // a custom cover is the card picture, the frames are still the thumb_ ones; no ?v= -> none added
        assertEquals("/cache/abc/thumb_3.webp", HoverPreview.frames(v.copy(thumb = "/cache/abc/cover.webp"))[3])
        assertTrue(HoverPreview.frames(v.copy(cacheId = null)).isEmpty())
        assertTrue(HoverPreview.frames(v.copy(thumb = null)).isEmpty())      // thumbnails not made yet
    }
}
