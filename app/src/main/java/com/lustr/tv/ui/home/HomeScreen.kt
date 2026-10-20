package com.lustr.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lustr.tv.AppContainer
import com.lustr.tv.core.HomeRow
import com.lustr.tv.core.Video
import com.lustr.tv.ui.components.CenteredMessage

private val EDGE = 48.dp          // TV safe area: keep content away from the screen edges

@Composable
fun HomeScreen(container: AppContainer, onPlay: (Video) -> Unit, onNeedLogin: () -> Unit) {
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    LaunchedEffect(state) { if (state is HomeState.NeedsLogin) onNeedLogin() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = EDGE, end = EDGE, top = 27.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("lustr", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = vm::logOut) { Text("Log out") }
        }

        when (val s = state) {
            HomeState.Loading, HomeState.NeedsLogin -> CenteredMessage("Loading…")
            is HomeState.Failed -> CenteredMessage(s.message) { Button(onClick = vm::load) { Text("Try again") } }
            is HomeState.Loaded ->
                if (s.rows.isEmpty()) {
                    CenteredMessage("Nothing here yet. Add a library and scan it in lustr's web app.")
                } else {
                    val client = remember { container.client }
                    HomeRows(s.rows, thumbUrl = client::absoluteUrl, onPlay = onPlay)
                }
        }
    }
}

@Composable
private fun HomeRows(rows: List<HomeRow>, thumbUrl: (String) -> String, onPlay: (Video) -> Unit) {
    val firstCard = remember { FocusRequester() }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { rowIndex, row ->
            Column {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = EDGE, top = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = EDGE, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(row.items, key = { it.path }) { video ->
                        val first = rowIndex == 0 && video === row.items.first()
                        VideoCard(
                            video,
                            thumbUrl = video.thumb?.let(thumbUrl),
                            onClick = { onPlay(video) },
                            modifier = if (first) Modifier.focusRequester(firstCard) else Modifier,
                        )
                    }
                }
            }
        }
    }
    // Start with the first card focused, so the remote works straight away
    LaunchedEffect(Unit) { runCatching { firstCard.requestFocus() } }
}
