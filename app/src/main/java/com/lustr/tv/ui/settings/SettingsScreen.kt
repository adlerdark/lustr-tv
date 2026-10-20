package com.lustr.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.Load
import com.lustr.tv.core.DisplayFilter
import com.lustr.tv.core.LengthRange
import com.lustr.tv.core.ResumeMode
import com.lustr.tv.core.SubtitleSize
import com.lustr.tv.core.TvSettings
import com.lustr.tv.core.VideoZoom
import com.lustr.tv.core.after
import com.lustr.tv.ui.browse.SectionTitle
import com.lustr.tv.ui.theme.LustrColors
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The TV's settings. Each line changes with OK (on / off, or the next choice).
 * Library and playback settings stay on the TV; the orientation filter is the server's
 * display filter (shared with the web app for this account).
 */
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onChangeServer: () -> Unit, onLoggedOut: () -> Unit) {
    val s by container.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var display by remember { mutableStateOf<Load<DisplayFilter>>(Load.Loading) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) { display = container.load { displayFilter() } }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onBack)

    fun toggleType(type: String) {
        val current = (display as? Load.Ready)?.value ?: return
        val types = if (type in current.types) current.types - type else current.types + type
        display = Load.Ready(current.copy(types = types))
        scope.launch {
            if (container.load { setDisplayFilter(types) } is Load.Ready) container.dataVersion.value++
        }
    }

    fun change(update: (TvSettings) -> TvSettings) = container.updateSettings(update)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 32.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                SectionTitle("Settings", Modifier.weight(1f))
                Text("Back returns to the library", color = LustrColors.textSecondary)
            }
        }

        heading("Library")
        item { Note("Length filter the Library tab starts with (you can still change it there).") }
        items(LengthRange.entries) { range ->
            SettingRow(
                title = "Length: ${range.label}",
                value = if (range.value in s.defaultLengths) "Shown" else "Hidden",
                on = range.value in s.defaultLengths,
                modifier = if (range == LengthRange.entries.first()) Modifier.focusRequester(first) else Modifier,
            ) {
                change { it.copy(defaultLengths = if (range.value in it.defaultLengths) it.defaultLengths - range.value else it.defaultLengths + range.value) }
            }
        }

        heading("Orientation filter")
        item { Note("Which libraries and videos are shown. Saved on the server for your account, so the web app follows it too.") }
        when (val d = display) {
            Load.Loading -> item { Note("Loading…") }
            is Load.Failed -> item { Note("Couldn't load: ${d.message}") }
            is Load.Ready -> items(d.value.displayTypes) { type ->
                SettingRow(
                    title = type.replaceFirstChar { it.uppercase() },
                    value = if (type in d.value.types) "Shown" else "Hidden",
                    on = type in d.value.types,
                ) { toggleType(type) }
            }
        }

        heading("Playback")
        item {
            SettingRow("Start quality", TvSettings.QUALITIES.firstOrNull { it.first == s.defaultQuality }?.second ?: s.defaultQuality) {
                change { it.copy(defaultQuality = TvSettings.QUALITIES.map { q -> q.first }.after(it.defaultQuality)) }
            }
        }
        item { Note("Direct play is full quality with no work for the server; if a file won't play, the player switches to a transcode by itself. Change it while watching with the ⚙ button.") }
        item {
            SettingRow("Left / right on the remote", "Jump ${s.seekStepSeconds} seconds (hold to seek fast)") {
                change { it.copy(seekStepSeconds = TvSettings.SEEK_STEPS.after(it.seekStepSeconds)) }
            }
        }
        item { SettingRow("Resume", s.resume.label) { change { it.copy(resume = ResumeMode.entries.after(it.resume)) } } }
        item {
            SettingRow("Captions", if (s.subtitlesOn) "On when a video has them" else "Off (turn on with CC)", on = s.subtitlesOn) {
                change { it.copy(subtitlesOn = !it.subtitlesOn) }
            }
        }
        item { SettingRow("Caption size", s.subtitleSize.label) { change { it.copy(subtitleSize = SubtitleSize.entries.after(it.subtitleSize)) } } }
        item { SettingRow("Picture", s.zoom.label) { change { it.copy(zoom = VideoZoom.entries.after(it.zoom)) } } }
        item {
            SettingRow("Match frame rate", if (s.matchFrameRate) "On (when the TV can switch seamlessly)" else "Off", on = s.matchFrameRate) {
                change { it.copy(matchFrameRate = !it.matchFrameRate) }
            }
        }
        item { Note("For smooth 24 / 25 fps playback also turn on Settings → Display & Sound → Advanced → Match content frame rate on the Shield.") }

        heading("Privacy")
        item {
            SettingRow("Privacy screen", if (s.privacyScreen) "On: no preview in recent apps, no screenshots" else "Off", on = s.privacyScreen) {
                change { it.copy(privacyScreen = !it.privacyScreen) }
            }
        }
        item { Note("Sign-in always lasts 4 hours at most and is asked again every time the app opens.") }

        heading("Server")
        item { SettingRow("Server", container.serverUrl?.let { "${it.host}:${it.port}" } ?: "-") { onChangeServer() } }
        item {
            SettingRow("Log out", "") {
                scope.launch {
                    try {
                        container.client.logout()
                    } catch (e: IOException) {
                        // logged out locally either way
                    }
                    container.forgetLogin()
                    onLoggedOut()
                }
            }
        }
    }
}

private fun LazyListScope.heading(text: String) {
    item {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = LustrColors.primarySoft,
            modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LustrColors.textSecondary,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
    )
}

/** A setting: its name on the left, the current value on the right; OK changes it. [on] colours the value. */
@Composable
private fun SettingRow(title: String, value: String, modifier: Modifier = Modifier, on: Boolean? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = LustrColors.textBrightest,
            focusedContainerColor = LustrColors.primaryBase,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.width(320.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    value,
                    color = when {
                        focused -> Color.White
                        on == true -> LustrColors.primarySoft
                        on == false -> LustrColors.textSecondary
                        else -> LustrColors.textPrimary
                    },
                )
            }
        }
    }
}
