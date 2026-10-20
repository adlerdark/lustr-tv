package com.lustr.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
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
                // The Surface sets the default text colour: without it, TV Material text is black
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                ) {
                    LustrNavHost(container)
                }
            }
        }
    }

    /** The player's remote handling gets first look at every key (AppContainer.keyInterceptor). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        container.keyInterceptor?.invoke(event) == true || super.dispatchKeyEvent(event)
}
