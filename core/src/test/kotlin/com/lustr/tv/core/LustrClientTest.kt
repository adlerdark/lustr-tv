package com.lustr.tv.core

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Parses responses recorded from a real lustr 0.6.1 server (src/test/resources/fixtures). */
class LustrClientTest {
    private val server = MockWebServer()
    private lateinit var cookies: PersistentCookieJar
    private lateinit var client: LustrClient

    @Before
    fun setUp() {
        server.start()
        cookies = PersistentCookieJar(MemoryStorage())
        client = LustrClient(server.url("/"), OkHttpClient.Builder().cookieJar(cookies).build())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) =
        javaClass.getResource("/fixtures/$name.json")!!.readText()

    private fun reply(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        })
    }

    @Test
    fun videoPage() = runTest {
        reply(fixture("videos"))
        val page = client.videos(library = "TestLib", limit = 2)
        assertEquals(3, page.total)
        assertEquals(2, page.items.size)
        val alpha = page.items[0]
        assertEquals("/media/TestLib/Alpha Scene.mp4", alpha.path)
        assertEquals("Alpha Scene", alpha.displayTitle)
        assertEquals("00:00:20", alpha.length)
        assertTrue(alpha.thumb!!.startsWith("/cache/"))
        assertEquals(0.4f, alpha.progressFraction!!, 0.001f)
        assertNull(page.items[1].progressFraction)        // no watch_progress key at all

        val request = server.takeRequest()
        assertEquals("/api/videos", request.requestUrl!!.encodedPath)
        assertEquals("TestLib", request.requestUrl!!.queryParameter("library"))
    }

    @Test
    fun homeRows() = runTest {
        reply(fixture("home"))
        val rows = client.home()
        assertEquals(listOf("continue", "recently_added", "rediscover"), rows.map { it.key })
        assertEquals("Continue Watching", rows[0].title)
        assertNull(rows[0].seeAll)
        assertEquals(3, rows[1].items.size)
    }

    @Test
    fun homeFailureIsAnError() = runTest {
        reply("""{"success": false, "error": "boom"}""")
        try {
            client.home(); fail()
        } catch (e: LustrException.ServerError) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun streamsAndTvOrder() = runTest {
        reply(fixture("streams"))
        val info = client.streams("/media/TestLib/Gamma Scene.mkv")
        assertEquals(15.0, info.duration, 0.0)
        assertEquals(1, info.startSource)               // the browser would skip the MKV...
        val order = Playback.tvOrder(info.sources)
        assertTrue(order[0].isDirect)                    // ...the Shield plays it directly
        assertEquals(listOf("direct", "original", "240"), order.map { it.res })
        assertEquals("http://${server.hostName}:${server.port}/files/media/TestLib/Gamma%20Scene.mkv",
            client.absoluteUrl(order[0].src))

        val url = server.takeRequest().requestUrl!!
        assertEquals("/media/TestLib/Gamma Scene.mkv", url.queryParameter("path"))   // spaces etc. encoded
    }

    @Test
    fun initLibraries() = runTest {
        reply(fixture("init"))
        assertEquals(listOf("TestLib"), client.init().visibleLibraries)
    }

    @Test
    fun visibleLibrariesKeepOrderAndDropHidden() {
        val init = InitData(
            libraries = mapOf("A" to emptyList(), "B" to emptyList(), "C" to emptyList(), "D" to emptyList()),
            libraryOrder = listOf("C", "A", "Gone"),
            hiddenLibraries = listOf("B"),
        )
        assertEquals(listOf("C", "A", "D"), init.visibleLibraries)
    }

    @Test
    fun loginKeepsCookieAndSendsIt() = runTest {
        reply("""{"status":"success","message":"Login successful"}""", 200,
            "Set-Cookie" to "video_library_session=abc123; HttpOnly; Path=/; SameSite=lax")
        reply("""{"authenticated":true,"username":"owner"}""")
        client.login("owner", "secret-pass")
        assertTrue(client.authStatus().authenticated)

        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals("""{"username":"owner","password":"secret-pass"}""", login.body.readUtf8())
        assertNull(login.getHeader("Origin"))            // the server's CSRF check lets origin-less clients through
        assertEquals("video_library_session=abc123", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun cookieSurvivesRestartAndLogoutClearsIt() = runTest {
        val storage = MemoryStorage()
        val jar = PersistentCookieJar(storage)
        val c = LustrClient(server.url("/"), OkHttpClient.Builder().cookieJar(jar).build())
        reply("{}", 200, "Set-Cookie" to "video_library_session=abc123; HttpOnly; Path=/")
        c.login("owner", "x")

        val restarted = PersistentCookieJar(storage)      // new app start, same storage
        assertTrue(restarted.hasCookiesFor(server.url("/")))

        // FastAPI's delete_cookie
        reply("{}", 200, "Set-Cookie" to "video_library_session=\"\"; expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/")
        c.logout()
        assertFalse(jar.hasCookiesFor(server.url("/")))
        assertFalse(PersistentCookieJar(storage).hasCookiesFor(server.url("/")))
    }

    @Test
    fun errors() = runTest {
        reply("""{"detail":"Invalid username or password"}""", 401)
        try {
            client.login("owner", "wrong"); fail()
        } catch (e: LustrException.NotLoggedIn) {
            assertEquals("Invalid username or password", e.message)
        }
        reply("""{"detail":"Too many wrong passwords. Try again in 5 minutes."}""", 429, "Retry-After" to "300")
        try {
            client.login("owner", "wrong"); fail()
        } catch (e: LustrException.TooManyAttempts) {
            assertEquals(300, e.retryAfterSeconds)
        }
        reply("Internal Server Error", 500)
        try {
            client.init(); fail()
        } catch (e: LustrException.ServerError) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun progressBody() = runTest {
        reply("""{"status":"ok"}""")
        client.reportProgress(ProgressReport("/media/a b.mp4", 12.5, 100.0, "p1", 12.0))
        assertEquals(
            """{"path":"/media/a b.mp4","current_time":12.5,"duration":100.0,"play_id":"p1","played":12.0,"log_only":false}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun parseServerUrl() {
        fun p(s: String) = LustrClient.parseServerUrl(s)?.toString()
        assertEquals("http://192.168.1.20:8008/", p("192.168.1.20"))
        assertEquals("http://192.168.1.20:9000/", p(" 192.168.1.20:9000/ "))
        assertEquals("http://nas.local:8008/", p("nas.local"))
        assertEquals("https://lustr.example.com/", p("https://lustr.example.com"))
        assertEquals("http://192.168.1.20/", p("http://192.168.1.20"))       // explicit scheme: port as given
        assertEquals("https://example.com/lustr/", p("https://example.com/lustr"))
        assertNull(p(""))
        assertNull(p("not a url"))
    }

    @Test
    fun absoluteUrlKeepsQuery() {
        val c = LustrClient("http://10.0.0.5:8008/".toHttpUrl(), OkHttpClient())
        assertEquals("http://10.0.0.5:8008/cache/abc/thumb_0.webp?v=17", c.absoluteUrl("/cache/abc/thumb_0.webp?v=17"))
    }
}

class PlaybackTest {
    @Test
    fun startPosition() {
        assertEquals(0.0, Playback.startPosition(null, 100.0), 0.0)
        assertEquals(0.0, Playback.startPosition(3.0, 100.0), 0.0)
        assertEquals(42.0, Playback.startPosition(42.0, 100.0), 0.0)
        assertEquals(0.0, Playback.startPosition(95.0, 100.0), 0.0)      // finished: start over
        assertEquals(42.0, Playback.startPosition(42.0, 0.0), 0.0)       // duration unknown
    }

    @Test
    fun trackerRules() {
        val t = PlayTracker("/media/a.mp4", "id1")
        assertNull(t.report(position = 1.0, duration = 60.0, final = true))   // opened and closed at once
        t.addPlayed(2.0)
        t.addPlayed(-5.0)
        val early = t.report(position = 2.0, duration = 60.0, final = true)!!
        assertTrue(early.logOnly)                                               // don't wipe the resume point
        assertEquals(2.0, early.played, 0.0)
        val normal = t.report(position = 30.0, duration = 60.0, final = false)!!
        assertFalse(normal.logOnly)
        assertEquals("id1", normal.playId)
    }
}

internal class MemoryStorage : CookieStorage {
    private var value: String? = null
    override fun load() = value
    override fun save(value: String?) {
        this.value = value
    }
}
