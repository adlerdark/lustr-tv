package com.lustr.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lustr.tv.ui.LustrNavHost
import com.lustr.tv.ui.theme.LustrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LustrApplication).container
        setContent {
            LustrTheme {
                LustrNavHost(container)
            }
        }
    }
}
