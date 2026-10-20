package com.lustr.tv

import android.content.Context
import androidx.core.content.edit
import com.lustr.tv.core.CookieStorage
import com.lustr.tv.core.LustrClient
import com.lustr.tv.core.PersistentCookieJar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
