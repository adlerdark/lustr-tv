package com.lustr.tv.core

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Code login, network scan, settings, quality order and remote seeking. */
class TvFeaturesTest {
    private val server = MockWebServer()
    private lateinit var jar: PersistentCookieJar
    private lateinit var client: LustrClient

    @Before
    fun setUp() {
        server.start()
        jar = PersistentCookieJar(MemoryStorage())
        client = LustrClient(server.url("/"), OkHttpClient.Builder().cookieJar(jar).build())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name.json")!!.readText()

    // ---- code login --------------------------------------------------------------------------

    @Test
    fun pairingFlow() = runTest {
        server.enqueue(MockResponse().setBody("""{"pair_id":"secret-id","code":"0481","expires_in":300,"poll_every":2}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
        server.enqueue(
            MockResponse().setBody("""{"status":"approved"}""")
                .addHeader("Set-Cookie", "video_library_session=tok; HttpOnly; Max-Age=14400; Path=/"),
        )
        val p = client.pairStart("SHIELD Android TV")
        assertEquals("0481", p.code)                     // leading zero kept
        assertEquals(2, p.pollEvery)
        assertFalse(client.pairPoll(p.pairId).approved)
        assertTrue(client.pairPoll(p.pairId).approved)
        assertTrue(jar.hasCookiesFor(server.url("/")))  // logged in

        val start = server.takeRequest()
        assertEquals("/api/tv/pair/start", start.path)
        assertEquals("""{"device_name":"SHIELD Android TV"}""", start.body.readUtf8())
        assertEquals("""{"pair_id":"secret-id"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun olderServerHasNoCodeLogin() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))
        try {
            client.pairStart("TV"); fail()
        } catch (e: LustrException.ServerError) {
            assertTrue(e.notSupported)
        }
    }

    @Test
    fun passwordLoginAsksForTvSession() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        client.login("owner", "pw", client = "tv")
        client.login("owner", "pw")
        assertEquals("""{"username":"owner","password":"pw","client":"tv"}""", server.takeRequest().body.readUtf8())
        assertEquals("""{"username":"owner","password":"pw"}""", server.takeRequest().body.readUtf8())
    }

    // ---- network scan -----------------------------------------------------------------------

    @Test
    fun hostsAround() {
        val hosts = ServerScanner.hostsAround("192.168.1.30")
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertTrue(ServerScanner.hostsAround("not an ip").isEmpty())
        assertTrue(ServerScanner.hostsAround("fe80::1").isEmpty())
    }

    @Test
    fun scanFindsOnlyLustr() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/api/health" -> MockResponse().setBody("""{"status":"ok","version":"0.6.1"}""")
                "/api/check_auth" -> MockResponse().setBody("""{"authenticated":false,"username":null}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val other = MockWebServer()                      // some other web server on the network
        other.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"status":"ok"}""")
        }
        other.start()
        try {
            val http = OkHttpClient()
            assertEquals(listOf("http://127.0.0.1:${server.port}/"), ServerScanner.scan(listOf("127.0.0.1"), http, server.port).map { it.toString() })
            assertTrue(ServerScanner.scan(listOf("127.0.0.1"), http, other.port).isEmpty())
            // nothing listening at all
            val closed = MockWebServer().apply { start() }.let { val p = it.port; it.shutdown(); p }
            assertTrue(ServerScanner.scan(listOf("127.0.0.1"), http, closed).isEmpty())
        } finally {
            other.shutdown()
        }
    }

    // ---- settings ----------------------------------------------------------------------------

    @Test
    fun settingsDefaultsAndStorage() {
        val s = TvSettings()
        assertFalse(LengthRange.UNDER_2.value in s.defaultLengths)          // < 2 min hidden by default
        assertEquals(5, s.defaultLengths.size)
        assertEquals(10, s.seekStepSeconds)
        assertTrue(s.animatedPreviews)
        assertFalse(TvSettings.decode("""{"animatedPreviews":false}""").animatedPreviews)
        val changed = s.copy(seekStepSeconds = 30, resume = ResumeMode.ASK, defaultLengths = emptySet())
        assertEquals(changed, TvSettings.decode(TvSettings.encode(changed)))
        assertEquals(TvSettings(), TvSettings.decode("garbage"))
        assertEquals(TvSettings(), TvSettings.decode(null))
        assertEquals(TvSettings().copy(zoom = VideoZoom.ZOOM), TvSettings.decode("""{"zoom":"ZOOM","unknown":1}"""))
        assertEquals(15, TvSettings.SEEK_STEPS.after(10))
        assertEquals(5, TvSettings.SEEK_STEPS.after(30))                   // wraps around
    }

    // ---- player ------------------------------------------------------------------------------

    @Test
    fun preferredQuality() {
        val sources = LustrClient.json.decodeFromString(StreamInfo.serializer(), fixture("streams")).sources
        // the file offers direct, original, 240
        assertEquals(listOf("direct", "original", "240"), Playback.order(sources, "direct").map { it.res })
        assertEquals(listOf("original", "direct", "240"), Playback.order(sources, "original").map { it.res })
        assertEquals(listOf("240", "direct", "original"), Playback.order(sources, "720").map { it.res })  // no 720: next lower
        val onlyDirect = sources.filter { it.isDirect }
        assertEquals(listOf("direct"), Playback.order(onlyDirect, "720").map { it.res })
    }

    @Test
    fun remoteSeekTaps() {
        val seek = RemoteSeek(stepMs = 10_000)
        assertEquals(60_000, seek.tap(current = 50_000, direction = 1, duration = 100_000))
        assertEquals(70_000, seek.tap(current = 50_000, direction = 1, duration = 100_000))   // builds on the pending target
        assertEquals(70_000L, seek.take())
        assertNull(seek.take())
        assertEquals(0, seek.tap(current = 4_000, direction = -1, duration = 100_000))         // not before the start
        seek.take()
        assertEquals(99_000, seek.tap(current = 95_000, direction = 1, duration = 100_000))    // not past the end
    }

    @Test
    fun remoteSeekHoldSpeedsUp() {
        val seek = RemoteSeek(stepMs = 10_000)
        val long = 10 * 3_600_000L                           // long enough not to reach the end
        // held: a key-down every 50 ms (like Android's key repeat) from t = 0
        var target = 0L
        val jumps = mutableListOf<Long>()
        var t = 0L
        while (t <= 10_000) {
            val before = seek.target ?: 0L
            target = seek.press(t, current = 0, direction = 1, duration = long)
            if (target != before) jumps += target - before
            t += 50
        }
        // 10 s jumps first, then 30 s, 1 min, 2 min; at most one jump per 150 ms (not one per repeat)
        assertEquals(listOf(10_000L, 30_000L, 60_000L, 120_000L), jumps.distinct())
        assertTrue(jumps.size in 60..70)
        assertEquals(target, seek.take())
    }

    @Test
    fun remoteSeekSameSpeedWhateverTheRepeatRate() {
        // a keyboard sending a down every 30 ms ends up where a remote at 100 ms does
        fun after(stepEveryMs: Long): Long {
            val seek = RemoteSeek(stepMs = 10_000)
            var t = 0L
            while (t <= 3_000) {
                seek.press(t, 0, 1, 3_600_000)
                t += stepEveryMs
            }
            return seek.take()!!
        }
        val fast = after(30)
        val slow = after(150)
        assertTrue("fast $fast slow $slow", kotlin.math.abs(fast - slow) <= 60_000)
    }

    @Test
    fun remoteSeekNewBurstAfterAPauseOrTurn() {
        val seek = RemoteSeek(stepMs = 10_000)
        seek.press(0, 100_000, 1, 3_600_000)                  // 110 s
        seek.press(2_000, 100_000, 1, 3_600_000)              // a separate press later: 10 s again, from the target
        assertEquals(120_000L, seek.target)
        seek.press(2_100, 100_000, -1, 3_600_000)             // turning round: a new seek, 10 s back
        assertEquals(110_000L, seek.take())
    }

    @Test
    fun castSortExists() {
        assertEquals(SortOption.CAST, SortOption.of("Cast"))
        assertFalse(SortOption.CAST.descendingByDefault)
    }
}
