package com.lustr.tv.ui.login

import android.os.Build
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.core.LustrException
import com.lustr.tv.ui.components.FormPage
import com.lustr.tv.ui.components.TvTextField
import com.lustr.tv.ui.theme.LustrColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Sign in with a 4-digit code that the owner enters in the web app (docs/SERVER_CHANGES.md §1),
 * or with the password. Nothing here names the app or hints at what it is; the server address
 * where the code goes isn't shown either.
 */
@Composable
fun LoginScreen(container: AppContainer, onLoggedIn: () -> Unit, onChangeServer: () -> Unit) {
    var usePassword by remember { mutableStateOf(false) }
    if (usePassword) {
        PasswordLogin(container, onLoggedIn, onUseCode = { usePassword = false }, onChangeServer = onChangeServer)
    } else {
        CodeLogin(container, onLoggedIn, onUsePassword = { usePassword = true }, onChangeServer = onChangeServer)
    }
}

@Composable
private fun CodeLogin(container: AppContainer, onLoggedIn: () -> Unit, onUsePassword: () -> Unit, onChangeServer: () -> Unit) {
    val loggedIn by rememberUpdatedState(onLoggedIn)
    var code by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var notSupported by remember { mutableStateOf(false) }
    val passwordButton = remember { FocusRequester() }

    // Get a code, poll until it's approved; a new code when it expires. Stops when the screen closes.
    LaunchedEffect(Unit) {
        val client = container.client
        while (isActive) {
            val pairing = try {
                client.pairStart(Build.MODEL ?: "TV")
            } catch (e: LustrException.ServerError) {
                if (e.notSupported) {
                    notSupported = true
                    return@LaunchedEffect
                }
                problem = e.message
                delay(5_000)
                continue
            } catch (e: IOException) {
                problem = "Can't reach the server. Retrying…"
                delay(5_000)
                continue
            }
            code = pairing.code
            problem = null
            val until = System.currentTimeMillis() + pairing.expiresIn * 1000L
            while (isActive && System.currentTimeMillis() < until) {
                delay(pairing.pollEvery.coerceAtLeast(1) * 1000L)
                val status = try {
                    client.pairPoll(pairing.pairId)
                } catch (e: IOException) {
                    problem = "Can't reach the server. Retrying…"
                    continue
                }
                problem = null
                if (status.approved) {
                    container.markLoggedIn()
                    loggedIn()
                    return@LaunchedEffect
                }
                if (status.expired) break                  // -> a new code
            }
        }
    }

    FormPage {
        if (notSupported) {
            Text("Sign in", style = MaterialTheme.typography.headlineMedium)
            Text("This server doesn't support sign-in with a code yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Sign in", style = MaterialTheme.typography.headlineMedium)
            Text(
                code?.toCharArray()?.joinToString("  ") ?: "· · · ·",
                fontSize = 88.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = LustrColors.textBrightest,
            )
            Text("Enter this code in the web app.", style = MaterialTheme.typography.titleMedium)
            Text("The code changes every few minutes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onUsePassword, modifier = Modifier.focusRequester(passwordButton)) { Text("Use password instead") }
            OutlinedButton(onClick = onChangeServer) { Text("Change server") }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    // something must have focus for the remote to work
    LaunchedEffect(notSupported) { runCatching { passwordButton.requestFocus() } }
}

/** Username and password; the server is asked for a TV session (4 hours at most). Nothing is stored. */
@Composable
private fun PasswordLogin(container: AppContainer, onLoggedIn: () -> Unit, onUseCode: () -> Unit, onChangeServer: () -> Unit) {
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
                container.client.login(username.trim(), password, client = "tv")
                password = ""
                container.markLoggedIn()
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
        Text("Sign in", style = MaterialTheme.typography.headlineMedium)
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
            Button(onClick = ::login, enabled = !busy) { Text(if (busy) "Signing in…" else "Sign in") }
            OutlinedButton(onClick = onUseCode) { Text("Use a code instead") }
            OutlinedButton(onClick = onChangeServer) { Text("Change server") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    LaunchedEffect(Unit) { runCatching { userFocus.requestFocus() } }
}
