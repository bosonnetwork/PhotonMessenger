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

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.photonmessenger.core.designsystem.component.ConfirmDialog
import io.photonmessenger.core.designsystem.component.EmptyState
import io.photonmessenger.core.designsystem.component.ErrorState
import io.photonmessenger.core.designsystem.component.LoadingState
import io.photonmessenger.core.designsystem.component.PhotonAvatar
import io.photonmessenger.core.designsystem.component.ResponsiveContent
import io.photonmessenger.feature.contacts.model.UiChannel
import io.photonmessenger.feature.contacts.model.UiChannelMember
import io.photonmessenger.feature.contacts.model.UiChannelPermission
import io.photonmessenger.feature.contacts.model.UiChannelRole

/** A pending member moderation that needs the user's confirmation before running. */
private sealed interface MemberAction {
    val member: UiChannelMember

    data class Ban(override val member: UiChannelMember) : MemberAction
    data class Kick(override val member: UiChannelMember) : MemberAction
    data class Transfer(override val member: UiChannelMember) : MemberAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    viewModel: ChannelDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingAction by remember { mutableStateOf<MemberAction?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var inviteTicket by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) { viewModel.closed.collect { onBack() } }
    LaunchedEffect(Unit) { viewModel.inviteTicket.collect { inviteTicket = it } }

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
                    state.detail?.channel?.let { channel ->
                        ChannelOverflow(
                            channel = channel,
                            onInvite = { viewModel.invite(null) },
                            onRotateKey = viewModel::rotateSessionKey,
                            onLeave = { confirmLeave = true },
                            onDelete = { confirmDelete = true },
                        )
                    }
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
                    item { ChannelHeader(detail.channel, onInvite = { viewModel.invite(null) }) }
                    item {
                        Text(
                            "${detail.members.size} MEMBERS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(detail.members, key = { it.id }) { member ->
                        MemberRow(
                            channel = detail.channel,
                            member = member,
                            onSetRole = viewModel::setRole,
                            onUnban = viewModel::unban,
                            onAction = { pendingAction = it },
                        )
                    }
                }
            }
        }
    }

    pendingAction?.let { action ->
        val spec = when (action) {
            is MemberAction.Ban -> ConfirmSpec(
                title = "Ban ${action.member.displayName}?",
                text = "They will be removed and can't rejoin until unbanned.",
                label = "Ban",
            ) { viewModel.ban(action.member.id) }
            is MemberAction.Kick -> ConfirmSpec(
                title = "Remove ${action.member.displayName}?",
                text = "They will be removed from the channel but can be invited again.",
                label = "Remove",
            ) { viewModel.kick(action.member.id) }
            is MemberAction.Transfer -> ConfirmSpec(
                title = "Transfer ownership?",
                text = "${action.member.displayName} becomes the owner. You keep your membership " +
                    "but can no longer manage or delete the channel.",
                label = "Transfer",
            ) { viewModel.transferOwnership(action.member.id) }
        }
        ConfirmDialog(
            title = spec.title,
            text = spec.text,
            confirmLabel = spec.label,
            onConfirm = spec.run,
            onDismiss = { pendingAction = null },
        )
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Leave channel?",
            text = "You will stop receiving messages from this channel. You can rejoin later " +
                "with an invite.",
            confirmLabel = "Leave",
            onConfirm = { viewModel.leave() },
            onDismiss = { confirmLeave = false },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete channel?",
            text = "The channel and its messages are deleted for every member. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.remove() },
            onDismiss = { confirmDelete = false },
        )
    }

    inviteTicket?.let { ticket ->
        InviteTicketDialog(
            ticket = ticket,
            onDismiss = { inviteTicket = null },
        )
    }
}

private class ConfirmSpec(
    val title: String,
    val text: String,
    val label: String,
    val run: () -> Unit,
)

@Composable
private fun ChannelHeader(channel: UiChannel, onInvite: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhotonAvatar(
            model = null,
            name = channel.name,
            colorKey = channel.id,
            isChannel = true,
            size = 88.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(channel.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            "${channel.memberCount} ${if (channel.memberCount == 1) "member" else "members"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        channel.notice?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, enabled = false, label = { Text(channel.permission.label) })
            AssistChip(onClick = {}, enabled = false, label = { Text("Your role: ${channel.myRole.label}") })
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onInvite) { Text("Create invite ticket") }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

@Composable
private fun MemberRow(
    channel: UiChannel,
    member: UiChannelMember,
    onSetRole: (String, UiChannelRole) -> Unit,
    onUnban: (String) -> Unit,
    onAction: (MemberAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Owners/moderators may act on other, non-owner members - never on themselves or the owner.
    val actionable = channel.canModerate && !member.isMe && member.role != UiChannelRole.OWNER

    ListItem(
        leadingContent = {
            PhotonAvatar(model = null, name = member.displayName, colorKey = member.id, size = 44.dp)
        },
        headlineContent = {
            Text(if (member.isMe) "${member.displayName} (you)" else member.displayName)
        },
        supportingContent = {
            if (member.role != UiChannelRole.MEMBER) {
                Text(
                    member.role.label,
                    color = when (member.role) {
                        UiChannelRole.BANNED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
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
                                onClick = { menu = false; onSetRole(member.id, UiChannelRole.MODERATOR) },
                            )
                            UiChannelRole.MODERATOR -> DropdownMenuItem(
                                text = { Text("Remove moderator") },
                                onClick = { menu = false; onSetRole(member.id, UiChannelRole.MEMBER) },
                            )
                            else -> Unit
                        }
                        if (member.role == UiChannelRole.BANNED) {
                            DropdownMenuItem(
                                text = { Text("Unban") },
                                onClick = { menu = false; onUnban(member.id) },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Ban", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onAction(MemberAction.Ban(member)) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Remove from channel", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onAction(MemberAction.Kick(member)) },
                        )
                        if (channel.isOwner && member.role != UiChannelRole.BANNED) {
                            DropdownMenuItem(
                                text = { Text("Transfer ownership") },
                                onClick = { menu = false; onAction(MemberAction.Transfer(member)) },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ChannelOverflow(
    channel: UiChannel,
    onInvite: () -> Unit,
    onRotateKey: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Channel actions")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Create invite") },
                onClick = { menu = false; onInvite() },
            )
            if (channel.isOwner) {
                DropdownMenuItem(
                    text = { Text("Rotate session key") },
                    onClick = { menu = false; onRotateKey() },
                )
                DropdownMenuItem(
                    text = { Text("Delete channel", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Leave channel", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onLeave() },
                )
            }
        }
    }
}

/**
 * Shows a freshly minted invite ticket with copy and share actions (M4-2). The ticket is a long
 * opaque string, so a snackbar is useless for it - the receiver needs the exact text.
 */
@Composable
private fun InviteTicketDialog(ticket: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite ticket") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Send this ticket to the person you want to invite. They can join from " +
                        "Contacts with \"Join channel\".",
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        ticket,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(ticket)) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text("Copy")
                }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, ticket)
                    }
                    context.startActivity(Intent.createChooser(send, "Share invite ticket"))
                }) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text("Share")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private val UiChannelPermission.label: String
    get() = when (this) {
        UiChannelPermission.PUBLIC -> "Anyone can join"
        UiChannelPermission.MEMBER_INVITE -> "Members can invite"
        UiChannelPermission.MODERATOR_INVITE -> "Moderators can invite"
        UiChannelPermission.OWNER_INVITE -> "Owner invites only"
    }

private val UiChannelRole.label: String
    get() = when (this) {
        UiChannelRole.OWNER -> "Owner"
        UiChannelRole.MODERATOR -> "Moderator"
        UiChannelRole.MEMBER -> "Member"
        UiChannelRole.BANNED -> "Banned"
    }
