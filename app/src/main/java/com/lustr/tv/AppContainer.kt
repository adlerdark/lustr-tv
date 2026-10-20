package com.lustr.tv

import android.content.Context
import android.view.KeyEvent
import androidx.core.content.edit
import com.lustr.tv.core.CookieStorage
import com.lustr.tv.core.LustrClient
import com.lustr.tv.core.LustrException
import com.lustr.tv.core.PersistentCookieJar
import com.lustr.tv.core.TvSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * The login cookie is kept across app starts, so the TV stays linked until the server ends
     * the session (its own limit for TV sessions, the inactivity timeout, or a log out).
     */
    val cookieJar = PersistentCookieJar(object : CookieStorage {
        override fun load(): String? = prefs.getString(KEY_COOKIES, null)
        override fun save(value: String?) = prefs.edit { putString(KEY_COOKIES, value) }
    })

    /** Log out on this TV (also when changing servers). */
    fun forgetLogin() {
        cookieJar.clear()
    }

    /** True when a login cookie for the chosen server is kept (it may still have ended on the server). */
    val hasLogin: Boolean
        get() = serverUrl?.let { cookieJar.hasCookiesFor(it) } == true

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)      // the first HLS segment waits for the transcoder
        .build()

    /** For work that must finish even when a screen closes (the last progress report). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set when the server says the login has ended; the navigation then goes to the login screen. */
    val sessionEnded = MutableStateFlow(false)

    /** Bumped when something changes what every list shows (e.g. the orientation filter): lists reload. */
    val dataVersion = MutableStateFlow(0)

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
        if (url != serverUrl) forgetLogin()              // a login belongs to one server
        serverUrl = url
        prefs.edit { putString(KEY_SERVER, url.toString()) }
    }

    private val _settings = MutableStateFlow(TvSettings.decode(prefs.getString(KEY_SETTINGS, null)))

    /** The TV's own settings (Settings screen). */
    val settings: StateFlow<TvSettings> = _settings

    fun updateSettings(change: (TvSettings) -> TvSettings) {
        val s = change(_settings.value)
        _settings.value = s
        prefs.edit { putString(KEY_SETTINGS, TvSettings.encode(s)) }
    }

    /**
     * Sees every key from the remote before the screens do (MainActivity.dispatchKeyEvent);
     * returns true to keep it. The player uses it for left / right seeking.
     */
    @Volatile
    var keyInterceptor: ((KeyEvent) -> Boolean)? = null

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_COOKIES = "cookies"
        const val KEY_SETTINGS = "tv_settings"
    }
}
