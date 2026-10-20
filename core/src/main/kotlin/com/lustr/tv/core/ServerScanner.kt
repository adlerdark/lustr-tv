package com.lustr.tv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Finds lustr servers on the home network: every address of the TV's /24, port 8008. */
object ServerScanner {
    /** The 254 addresses of [ip]'s /24, e.g. 192.168.1.30 -> 192.168.1.1 .. 192.168.1.254 (its own included). */
    fun hostsAround(ip: String): List<String> {
        val parts = ip.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return emptyList()
        val prefix = parts.take(3).joinToString(".")
        return (1..254).map { "$prefix.$it" }
    }

    /**
     * The hosts answering like a lustr server: GET /api/health -> {"status": "ok"} and
     * GET /api/check_auth -> {"authenticated": ...}. Returns their base URLs, in host order.
     */
    suspend fun scan(
        hosts: List<String>,
        http: OkHttpClient,
        port: Int = LustrClient.DEFAULT_PORT,
        parallel: Int = 48,
    ): List<HttpUrl> = coroutineScope {
        val quick = http.newBuilder()
            .connectTimeout(600, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .build()
        val gate = Semaphore(parallel)
        hosts.map { host ->
            async {
                gate.withPermit {
                    val base = "http://$host:$port/".toHttpUrlOrNull() ?: return@withPermit null
                    base.takeIf { isLustr(quick, it) }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun isLustr(http: OkHttpClient, base: HttpUrl): Boolean = withContext(Dispatchers.IO) {
        fun field(path: String, name: String): String? = try {
            http.newCall(Request.Builder().url(base.resolve(path)!!).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val obj = LustrClient.json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject ?: return null
                obj[name]?.jsonPrimitive?.contentOrNull
            }
        } catch (e: Exception) {
            null
        }
        field("api/health", "status") == "ok" && field("api/check_auth", "authenticated") != null
    }
}
