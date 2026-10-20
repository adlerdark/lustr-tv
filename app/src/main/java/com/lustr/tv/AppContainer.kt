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

    init {
        prefs.edit { remove(KEY_OLD_COOKIES) }           // logins used to be kept; not any more
    }

    /**
     * The login cookie lives in memory only: every start of the app asks for a new login.
     * (The server address is remembered.)
     */
    val cookieJar = PersistentCookieJar(object : CookieStorage {
        private var value: String? = null
        override fun load(): String? = value
        override fun save(value: String?) {
            this.value = value
        }
    })

    @Volatile
    private var loggedInAt: Long? = null

    /** Call after a successful login: the TV ends it itself after [MAX_LOGIN_MS], whatever the server does. */
    fun markLoggedIn() {
        loggedInAt = System.currentTimeMillis()
    }

    fun forgetLogin() {
        loggedInAt = null
        cookieJar.clear()
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val since = loggedInAt
            if (since != null && System.currentTimeMillis() - since > MAX_LOGIN_MS) {
                forgetLogin()                            // the request then gets a 401 -> login screen
                sessionEnded.value = true
            }
            chain.proceed(chain.request())
        }
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
        if (e is LustrException.NotLoggedIn) {
            loggedInAt = null
            sessionEnded.value = true
        }
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

    companion object {
        /** A TV login lasts at most 4 hours. */
        const val MAX_LOGIN_MS = 4 * 60 * 60 * 1000L
        private const val KEY_SERVER = "server_url"
        private const val KEY_OLD_COOKIES = "cookies"
        private const val KEY_SETTINGS = "tv_settings"
    }
}
