/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.photonmessenger.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.photonmessenger.core.designsystem.component.EmptyState
import io.photonmessenger.core.designsystem.component.ErrorState
import io.photonmessenger.core.designsystem.component.LoadingState
import io.photonmessenger.core.designsystem.component.PhotonAvatar
import io.photonmessenger.core.designsystem.component.ResponsiveContent
import io.photonmessenger.feature.chat.model.UiForwardTarget

/** Destination picker for forwarding a message (screen: Forward to...). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardScreen(
    onBack: () -> Unit,
    onForwarded: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ForwardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.forwarded.collect { onForwarded(it) } }

    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Forward to")
                        val label = viewModel.forwardingLabel
                        if (label.isNotBlank()) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
                scrollBehavior = topBarScroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ResponsiveContent(modifier = Modifier.padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Search") },
                )
                when {
                    state.loading -> LoadingState()
                    state.error != null -> ErrorState(state.error!!)
                    state.filteredEmpty -> EmptyState("No chats match \"${query.trim()}\"")
                    state.isEmpty -> EmptyState("No contacts to forward to yet.")
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (state.recent.isNotEmpty()) {
                            item(key = "header-recent") { SectionHeader("Recent chats") }
                            items(state.recent, key = { "recent-${it.id}" }) { target ->
                                ForwardTargetRow(target = target, onClick = { viewModel.forward(target.id) })
                            }
                        }
                        if (state.others.isNotEmpty()) {
                            item(key = "header-contacts") { SectionHeader("Contacts") }
                            items(state.others, key = { "contact-${it.id}" }) { target ->
                                ForwardTargetRow(target = target, onClick = { viewModel.forward(target.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ForwardTargetRow(
    target: UiForwardTarget,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            PhotonAvatar(
                model = target.avatarUrl,
                name = target.title,
                colorKey = target.id,
                isChannel = target.isChannel,
                size = 44.dp,
            )
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (target.isChannel) {
                    Icon(
                        Icons.Outlined.Groups,
                        contentDescription = "Channel",
                        modifier = Modifier.size(16.dp).padding(end = 2.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    target.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}
