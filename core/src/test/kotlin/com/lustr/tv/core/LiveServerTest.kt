package com.lustr.tv.core

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real lustr server, only when these are set (skipped otherwise):
 *   LUSTR_TEST_URL=http://192.168.1.20:8008 LUSTR_TEST_USER=... LUSTR_TEST_PASSWORD=... ./gradlew :core:test
 * Read-only apart from logging in and out.
 */
class LiveServerTest {
    private val url = System.getenv("LUSTR_TEST_URL")
    private val user = System.getenv("LUSTR_TEST_USER")
    private val password = System.getenv("LUSTR_TEST_PASSWORD")

    @Test
    fun loginBrowseAndOpenStreams() = runBlocking {
        assumeTrue("LUSTR_TEST_URL not set", !url.isNullOrBlank() && user != null && password != null)
        val http = OkHttpClient.Builder().cookieJar(PersistentCookieJar(MemoryStorage())).build()
        val client = LustrClient(LustrClient.parseServerUrl(url!!)!!, http)

        assertEquals("ok", client.health().status)
        client.login(user!!, password!!)
        assertTrue(client.authStatus().authenticated)

        val libraries = client.init().visibleLibraries
        println("libraries: $libraries")
        val rows = client.home()
        println("home rows: ${rows.map { "${it.title} (${it.items.size})" }}")
        val page = client.videos(limit = 5)
        println("videos: ${page.total}")

        val first = page.items.firstOrNull() ?: rows.flatMap { it.items }.firstOrNull()
        if (first != null) {
            assertEquals(first.path, client.video(first.path).path)
            val streams = client.streams(first.path)
            val direct = Playback.tvOrder(streams.sources).first()
            println("${first.displayTitle}: ${streams.sources.map { it.label }}, ${streams.captions?.tracks?.size ?: 0} caption tracks")
            // the media URL must accept the session cookie, with a range request (seeking)
            val media = Request.Builder().url(client.absoluteUrl(direct.src)).header("Range", "bytes=0-1023").build()
            http.newCall(media).execute().use { assertEquals(206, it.code) }
            first.thumb?.let { thumb ->
                http.newCall(Request.Builder().url(client.absoluteUrl(thumb)).build()).execute().use { assertEquals(200, it.code) }
            }
        }

        client.logout()
        assertTrue(!client.authStatus().authenticated)
    }
}
