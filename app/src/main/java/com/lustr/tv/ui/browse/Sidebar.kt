package com.lustr.tv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lustr.tv.Load
import com.lustr.tv.core.LibraryTree
import com.lustr.tv.core.SEARCH
import com.lustr.tv.core.SidebarNode
import com.lustr.tv.core.SidebarState
import com.lustr.tv.ui.theme.LustrColors
import com.lustr.tv.ui.theme.LustrLogo

/**
 * The left column: the logo, Home, All Videos, then libraries and groups in the owner's order.
 * Groups start collapsed; see SidebarState.click for what a click on a group does.
 * [activeFocus] is attached to the open item, so Back from the content can return to it.
 */
@Composable
fun Sidebar(
    tree: Load<List<SidebarNode>>,
    state: SidebarState,
    onClick: (SidebarNode) -> Unit,
    onRetry: () -> Unit,
    onLogOut: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    activeFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(250.dp)
            .fillMaxHeight()
            .background(LustrColors.surfaceRaised)
            .padding(top = 20.dp, bottom = 20.dp),
    ) {
        LustrLogo(Modifier.padding(start = 24.dp, bottom = 8.dp))
        // Search the whole site; the results open as their own page
        SidebarItem(
            SidebarNode(SEARCH, isGroup = false, label = "⌕  Search"),
            active = state.active == SEARCH,
            expanded = false,
            onClick = onSearch,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .then(if (state.active == SEARCH) Modifier.focusRequester(activeFocus) else Modifier),
        )
        when (tree) {
            Load.Loading -> Text("Loading…", color = LustrColors.textSecondary, modifier = Modifier.padding(start = 24.dp))
            is Load.Failed -> Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tree.message, color = LustrColors.danger, style = MaterialTheme.typography.bodySmall)
                Button(onClick = onRetry) { Text("Try again") }
            }
            is Load.Ready -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(LibraryTree.visible(tree.value, state.expanded), key = { it.name }) { node ->
                    SidebarItem(
                        node,
                        active = node.name == state.active,
                        expanded = node.name in state.expanded,
                        onClick = { onClick(node) },
                        modifier = if (node.name == state.active) Modifier.focusRequester(activeFocus) else Modifier,
                    )
                }
            }
        }
        if (tree !is Load.Ready) Spacer(Modifier.weight(1f))
        Row(Modifier.padding(start = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSettings) { Text("Settings") }
            OutlinedButton(onClick = onLogOut) { Text("Log out") }
        }
    }
}

@Composable
private fun SidebarItem(node: SidebarNode, active: Boolean, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(start = (node.depth * 14).dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) LustrColors.activeBackground else Color.Transparent,
            contentColor = if (active) LustrColors.activeText else LustrColors.textPrimary,
            focusedContainerColor = LustrColors.primaryBase,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (node.isGroup) {
                Text(if (expanded) "▾" else "▸", fontSize = 14.sp, modifier = Modifier.width(18.dp))
            }
            Text(
                node.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (node.isGroup) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The sidebar when it's tucked away: a thin strip on the left edge. ◀ from the content lands on it,
 * which opens the sidebar again ([onReached]).
 */
@Composable
fun SidebarRail(onReached: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(18.dp)
            .fillMaxHeight()
            .background(LustrColors.surfaceRaised)
            .onFocusChanged { if (it.isFocused) onReached() }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text("›", color = LustrColors.textSecondary, fontSize = 16.sp)
    }
}
