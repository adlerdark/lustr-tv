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

    @Test
    fun everyLibraryAndTab() = runBlocking {
        assumeTrue("LUSTR_TEST_URL not set", !url.isNullOrBlank() && user != null && password != null)
        val http = OkHttpClient.Builder().cookieJar(PersistentCookieJar(MemoryStorage())).build()
        val client = LustrClient(LustrClient.parseServerUrl(url!!)!!, http)
        client.login(user!!, password!!)

        val tree = LibraryTree.build(client.init())
        val all = LibraryTree.visible(tree, tree.flatMap { allGroups(it) }.toSet())
        println("sidebar:\n" + all.joinToString("\n") { "  ".repeat(it.depth + 1) + it.name + if (it.isGroup) "/" else "" })

        for (node in all.filter { it.name != HOME }) {
            val rows = client.home(node.name)
            val page = client.videos(node.name, VideoFilter(), limit = 3)
            val counts = client.availableFilters(node.name, VideoFilter())
            val collections = client.collections(node.name)
            val movies = client.movies(node.name, limit = 3)
            println("${node.name}: ${rows.size} rows, ${page.total} videos, ${counts.tags.size} tags, " +
                "${collections.size} collections, ${movies.total} movies")
            collections.firstOrNull()?.let { client.collectionItems(it.id, limit = 3) }
            movies.movies.firstOrNull()?.let { assertTrue(client.movie(it.id).items.isNotEmpty()) }
        }

        // every sort and a filter are accepted by the server
        for (sort in SortOption.entries) {
            client.videos(ALL_VIDEOS, VideoFilter(sortBy = sort.value, descending = sort.descendingByDefault), limit = 2)
        }
        val counts = client.availableFilters(ALL_VIDEOS, VideoFilter())
        counts.tags.keys.firstOrNull()?.let { tag ->
            val withTag = client.videos(ALL_VIDEOS, VideoFilter().cycleTag(tag), limit = 1).total
            val withoutTag = client.videos(ALL_VIDEOS, VideoFilter().cycleTag(tag).cycleTag(tag), limit = 1).total
            val everything = client.videos(ALL_VIDEOS, VideoFilter(), limit = 1).total
            assertEquals("tag $tag: with + without = all", everything, withTag + withoutTag)
        }
        val short = client.videos(ALL_VIDEOS, VideoFilter().toggleLength(LengthRange.UNDER_2.value), limit = 1).total
        println("under 2 min: $short")
        client.logout()
    }

    private fun allGroups(node: SidebarNode): List<String> =
        if (node.isGroup) listOf(node.name) + node.children.flatMap { allGroups(it) } else emptyList()
}
