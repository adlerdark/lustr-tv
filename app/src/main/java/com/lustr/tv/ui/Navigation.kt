package com.lustr.tv.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lustr.tv.AppContainer
import com.lustr.tv.ui.browse.BrowseScreen
import com.lustr.tv.ui.login.LoginScreen
import com.lustr.tv.ui.login.ServerScreen
import com.lustr.tv.ui.player.PlayerScreen

private object Routes {
    const val SERVER = "server"
    const val LOGIN = "login"
    const val BROWSE = "browse"
    const val PLAYER = "player?path={path}"
    fun player(path: String) = "player?path=${Uri.encode(path)}"
}

/** Server -> Login -> Browse (sidebar + tabs) -> Player. Any screen goes back to Login when the session ends. */
@Composable
fun LustrNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val start = if (container.serverUrl == null) Routes.SERVER else Routes.BROWSE

    val sessionEnded by container.sessionEnded.collectAsStateWithLifecycle()
    LaunchedEffect(sessionEnded) {
        if (sessionEnded) {
            container.sessionEnded.value = false
            nav.resetTo(Routes.LOGIN)
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.SERVER) {
            ServerScreen(container, onConnected = { loggedIn -> nav.resetTo(if (loggedIn) Routes.BROWSE else Routes.LOGIN) })
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                container,
                onLoggedIn = { nav.resetTo(Routes.BROWSE) },
                onChangeServer = { nav.resetTo(Routes.SERVER) },
            )
        }
        composable(Routes.BROWSE) {
            BrowseScreen(
                container,
                onPlay = { video -> nav.navigate(Routes.player(video.path)) },
                onLoggedOut = { nav.resetTo(Routes.LOGIN) },
            )
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            PlayerScreen(
                container,
                path = entry.arguments?.getString("path").orEmpty(),
                onNeedLogin = { nav.resetTo(Routes.LOGIN) },
                onExit = { nav.popBackStack() },
            )
        }
    }
}

/** Go to [route] and forget everything before it (Back then leaves the app). */
private fun NavHostController.resetTo(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
