package com.lustr.tv.ui.login

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.core.LustrClient
import com.lustr.tv.ui.components.FormPage
import com.lustr.tv.ui.components.TvTextField
import kotlinx.coroutines.launch
import java.io.IOException

/** Asks for the server's address and checks that a lustr server answers there. */
@Composable
fun ServerScreen(container: AppContainer, onConnected: (loggedIn: Boolean) -> Unit) {
    var address by remember { mutableStateOf(container.serverUrl?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    fun connect() {
        val url = LustrClient.parseServerUrl(address)
        if (url == null) {
            error = "That doesn't look like an address. Example: 192.168.1.20:8008"
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val client = LustrClient(url, container.http)
                val health = client.health()
                if (health.status != "ok") throw IOException("Not a lustr server")
                container.setServer(url)
                onConnected(client.authStatus().authenticated)
            } catch (e: IOException) {
                error = "No lustr server at $url (${e.message ?: e.javaClass.simpleName})"
            } finally {
                busy = false
            }
        }
    }

    FormPage {
        Text("Connect to lustr", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The address you open lustr at in a browser, e.g. 192.168.1.20:8008",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TvTextField(
            value = address,
            onValueChange = { address = it },
            label = "Server address",
            modifier = Modifier.focusRequester(focus),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { connect() }),
        )
        Button(onClick = ::connect, enabled = !busy) { Text(if (busy) "Connecting…" else "Connect") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
