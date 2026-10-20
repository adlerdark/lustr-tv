package com.lustr.tv.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Cast browser / bio, video info, file info, favorites, movie filters. Fixtures from lustr 0.8.1. */
class CastAndInfoTest {
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
    private fun reply(body: String) = server.enqueue(MockResponse().setBody(body))

    @Test
    fun castListAndBio() = runTest {
        reply(fixture("cast_all"))
        val jane = client.cast().single()
        assertEquals("Jane Doe", jane.name)
        assertEquals(2, jane.count)
        assertTrue(jane.isFavorite && jane.hasPhoto)
        assertEquals("Female", genderLabel(jane.gender))

        reply(fixture("cast_bio"))
        val bio = client.castBio("Jane Doe")
        assertEquals(2, bio.videos.size)
        assertEquals(mapOf("LibA" to 2), bio.libraryDistribution)
        assertEquals(2, bio.stats.totalVideos)
        assertEquals("Pro", categoryLabel(bio.category))
        server.takeRequest()
        assertEquals("/api/cast/Jane%20Doe/bio", server.takeRequest().requestUrl!!.encodedPath)   // name in the path, encoded
    }

    @Test
    fun appearanceAndPhotoUrl() {
        val bio = LustrClient.json.decodeFromString(CastBio.serializer(), """{"name":"A","appearance":{"hair":["Blonde","Brown"]}}""")
        assertEquals(mapOf("hair" to listOf("Blonde", "Brown")), bio.appearance)
        assertEquals("http://${server.hostName}:${server.port}/api/cast/photo/Jane%20O'Doe%2FX?thumbnail=true", client.castPhotoUrl("Jane O'Doe/X"))
    }

    @Test
    fun castFavorite() = runTest {
        reply("""{"success":true}"""); reply("""{"success":true}""")
        client.setCastFavorite("Jane Doe", true)
        client.setCastFavorite("Jane Doe", false)
        assertEquals("POST", server.takeRequest().method)
        val off = server.takeRequest()
        assertEquals("DELETE", off.method)
        assertEquals("/api/cast/favorites/Jane%20Doe", off.requestUrl!!.encodedPath)
    }

    @Test
    fun browseFilterAndSort() {
        val cast = listOf(
            CastMember("Zoe", count = 3, gender = "female", isFavorite = true),
            CastMember("adam", count = 9, gender = "male"),
            CastMember("Bea", count = 3, gender = null, aliases = listOf("Beatrix")),   // no gender = female (web default)
            CastMember("Tia", count = 1, gender = "trans_female"),
        )
        assertEquals(listOf("adam", "Bea", "Zoe", "Tia"), CastBrowse.filter(cast).map { it.name })        // most videos, then name
        assertEquals(listOf("adam", "Bea", "Tia", "Zoe"), CastBrowse.filter(cast, sort = CastSort.NAME).map { it.name })
        assertEquals(listOf("Bea", "Zoe"), CastBrowse.filter(cast, gender = CastGenderFilter.FEMALE).map { it.name })
        assertEquals(listOf("Tia"), CastBrowse.filter(cast, gender = CastGenderFilter.TRANS).map { it.name })
        assertEquals(listOf("Zoe"), CastBrowse.filter(cast, favoritesOnly = true).map { it.name })
        assertEquals(listOf("Bea"), CastBrowse.filter(cast, query = "trix").map { it.name })               // aliases count
    }

    @Test
    fun externalProfile() {
        // the shape ext_db.row_to_public gives (see the web app's extDbRows)
        val link = LustrClient.json.parseToJsonElement(
            """{"ext_name":"Jane Doe","ext_disambiguation":"US","ext_gender":"FEMALE","ext_eye_color":"BLUE_GREEN",
               "ext_scene_count":42,"ext_tattoos":[{"location":"Back","description":"Wings"},{"location":"Wrist"}],
               "display":{"aliases":["J. Doe","Janie"],"birthdate":"1995-04-01","age":30,"years_active":{"text":"2015 - now"},
                          "measurements":"34B-24-34","height":"170 cm","nationality":""}}""",
        ) as JsonObject
        assertEquals(
            listOf(
                "Name" to "Jane Doe (US)", "Aliases" to "J. Doe, Janie", "Gender" to "Female", "Birthday" to "1995-04-01 (age 30)",
                "Years active" to "2015 - now", "Height" to "170 cm", "Measurements" to "34B-24-34", "Eye color" to "Blue green",
                "Tattoos" to "Back (Wings); Wrist", "Scenes" to "42",
            ),
            ExtProfile.rows(link),
        )
        assertTrue(ExtProfile.rows(null).isEmpty())
    }

    @Test
    fun unlinkedProfileIsEmpty() = runTest {
        reply("""{"success":true,"cast_name":"Jane Doe","linked":false,"link":null}""")
        assertTrue(client.castProfile("Jane Doe").isEmpty())
        assertEquals("Jane Doe", server.takeRequest().requestUrl!!.queryParameter("name"))
    }

    @Test
    fun videoInfoAndFileInfo() = runTest {
        reply(fixture("video_full"))
        val v = client.video("/media/LibA/Big Movie - Scene 1.mp4")
        assertEquals(listOf("Site" to listOf("StudioX"), "Tags" to listOf("Outdoor")), v.metadata)
        assertEquals("Big Movie", v.movie)
        assertEquals("0.29 MB", v.fileSize)
        assertEquals("122 kbps", v.totalBitrate)
        assertTrue(v.dateAdded!!.startsWith("20"))
        assertTrue(v.versions.isEmpty())                       // one file: no versions list

        val two = LustrClient.json.decodeFromString(
            Video.serializer(),
            """{"path":"/a.mp4","versions":[{"path":"/a.mp4","filename":"a.mp4","is_preferred":1,"codec":"h264","file_size":"1 GB",
               "length":"00:30:00","total_bitrate":"4 Mbps","cache_id":"x","resolution":"1080p","extension":"mp4","video_id":"v"},
               {"path":"/b.mkv","filename":"b.mkv","is_preferred":0}]}""",
        )
        assertEquals(2, two.versions.size)
        assertEquals(1, two.versions[0].isPreferred)
        assertEquals("1 GB", two.versions[0].fileSize)
    }

    @Test
    fun toggleFavorite() = runTest {
        reply("""{"favorite":true}"""); reply("""{"favorite":0}""")
        assertTrue(client.toggleFavorite("/media/a b.mp4"))
        assertFalse(client.toggleFavorite("/media/a b.mp4"))
        assertEquals("""{"path":"/media/a b.mp4"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun movieFiltersAndSort() = runTest {
        reply(fixture("movies")); reply(fixture("movie_filters"))
        val f = VideoFilter(query = "big").cycleTag("outdoor").cycleSite("x").cycleSite("x").copy(favoritesOnly = true)
        client.movies("Studios", f, MovieSort.YEAR)
        val counts = client.movieFilters("Studios", f)
        assertEquals(mapOf("outdoor" to 1), counts.tags)
        val url = server.takeRequest().requestUrl!!
        assertEquals("big", url.queryParameter("q"))
        assertEquals("outdoor", url.queryParameter("tags"))
        assertEquals("x", url.queryParameter("sites_exclude"))
        assertEquals("true", url.queryParameter("favorites"))
        assertEquals("year", url.queryParameter("sort"))
        assertEquals("/api/movies/filters", server.takeRequest().requestUrl!!.encodedPath)
    }
}
