package com.lustr.tv.ui.login

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.core.LustrException
import com.lustr.tv.ui.components.FormPage
import com.lustr.tv.ui.components.TvTextField
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The owner's username and password (lustr has one account). The password is never stored:
 * the server's login cookie is kept instead, and the server ends it after the inactivity timeout.
 */
@Composable
fun LoginScreen(container: AppContainer, onLoggedIn: () -> Unit, onChangeServer: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    fun login() {
        if (username.isBlank() || password.isEmpty()) {
            error = "Enter your username and password."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                container.client.login(username.trim(), password)
                password = ""
                onLoggedIn()
            } catch (e: LustrException.NotLoggedIn) {
                error = e.message                       // "Invalid username or password"
            } catch (e: LustrException.TooManyAttempts) {
                error = e.message                       // locked out for a few minutes
            } catch (e: IOException) {
                error = "Can't reach the server (${e.message ?: e.javaClass.simpleName})"
            } finally {
                busy = false
            }
        }
    }

    FormPage {
        Text("Log in", style = MaterialTheme.typography.headlineMedium)
        Text(
            "${container.serverUrl} · No account yet? Create it in lustr in a browser first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TvTextField(
            value = username,
            onValueChange = { username = it },
            label = "Username",
            modifier = Modifier.focusRequester(userFocus),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
        )
        TvTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            modifier = Modifier.focusRequester(passwordFocus),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { login() }),
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = ::login, enabled = !busy) { Text(if (busy) "Logging in…" else "Log in") }
            OutlinedButton(onClick = onChangeServer) { Text("Change server") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    LaunchedEffect(Unit) { runCatching { userFocus.requestFocus() } }
}
