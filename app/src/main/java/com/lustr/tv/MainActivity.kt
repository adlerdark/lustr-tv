package com.lustr.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import com.lustr.tv.ui.LustrNavHost
import com.lustr.tv.ui.theme.LustrTheme

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = (application as LustrApplication).container
        setContent {
            // Privacy screen (Settings): no picture in recent apps, no screenshots / casting
            val settings by container.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.privacyScreen) {
                if (settings.privacyScreen) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            LustrTheme {
                // TV Material text is black unless told otherwise: set the default text colour here.
                // (Not with a tv Surface: it draws through an off-screen layer, which hides the
                // video - the player's SurfaceView shows through a hole in the window.)
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        LustrNavHost(container)
                    }
                }
            }
        }
    }

    /** The player's remote handling gets first look at every key (AppContainer.keyInterceptor). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        container.keyInterceptor?.invoke(event) == true || super.dispatchKeyEvent(event)
}
