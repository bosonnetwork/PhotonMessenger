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

package io.photonmessenger.feature.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.photonmessenger.core.designsystem.component.EmptyState
import io.photonmessenger.core.designsystem.component.ErrorState
import io.photonmessenger.core.designsystem.component.LoadingState
import io.photonmessenger.core.designsystem.component.ResponsiveContent
import io.photonmessenger.feature.contacts.model.UiChannel
import io.photonmessenger.feature.contacts.model.UiChannelMember
import io.photonmessenger.feature.contacts.model.UiChannelRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    viewModel: ChannelDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.channel?.name ?: "Channel") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenChat(viewModel.channelId) }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Open chat")
                    }
                    state.detail?.channel?.let { ChannelOverflow(it, viewModel, onBack) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ResponsiveContent(modifier = Modifier.padding(padding)) {
            val detail = state.detail
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(state.error ?: "Error")
                detail == null -> EmptyState("Channel not found")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item { ChannelHeader(detail.channel) }
                    item { HorizontalDivider() }
                    items(detail.members, key = { it.id }) { member ->
                        MemberRow(detail.channel, member, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelHeader(channel: UiChannel) {
    Column(Modifier.padding(16.dp)) {
        channel.notice?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "${channel.memberCount} members - ${channel.permission.name}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Your role: ${channel.myRole.name}",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun MemberRow(channel: UiChannel, member: UiChannelMember, viewModel: ChannelDetailViewModel) {
    var menu by remember { mutableStateOf(false) }
    val isSelf = member.role == UiChannelRole.OWNER && channel.isOwner
    // Owners/moderators may act on others (never on the owner, never on themselves as owner).
    val actionable = channel.canModerate && member.role != UiChannelRole.OWNER && !isSelf

    ListItem(
        headlineContent = { Text(member.displayName) },
        supportingContent = { Text(member.role.name) },
        trailingContent = {
            if (actionable) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Member actions")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        when (member.role) {
                            UiChannelRole.MEMBER -> DropdownMenuItem(
                                text = { Text("Make moderator") },
                                onClick = { menu = false; viewModel.setRole(member.id, UiChannelRole.MODERATOR) },
                            )
                            UiChannelRole.MODERATOR -> DropdownMenuItem(
                                text = { Text("Remove moderator") },
                                onClick = { menu = false; viewModel.setRole(member.id, UiChannelRole.MEMBER) },
                            )
                            else -> Unit
                        }
                        if (member.role == UiChannelRole.BANNED) {
                            DropdownMenuItem(
                                text = { Text("Unban") },
                                onClick = { menu = false; viewModel.unban(member.id) },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Ban") },
                                onClick = { menu = false; viewModel.ban(member.id) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Remove from channel") },
                            onClick = { menu = false; viewModel.kick(member.id) },
                        )
                        if (channel.isOwner) {
                            DropdownMenuItem(
                                text = { Text("Transfer ownership") },
                                onClick = { menu = false; viewModel.transferOwnership(member.id) },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ChannelOverflow(channel: UiChannel, viewModel: ChannelDetailViewModel, onBack: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Channel actions")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Create invite") },
                onClick = { menu = false; viewModel.invite(null) },
            )
            if (channel.isOwner) {
                DropdownMenuItem(
                    text = { Text("Rotate session key") },
                    onClick = { menu = false; viewModel.rotateSessionKey() },
                )
                DropdownMenuItem(
                    text = { Text("Delete channel") },
                    onClick = { menu = false; viewModel.remove(); onBack() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Leave channel") },
                    onClick = { menu = false; viewModel.leave(); onBack() },
                )
            }
        }
    }
}
