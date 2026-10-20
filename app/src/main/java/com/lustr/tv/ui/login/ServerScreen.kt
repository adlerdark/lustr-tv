package com.lustr.tv.ui.login

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.core.LustrClient
import com.lustr.tv.core.ServerScanner
import com.lustr.tv.ui.components.FormPage
import com.lustr.tv.ui.components.TvTextField
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.io.IOException
import java.net.Inet4Address

/**
 * Asks for the server's address (remembered for next time), or finds it on the network.
 * Worded neutrally: this screen is seen before login.
 */
@Composable
fun ServerScreen(container: AppContainer, onConnected: () -> Unit) {
    val context = LocalContext.current
    var address by remember { mutableStateOf(container.serverUrl?.let { "${it.host}:${it.port}" }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<HttpUrl>?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    fun connect(url: HttpUrl?) {
        if (url == null) {
            error = "That doesn't look like an address. Example: 192.168.1.20:8008"
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val health = LustrClient(url, container.http).health()
                if (health.status != "ok") throw IOException("unexpected answer")
                container.setServer(url)
                onConnected()
            } catch (e: IOException) {
                error = "No server found at $url (${e.message ?: e.javaClass.simpleName})"
            } finally {
                busy = false
            }
        }
    }

    fun scan() {
        val ip = localIpv4(context)
        if (ip == null) {
            error = "Not connected to a network."
            return
        }
        scanning = true
        error = null
        found = null
        scope.launch {
            found = ServerScanner.scan(ServerScanner.hostsAround(ip), container.http)
            scanning = false
            if (found.isNullOrEmpty()) error = "Nothing found on port ${LustrClient.DEFAULT_PORT}. Type the address instead."
        }
    }

    FormPage {
        Text("Connect to server", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The server's address on your network, e.g. 192.168.1.20:8008",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TvTextField(
            value = address,
            onValueChange = { address = it },
            label = "Server address",
            modifier = Modifier.focusRequester(focus),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { connect(LustrClient.parseServerUrl(address)) }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { connect(LustrClient.parseServerUrl(address)) }, enabled = !busy) {
                Text(if (busy) "Connecting…" else "Connect")
            }
            OutlinedButton(onClick = ::scan, enabled = !scanning) { Text(if (scanning) "Searching…" else "Search network") }
        }
        found?.takeIf { it.isNotEmpty() }?.let { servers ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Found:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (url in servers) {
                    OutlinedButton(onClick = { address = "${url.host}:${url.port}"; connect(url) }) { Text("${url.host}:${url.port}") }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The TV's own IPv4 address on the current network (to know which addresses to scan). */
private fun localIpv4(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses.map { it.address }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress
}
