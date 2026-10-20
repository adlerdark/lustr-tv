package com.lustr.tv.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Where the cookie jar keeps its cookies between app starts (SharedPreferences on Android). */
interface CookieStorage {
    fun load(): String?
    fun save(value: String?)
}

/**
 * Keeps lustr's login cookie, also across app restarts. The server still decides how long a
 * login lasts: it ends after the inactivity timeout (Settings -> General, 15 min by default)
 * and after 30 days at most, whatever the cookie says.
 */
class PersistentCookieJar(private val storage: CookieStorage) : CookieJar {
    private val cookies: MutableList<Cookie> by lazy { restore() }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies += cookie   // an expired one = deleted (logout)
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        if (cookies.removeAll { it.expiresAt <= now }) persist()
        return cookies.filter { it.matches(url) }
    }

    /** Forget the login (e.g. when switching servers). */
    @Synchronized
    fun clear() {
        cookies.clear()
        persist()
    }

    @Synchronized
    fun hasCookiesFor(url: HttpUrl): Boolean = loadForRequest(url).isNotEmpty()

    private fun persist() {
        storage.save(LustrClient.json.encodeToString(ListSerializer(Stored.serializer()), cookies.map(Stored::from)))
    }

    private fun restore(): MutableList<Cookie> = try {
        val text = storage.load() ?: return mutableListOf()
        LustrClient.json.decodeFromString(ListSerializer(Stored.serializer()), text).map { it.toCookie() }.toMutableList()
    } catch (e: Exception) {
        mutableListOf()
    }

    @Serializable
    private data class Stored(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    ) {
        fun toCookie(): Cookie = Cookie.Builder().name(name).value(value).path(path).expiresAt(expiresAt).apply {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            if (secure) secure()
            if (httpOnly) httpOnly()
        }.build()

        companion object {
            fun from(c: Cookie) = Stored(c.name, c.value, c.domain, c.path, c.expiresAt, c.secure, c.httpOnly, c.hostOnly)
        }
    }
}
