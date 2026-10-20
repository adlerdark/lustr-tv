package com.lustr.tv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.lustr.tv.core.FilterCounts
import com.lustr.tv.core.LengthRange
import com.lustr.tv.core.SortOption
import com.lustr.tv.core.VideoFilter
import com.lustr.tv.ui.components.TvTextField
import com.lustr.tv.ui.theme.LustrColors

/** A line in a dialog list: [marker] on the left (✓, ✕, ↑ ...), [count] on the right. */
@Composable
fun ChoiceRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    marker: String = "",
    markerColor: Color = LustrColors.primarySoft,
    count: Int? = null,
    highlighted: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlighted) LustrColors.activeBackground else Color.Transparent,
            contentColor = LustrColors.textBrightest,
            focusedContainerColor = LustrColors.primaryBase,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(marker, color = markerColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (count != null) Text("$count", color = LustrColors.textSecondary)
        }
    }
}

/** A dark panel; [side] = docked to the right edge (filters), otherwise centred. */
@Composable
fun Panel(onDismiss: () -> Unit, side: Boolean = false, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = if (side) Alignment.CenterEnd else Alignment.Center) {
            // a dialog is its own window, outside the app's Surface: this one sets the text colour again
            Surface(
                modifier = if (side) Modifier.width(420.dp).fillMaxHeight() else Modifier.width(520.dp),
                shape = RoundedCornerShape(if (side) 0.dp else 12.dp),
                colors = SurfaceDefaults.colors(containerColor = LustrColors.surfaceRaised),
            ) {
                Box(Modifier.padding(24.dp)) { content() }
            }
        }
    }
}

@Composable
fun SearchDialog(title: String, initial: String, hint: String, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    Panel(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionTitle(title)
            TvTextField(
                value = text,
                onValueChange = { text = it },
                label = hint,
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text.trim()) }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onSearch(text.trim()) }) { Text("Search") }
                OutlinedButton(onClick = { onSearch("") }) { Text("Clear") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Picking the current sort again turns it around (↑ / ↓). */
@Composable
fun SortDialog(current: VideoFilter, onPick: (VideoFilter) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    Panel(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("Sort by", Modifier.padding(bottom = 4.dp))
            Text("Pick the selected one again to reverse it.", color = LustrColors.textSecondary, style = MaterialTheme.typography.bodySmall)
            LazyColumn(contentPadding = PaddingValues(top = 8.dp)) {
                items(SortOption.entries) { option ->
                    val selected = option.value == current.sortBy
                    ChoiceRow(
                        text = option.label,
                        marker = if (selected) (if (current.descending) "↓" else "↑") else "",
                        highlighted = selected,
                        modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                        onClick = {
                            onPick(
                                if (selected) current.copy(descending = !current.descending)
                                else current.copy(sortBy = option.value, descending = option.descendingByDefault),
                            )
                        },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * Favorites, length, tags and sites. A tag / site cycles: off -> ✓ must have -> ✕ must not have -> off.
 * Changes apply straight away (the grid behind updates); the counts follow the other filters.
 */
@Composable
fun FilterDialog(filter: VideoFilter, counts: FilterCounts?, onChange: (VideoFilter) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    Panel(onDismiss, side = true) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    SectionTitle("Filters", Modifier.weight(1f))
                    OutlinedButton(onClick = { onChange(filter.clearFilters()) }) { Text("Clear all") }
                }
            }
            item {
                ChoiceRow(
                    "Favorites only",
                    onClick = { onChange(filter.copy(favoritesOnly = !filter.favoritesOnly)) },
                    marker = if (filter.favoritesOnly) "★" else "",
                    markerColor = LustrColors.favorite,
                    modifier = Modifier.focusRequester(focus),
                )
            }
            item { Heading("Length") }
            items(LengthRange.entries) { range ->
                ChoiceRow(range.label, onClick = { onChange(filter.toggleLength(range.value)) }, marker = if (range.value in filter.lengths) "✓" else "")
            }
            tagSection("Tags", counts?.tags, filter.tags, counts == null) { onChange(filter.cycleTag(it)) }
            tagSection("Sites", counts?.sites, filter.sites, counts == null) { onChange(filter.cycleSite(it)) }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private fun LazyListScope.tagSection(
    title: String,
    counts: Map<String, Int>?,
    chosen: Map<String, Boolean>,
    loading: Boolean,
    onCycle: (String) -> Unit,
) {
    item { Heading(title) }
    // chosen ones first (they may have no count any more), then the rest by how many videos have them
    val names = chosen.keys.toList() + (counts.orEmpty().entries.sortedByDescending { it.value }.map { it.key }.filter { it !in chosen })
    if (names.isEmpty()) {
        item { Text(if (loading) "Loading…" else "None", color = LustrColors.textSecondary, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)) }
    }
    items(names, key = { "$title:$it" }) { name ->
        val state = chosen[name]
        ChoiceRow(
            text = name,
            onClick = { onCycle(name) },
            marker = when (state) { true -> "✓"; false -> "✕"; null -> "" },
            markerColor = if (state == false) LustrColors.danger else LustrColors.primarySoft,
            count = counts?.get(name),
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = LustrColors.textSecondary,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}
