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

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.photonmessenger.core.designsystem.component.ConfirmDialog
import io.photonmessenger.core.designsystem.component.EmptyState
import io.photonmessenger.core.designsystem.component.ErrorState
import io.photonmessenger.core.designsystem.component.LoadingState
import io.photonmessenger.core.designsystem.component.PhotonAvatar
import io.photonmessenger.core.designsystem.component.ResponsiveContent
import io.photonmessenger.core.designsystem.component.formatListTime
import io.photonmessenger.feature.chat.model.UiConversation

/** Conversation list / main dashboard (design spec screen 3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<UiConversation?>(null) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("Chats") }, scrollBehavior = topBarScroll) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("New chat") },
                icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                onClick = onNewChat,
            )
        },
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
                    state.filteredEmpty -> EmptyState("No conversations match \"${query.trim()}\"")
                    state.conversations.isEmpty() ->
                        EmptyState(
                            "No conversations yet.\nStart one from your contacts.",
                            icon = Icons.AutoMirrored.Filled.Chat,
                        )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.conversations, key = { it.id }) { convo ->
                            ConversationRow(
                                conversation = convo,
                                onClick = { onOpenConversation(convo.id) },
                                onDelete = { deleteTarget = convo },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { convo ->
        ConfirmDialog(
            title = "Delete conversation?",
            text = "This removes the message history for \"${convo.title}\" from your account. " +
                "The ${if (convo.isChannel) "channel" else "contact"} is not removed.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.deleteConversation(convo.id) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: UiConversation,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember(conversation.id) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier = modifier) {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menuOpen = true },
            ),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = {
                PhotonAvatar(
                    model = conversation.avatarUrl,
                    name = conversation.title,
                    colorKey = conversation.id,
                    isChannel = conversation.isChannel,
                    size = 52.dp,
                )
            },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.isChannel) {
                        Icon(
                            Icons.Outlined.Groups,
                            contentDescription = "Channel",
                            modifier = Modifier.size(16.dp).padding(end = 2.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            supportingContent = {
                Text(
                    conversation.preview.ifBlank { "No messages yet" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Text(
                    formatListTime(conversation.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Delete conversation", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}
