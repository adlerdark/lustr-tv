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
import com.lustr.tv.FOCUS_CONTENT
import com.lustr.tv.core.Video
import com.lustr.tv.ui.browse.BrowseScreen
import com.lustr.tv.ui.browse.VideoActionsHost
import com.lustr.tv.ui.info.CastScreen
import com.lustr.tv.ui.info.VideoInfoScreen
import com.lustr.tv.ui.login.LoginScreen
import com.lustr.tv.ui.login.ServerScreen
import com.lustr.tv.ui.player.PlayerScreen
import com.lustr.tv.ui.settings.SettingsScreen

private object Routes {
    const val SERVER = "server"
    const val LOGIN = "login"
    const val BROWSE = "browse"
    const val SETTINGS = "settings"
    const val PLAYER = "player?path={path}"
    const val VIDEO_INFO = "video?path={path}"
    const val CAST = "cast?name={name}"
    fun player(path: String) = "player?path=${Uri.encode(path)}"
    fun videoInfo(path: String) = "video?path=${Uri.encode(path)}"
    fun cast(name: String) = "cast?name=${Uri.encode(name)}"
}

/**
 * Server -> Login -> Browse (sidebar + tabs) -> Player / Settings.
 * The server address and the login are remembered; any screen goes back to Login when the
 * server ends the session.
 */
@Composable
fun LustrNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val start = when {
        container.serverUrl == null -> Routes.SERVER
        container.hasLogin -> Routes.BROWSE
        else -> Routes.LOGIN
    }

    val sessionEnded by container.sessionEnded.collectAsStateWithLifecycle()
    LaunchedEffect(sessionEnded) {
        if (sessionEnded) {
            container.sessionEnded.value = false
            nav.resetTo(Routes.LOGIN)
        }
    }

    // coming back, the focus returns to the video's card (or at least the content, not the sidebar)
    val play = { video: Video -> container.returnFocusTo = video.path; nav.navigate(Routes.player(video.path)) }
    val openCast = { name: String -> container.returnFocusTo = container.returnFocusTo ?: FOCUS_CONTENT; nav.navigate(Routes.cast(name)) }
    val openInfo = { video: Video -> container.returnFocusTo = video.path; nav.navigate(Routes.videoInfo(video.path)) }

    // a long press on any video card opens the video menu (VideoActions)
    VideoActionsHost(container, onPlay = play, onVideoInfo = openInfo) {
        NavHost(navController = nav, startDestination = start) {
            composable(Routes.SERVER) {
                ServerScreen(container, onConnected = { nav.resetTo(Routes.LOGIN) })
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
                    onPlay = play,
                    onCast = openCast,
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onLoggedOut = { nav.resetTo(Routes.LOGIN) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container,
                    onBack = { nav.popBackStack() },
                    onChangeServer = { container.forgetLogin(); nav.resetTo(Routes.SERVER) },
                    onLoggedOut = { nav.resetTo(Routes.LOGIN) },
                )
            }
            composable(
                Routes.VIDEO_INFO,
                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                VideoInfoScreen(container, path = entry.arguments?.getString("path").orEmpty(), onPlay = play, onCast = openCast)
            }
            composable(
                Routes.CAST,
                arguments = listOf(navArgument("name") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                CastScreen(container, name = entry.arguments?.getString("name").orEmpty(), onPlay = play)
            }
            composable(
                Routes.PLAYER,
                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                PlayerScreen(
                    container,
                    path = entry.arguments?.getString("path").orEmpty(),
                    onExit = { nav.popBackStack() },
                )
            }
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
