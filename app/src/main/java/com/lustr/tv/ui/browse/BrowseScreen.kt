package com.lustr.tv.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.Load
import com.lustr.tv.core.HOME
import com.lustr.tv.core.LibraryTab
import com.lustr.tv.core.SEARCH
import com.lustr.tv.core.Video
import com.lustr.tv.ui.theme.LustrColors
import kotlinx.coroutines.delay

/** Sidebar on the left; on the right Home, or the open library / group with its tabs. */
@Composable
fun BrowseScreen(
    container: AppContainer,
    onPlay: (Video) -> Unit,
    onCast: (String) -> Unit,
    onSettings: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val vm: BrowseViewModel = viewModel { BrowseViewModel(container) }
    val tree by vm.tree.collectAsStateWithLifecycle()
    val sidebar by vm.sidebar.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val homeTab by vm.homeTab.collectAsStateWithLifecycle()
    val query by vm.search.collectAsStateWithLifecycle()
    val client = remember { container.client }
    val activeFocus = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }

    // The sidebar tucks itself away while you're in the content, and comes back when you go left
    var sidebarOpen by remember { mutableStateOf(true) }
    var focusSidebar by remember { mutableStateOf(true) }
    LaunchedEffect(contentHasFocus) { if (contentHasFocus) sidebarOpen = false }
    fun showSidebar() {
        sidebarOpen = true
        focusSidebar = true
    }
    // focus the open item once the sidebar (and its list) is there again; it may take a frame or two
    LaunchedEffect(sidebarOpen, focusSidebar, tree is Load.Ready) {
        if (!sidebarOpen || !focusSidebar || tree !is Load.Ready) return@LaunchedEffect
        repeat(20) {
            if (runCatching { activeFocus.requestFocus() }.isSuccess) {
                focusSidebar = false
                return@LaunchedEffect
            }
            delay(25)
        }
        focusSidebar = false
    }

    // Back in the content: return to the sidebar first (opened collections / movies close before this)
    BackHandler(enabled = contentHasFocus) { showSidebar() }

    // The sidebar follows the orientation filter (changed in Settings)
    val dataVersion by container.dataVersion.collectAsStateWithLifecycle()
    LaunchedEffect(dataVersion) { if (dataVersion > 0) vm.load() }

    Row(Modifier.fillMaxSize()) {
        if (sidebarOpen) {
            Sidebar(
                tree = tree,
                state = sidebar,
                onClick = vm::click,
                onRetry = vm::load,
                onLogOut = { vm.logOut(onLoggedOut) },
                onSettings = onSettings,
                onSearch = { searching = true },
                activeFocus = activeFocus,
            )
        } else {
            SidebarRail(onReached = ::showSidebar)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .onFocusChanged { contentHasFocus = it.hasFocus },
        ) {
            val library = sidebar.active
            key(library) {
                when (library) {
                    HOME -> Column {
                        Header(title = "Home") {
                            TabBar(HOME_TABS, selected = homeTab, onSelect = vm::selectHomeTab)
                        }
                        key(homeTab) {
                            if (homeTab == 0) {
                                RecommendedTab(container, library = null, imageUrl = client::absoluteUrl, onPlay = onPlay)
                            } else {
                                CastBrowserContent(container, onCast)
                            }
                        }
                    }
                    SEARCH -> Column {
                        Header(title = "Search: $query")
                        key(query) { SearchResults(container, query, client::absoluteUrl, onPlay, onCast) }
                    }
                    else -> {
                        val tab = tabs[library] ?: LibraryTab.RECOMMENDED
                        Column {
                            Header(title = library) {
                                TabBar(LibraryTab.entries.map { it.label }, selected = tab.ordinal, onSelect = { vm.selectTab(library, LibraryTab.entries[it]) })
                            }
                            key(tab) {
                                when (tab) {
                                    LibraryTab.RECOMMENDED -> RecommendedTab(container, library, client::absoluteUrl, onPlay)
                                    LibraryTab.LIBRARY -> LibraryTabContent(container, library, client::absoluteUrl, onPlay)
                                    LibraryTab.COLLECTIONS -> CollectionsTab(container, library, client::absoluteUrl, onPlay)
                                    LibraryTab.MOVIES -> MoviesTab(container, library, client::absoluteUrl, onPlay)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (searching) {
        SearchDialog(
            title = "Search everything",
            initial = query,
            hint = "Titles, cast, movies – or lustr's syntax: cast:jane*  site:(a,b)",
            onSearch = { q ->
                searching = false
                if (q.isNotBlank()) vm.search(q)        // ▶ moves into the results (and tucks the sidebar away)
            },
            onDismiss = { searching = false },
        )
    }
}

private val HOME_TABS = listOf("Recommended", "Cast")

/** The open library's name, with its tabs next to it. */
@Composable
private fun Header(title: String, tabs: @Composable () -> Unit = {}) {
    Row(
        Modifier.padding(start = 24.dp, end = EDGE, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = LustrColors.primarySoft,
            maxLines = 1,
        )
        tabs()
    }
}

/** Tabs (e.g. Recommended | Library | Collections | Movies), underlined like the web app's. */
@Composable
private fun TabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, label ->
            val isSelected = i == selected
            Surface(
                onClick = { onSelect(i) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = if (isSelected) Color.White else LustrColors.textSecondary,
                    focusedContainerColor = LustrColors.surfaceElevated,
                    focusedContentColor = Color.White,
                ),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Box(
                        Modifier
                            .padding(top = 4.dp)
                            .width(if (isSelected) 28.dp else 0.dp)
                            .height(2.dp)
                            .background(LustrColors.primaryLight),
                    )
                }
            }
        }
    }
}
