package com.lustr.tv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** A problem the server reported. Plain network failures stay ordinary IOExceptions. */
sealed class LustrException(message: String) : IOException(message) {
    /** 401: not logged in, or the session ran out (inactivity timeout / logged out elsewhere). */
    class NotLoggedIn(message: String) : LustrException(message)

    /** 429: too many wrong passwords from this device. */
    class TooManyAttempts(message: String, val retryAfterSeconds: Int?) : LustrException(message)

    /** Any other error status, or a {"success": false} reply. */
    class ServerError(message: String, val code: Int) : LustrException(message) {
        /** The server doesn't have this endpoint (an older lustr). */
        val notSupported: Boolean get() = code == 404 || code == 405
    }
}

/**
 * Talks to one lustr server. Login is a cookie, so [http] must have a cookie jar
 * (see [PersistentCookieJar]); the same client is used for video and image requests,
 * which need that cookie too.
 */
class LustrClient(val baseUrl: HttpUrl, private val http: OkHttpClient) {

    /** Absolute URL for one of the server's relative links (thumb, src, ...). */
    fun absoluteUrl(relative: String): String =
        baseUrl.resolve(relative)?.toString() ?: (baseUrl.toString().trimEnd('/') + relative)

    suspend fun health(): Health = get(url("api/health"))

    suspend fun authStatus(): AuthStatus = get(url("api/check_auth"))

    /** Logs in; the session cookie lands in the cookie jar. [client] = "tv" asks for a 4-hour TV session. */
    suspend fun login(username: String, password: String, client: String? = null) {
        post<JsonObject>(url("api/login"), json.encodeToString(LoginBody.serializer(), LoginBody(username, password, client)))
    }

    /**
     * Start a code login: show [Pairing.code] and poll with [pairPoll] until it's approved in the web app.
     * A server without TV login answers 404 ([LustrException.ServerError.notSupported]).
     */
    suspend fun pairStart(deviceName: String): Pairing =
        post(url("api/tv/pair/start"), json.encodeToString(PairStartBody.serializer(), PairStartBody(deviceName)))

    /** "approved" also sets the login cookie. */
    suspend fun pairPoll(pairId: String): PairStatus =
        post(url("api/tv/pair/poll"), json.encodeToString(PairPollBody.serializer(), PairPollBody(pairId)))

    /** The server's display filter for this account: the orientation types shown, out of all of them. */
    suspend fun displayFilter(): DisplayFilter = get(url("api/settings/display-filter"))

    suspend fun setDisplayFilter(types: List<String>) {
        post<JsonObject>(url("api/settings/display-filter"), json.encodeToString(DisplayFilterBody.serializer(), DisplayFilterBody(types)))
    }

    suspend fun logout() {
        post<JsonObject>(url("api/logout"), "{}")
    }

    suspend fun init(): InitData = get(url("api/init"))

    /** Home rows (Continue Watching, Recently Added, ...), for everything or one library / group. */
    suspend fun home(library: String? = null): List<HomeRow> {
        val reply: HomeResponse = get(url("api/home") { library?.let { addQueryParameter("library", it) } })
        if (!reply.success) throw LustrException.ServerError(reply.error ?: "Home rows not available", 200)
        return reply.rows
    }

    /**
     * One page of a library or group, filtered and sorted like the web app's Library tab.
     * [seed] keeps a Random sort in the same order from page to page.
     */
    suspend fun videos(
        library: String = ALL_VIDEOS,
        filter: VideoFilter = VideoFilter(),
        page: Int = 1,
        limit: Int = 50,
        seed: Int = 0,
    ): VideoPage = get(url("api/videos") {
        addQueryParameter("library", library)
        addFilter(filter)
        addQueryParameter("sort_by", filter.sortBy)
        addQueryParameter("desc", filter.descending.toString())
        addQueryParameter("page", page.toString())
        addQueryParameter("limit", limit.toString())
        addQueryParameter("seed", seed.toString())
    })

    /** Tags and sites (with counts) among the videos the other filters leave. */
    suspend fun availableFilters(library: String, filter: VideoFilter): FilterCounts =
        get(url("api/available_filters") {
            addQueryParameter("library", library)
            addFilter(filter)
        })

    /** Collections shown on a library's / group's Collections tab. */
    suspend fun collections(library: String): List<VideoCollection> {
        val reply: CollectionList = get(url("api/collections") { addQueryParameter("library", library) })
        if (!reply.success) throw LustrException.ServerError(reply.error ?: "Collections not available", 200)
        return reply.collections
    }

    suspend fun collectionItems(id: Int, page: Int = 1, limit: Int = 60): CollectionItems {
        val reply: CollectionItems = get(url("api/collections/$id/items") {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", limit.toString())
        })
        if (!reply.success) throw LustrException.ServerError(reply.error ?: "Can't open this collection", 200)
        return reply
    }

    /** Movies in a library / group (by title), optionally searched. */
    suspend fun movies(library: String, query: String = "", page: Int = 1, limit: Int = 60): MovieList {
        val reply: MovieList = get(url("api/movies") {
            addQueryParameter("library", library)
            addQueryParameter("q", query)
            addQueryParameter("sort", "title")
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", limit.toString())
        })
        if (!reply.success) throw LustrException.ServerError(reply.error ?: "Movies not available", 200)
        return reply
    }

    /** One movie with its scenes in order. */
    suspend fun movie(id: Int): MovieDetail {
        val reply: MovieDetail = get(url("api/movies/$id"))
        if (!reply.success) throw LustrException.ServerError(reply.error ?: "Can't open this movie", 200)
        return reply
    }

    /** One video, with its current resume point. */
    suspend fun video(path: String): Video = get(url("api/video") { addQueryParameter("path", path) })

    /** The ways a file can be played: direct (original file) and HLS transcodes. */
    suspend fun streams(path: String): StreamInfo {
        val info: StreamInfo = get(url("api/player/streams") { addQueryParameter("path", path) })
        if (!info.success) throw LustrException.ServerError(info.error ?: "Can't play this file", 200)
        return info
    }

    /** Saves the resume point and Play History entry. Also counts as activity for the login session. */
    suspend fun reportProgress(report: ProgressReport) {
        post<JsonObject>(url("api/watch/progress"), json.encodeToString(ProgressReport.serializer(), report))
    }

    // -----------------------------------------------------------------------------------------

    private fun url(path: String, build: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        baseUrl.newBuilder().addPathSegments(path).apply(build).build()

    private fun HttpUrl.Builder.addFilter(filter: VideoFilter) {
        addQueryParameter("query", filter.query)
        filter.include(filter.tags)?.let { addQueryParameter("tags", it) }
        filter.exclude(filter.tags)?.let { addQueryParameter("tags_exclude", it) }
        filter.include(filter.sites)?.let { addQueryParameter("sites", it) }
        filter.exclude(filter.sites)?.let { addQueryParameter("sites_exclude", it) }
        if (filter.favoritesOnly) addQueryParameter("favorites_only", "true")
        filter.lengthParam?.let { addQueryParameter("length_filter", it) }
    }

    private suspend inline fun <reified T> get(url: HttpUrl): T =
        call(Request.Builder().url(url).get().build())

    private suspend inline fun <reified T> post(url: HttpUrl, body: String): T =
        call(Request.Builder().url(url).post(body.toRequestBody(JSON_TYPE)).build())

    private suspend inline fun <reified T> call(request: Request): T {
        val text = execute(request)
        return json.decodeFromString(text)
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = errorDetail(text) ?: "HTTP ${response.code}"
                throw when (response.code) {
                    401 -> LustrException.NotLoggedIn(detail)
                    429 -> LustrException.TooManyAttempts(detail, response.header("Retry-After")?.toIntOrNull())
                    else -> LustrException.ServerError(detail, response.code)
                }
            }
            text
        }
    }

    /** FastAPI errors look like {"detail": "..."}. */
    private fun errorDetail(text: String): String? = try {
        (json.parseToJsonElement(text) as? JsonObject)?.get("detail")?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT_PORT = 8008
        private val JSON_TYPE = "application/json".toMediaType()

        internal val json = Json {
            ignoreUnknownKeys = true
            isLenient = true             // e.g. a movie year sent as a number where a string is expected
            coerceInputValues = true     // null where a default exists -> the default
            explicitNulls = false
            encodeDefaults = true        // always send every field (e.g. log_only: false)
        }

        /**
         * What the owner typed -> the server's base URL, or null if it can't be one.
         * "192.168.1.20" -> http://192.168.1.20:8008/ ; a full URL (e.g. https://lustr.example.com) is kept.
         */
        fun parseServerUrl(input: String): HttpUrl? {
            val text = input.trim().trimEnd('/')
            if (text.isEmpty()) return null
            val hasScheme = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
            val url = (if (hasScheme) text else "http://$text").toHttpUrlOrNull() ?: return null
            val withPort = if (!hasScheme && !text.substringAfter(']').contains(':')) {
                url.newBuilder().port(DEFAULT_PORT).build()
            } else {
                url
            }
            // keep a path prefix (reverse proxy under /lustr), always ending in "/"
            val path = withPort.encodedPath.trimEnd('/') + "/"
            return withPort.newBuilder().encodedPath(path).query(null).fragment(null).build()
        }
    }
}
