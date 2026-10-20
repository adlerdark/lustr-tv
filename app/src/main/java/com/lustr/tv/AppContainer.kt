package com.lustr.tv

import android.content.Context
import androidx.core.content.edit
import com.lustr.tv.core.CookieStorage
import com.lustr.tv.core.LustrClient
import com.lustr.tv.core.LustrException
import com.lustr.tv.core.PersistentCookieJar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A screen's data: loading, there, or failed with a message. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * The app's shared objects. One OkHttpClient (with the login cookie) serves the API, the
 * thumbnails (Coil) and the video player (ExoPlayer): lustr refuses all of them without the cookie.
 */
class AppContainer(context: Context) {
    private val prefs = context.getSharedPreferences("lustr", Context.MODE_PRIVATE)

    val cookieJar = PersistentCookieJar(object : CookieStorage {
        override fun load(): String? = prefs.getString(KEY_COOKIES, null)
        override fun save(value: String?) = prefs.edit { putString(KEY_COOKIES, value) }
    })

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)      // the first HLS segment waits for the transcoder
        .build()

    /** For work that must finish even when a screen closes (the last progress report). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set when the server says the login has ended; the navigation then goes to the login screen. */
    val sessionEnded = MutableStateFlow(false)

    /** Every failed request passes through here, so a 401 anywhere leads back to login. */
    fun onError(e: IOException) {
        if (e is LustrException.NotLoggedIn) sessionEnded.value = true
    }

    /** Run one request: Ready with the result, or Failed with a message to show. */
    suspend fun <T> load(block: suspend LustrClient.() -> T): Load<T> = try {
        Load.Ready(client.block())
    } catch (e: IOException) {
        onError(e)
        Load.Failed(e.message ?: "Can't reach the server")
    }

    var serverUrl: HttpUrl? = prefs.getString(KEY_SERVER, null)?.toHttpUrlOrNull()
        private set

    /** The client for the chosen server. Only call once a server is set. */
    val client: LustrClient
        get() = LustrClient(checkNotNull(serverUrl) { "No server set" }, http)

    fun setServer(url: HttpUrl) {
        if (url != serverUrl) cookieJar.clear()          // a login belongs to one server
        serverUrl = url
        prefs.edit { putString(KEY_SERVER, url.toString()) }
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_COOKIES = "cookies"
    }
}
