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

package io.bosonnetwork.photon.feature.contacts

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.photon.core.designsystem.component.ConfirmDialog
import io.bosonnetwork.photon.core.designsystem.component.EmptyState
import io.bosonnetwork.photon.core.designsystem.component.ErrorState
import io.bosonnetwork.photon.core.designsystem.component.LoadingState
import io.bosonnetwork.photon.core.designsystem.component.PhotonAvatar
import io.bosonnetwork.photon.core.designsystem.component.ResponsiveContent
import io.bosonnetwork.photon.feature.contacts.model.UiChannel
import io.bosonnetwork.photon.feature.contacts.model.UiChannelMember
import io.bosonnetwork.photon.feature.contacts.model.UiChannelPermission
import io.bosonnetwork.photon.feature.contacts.model.UiChannelRole

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
    onInviteContact: (String) -> Unit = {},
    viewModel: ChannelDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingAction by remember { mutableStateOf<MemberAction?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var inviteTicket by remember { mutableStateOf<String?>(null) }
    var showInviteChooser by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) { viewModel.closed.collect { onBack() } }
    LaunchedEffect(Unit) { viewModel.inviteTicket.collect { inviteTicket = it } }

    val topBarScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = topBarScroll,
                title = { Text(state.detail?.channel?.name ?: stringResource(R.string.contacts_channel_title_fallback)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.contacts_cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenChat(viewModel.channelId) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = stringResource(R.string.contacts_cd_open_chat),
                        )
                    }
                    state.detail?.channel?.let { channel ->
                        ChannelOverflow(
                            channel = channel,
                            onInvite = { showInviteChooser = true },
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
                state.error != null -> ErrorState(state.error ?: stringResource(R.string.contacts_error_generic))
                detail == null -> EmptyState(stringResource(R.string.contacts_channel_not_found))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item { ChannelHeader(detail.channel, onInvite = { showInviteChooser = true }) }
                    item {
                        Text(
                            stringResource(R.string.contacts_members_header_format, detail.members.size),
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
                title = stringResource(R.string.contacts_ban_member_title, action.member.displayName),
                text = stringResource(R.string.contacts_ban_member_message),
                label = stringResource(R.string.contacts_action_ban),
            ) { viewModel.ban(action.member.id) }
            is MemberAction.Kick -> ConfirmSpec(
                title = stringResource(R.string.contacts_remove_member_title, action.member.displayName),
                text = stringResource(R.string.contacts_remove_member_message),
                label = stringResource(R.string.contacts_action_remove),
            ) { viewModel.kick(action.member.id) }
            is MemberAction.Transfer -> ConfirmSpec(
                title = stringResource(R.string.contacts_transfer_ownership_title),
                text = stringResource(R.string.contacts_transfer_ownership_message, action.member.displayName),
                label = stringResource(R.string.contacts_action_transfer),
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
            title = stringResource(R.string.contacts_leave_channel_title),
            text = stringResource(R.string.contacts_leave_channel_message),
            confirmLabel = stringResource(R.string.contacts_action_leave),
            onConfirm = { viewModel.leave() },
            onDismiss = { confirmLeave = false },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.contacts_delete_channel_title),
            text = stringResource(R.string.contacts_delete_channel_message),
            confirmLabel = stringResource(R.string.contacts_action_delete),
            onConfirm = { viewModel.remove() },
            onDismiss = { confirmDelete = false },
        )
    }

    if (showInviteChooser) {
        InviteTypeDialog(
            onInviteContact = {
                showInviteChooser = false
                onInviteContact(viewModel.channelId)
            },
            onShareableLink = {
                showInviteChooser = false
                viewModel.invite(null)
            },
            onDismiss = { showInviteChooser = false },
        )
    }

    inviteTicket?.let { ticket ->
        InviteTicketDialog(
            ticket = ticket,
            onDismiss = { inviteTicket = null },
        )
    }
}

/**
 * Chooses how to invite to a channel: a named invite (default, most convenient) sends the ticket
 * straight to a contact as an in-chat invitation; a shareable link mints a bearer ticket the user can
 * copy/share anywhere.
 */
@Composable
private fun InviteTypeDialog(
    onInviteContact: () -> Unit,
    onShareableLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_create_invite_title)) },
        text = {
            Column {
                ListItem(
                    modifier = Modifier.clickable(onClick = onInviteContact),
                    headlineContent = { Text(stringResource(R.string.contacts_invite_a_contact)) },
                    supportingContent = { Text(stringResource(R.string.contacts_invite_contact_description)) },
                )
                ListItem(
                    modifier = Modifier.clickable(onClick = onShareableLink),
                    headlineContent = { Text(stringResource(R.string.contacts_create_shareable_link)) },
                    supportingContent = { Text(stringResource(R.string.contacts_shareable_link_description)) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contacts_action_cancel)) }
        },
    )
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
            pluralStringResource(R.plurals.contacts_member_count, channel.memberCount, channel.memberCount),
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
            AssistChip(onClick = {}, enabled = false, label = { Text(channel.permission.label()) })
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.contacts_your_role, channel.myRole.label())) },
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onInvite) { Text(stringResource(R.string.contacts_action_create_invite_ticket)) }
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
            PhotonAvatar(
                model = member.avatarUrl,
                name = member.displayName,
                colorKey = member.id,
                size = 44.dp,
            )
        },
        headlineContent = {
            Text(
                if (member.isMe) {
                    stringResource(R.string.contacts_member_you_suffix, member.displayName)
                } else {
                    member.displayName
                },
            )
        },
        supportingContent = {
            if (member.role != UiChannelRole.MEMBER) {
                Text(
                    member.role.label(),
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
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.contacts_cd_member_actions),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        when (member.role) {
                            UiChannelRole.MEMBER -> DropdownMenuItem(
                                text = { Text(stringResource(R.string.contacts_action_make_moderator)) },
                                onClick = { menu = false; onSetRole(member.id, UiChannelRole.MODERATOR) },
                            )
                            UiChannelRole.MODERATOR -> DropdownMenuItem(
                                text = { Text(stringResource(R.string.contacts_action_remove_moderator)) },
                                onClick = { menu = false; onSetRole(member.id, UiChannelRole.MEMBER) },
                            )
                            else -> Unit
                        }
                        if (member.role == UiChannelRole.BANNED) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.contacts_action_unban)) },
                                onClick = { menu = false; onUnban(member.id) },
                            )
                        } else {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.contacts_action_ban),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = { menu = false; onAction(MemberAction.Ban(member)) },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.contacts_action_remove_from_channel),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { menu = false; onAction(MemberAction.Kick(member)) },
                        )
                        if (channel.isOwner && member.role != UiChannelRole.BANNED) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.contacts_menu_transfer_ownership)) },
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
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.contacts_cd_channel_actions),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.contacts_menu_create_invite)) },
                onClick = { menu = false; onInvite() },
            )
            if (channel.isOwner) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.contacts_action_rotate_session_key)) },
                    onClick = { menu = false; onRotateKey() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.contacts_menu_delete_channel),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { menu = false; onDelete() },
                )
            } else {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.contacts_menu_leave_channel),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
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
    val shareChooserTitle = stringResource(R.string.contacts_share_invite_ticket_chooser)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_invite_ticket_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.contacts_invite_ticket_description))
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
                    Text(stringResource(R.string.contacts_action_copy))
                }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, ticket)
                    }
                    context.startActivity(Intent.createChooser(send, shareChooserTitle))
                }) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(stringResource(R.string.contacts_action_share))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contacts_action_done)) }
        },
    )
}

@Composable
private fun UiChannelPermission.label(): String = when (this) {
    UiChannelPermission.PUBLIC -> stringResource(R.string.contacts_permission_public)
    UiChannelPermission.MEMBER_INVITE -> stringResource(R.string.contacts_permission_member_invite)
    UiChannelPermission.MODERATOR_INVITE -> stringResource(R.string.contacts_permission_moderator_invite)
    UiChannelPermission.OWNER_INVITE -> stringResource(R.string.contacts_permission_owner_invite)
}

@Composable
private fun UiChannelRole.label(): String = when (this) {
    UiChannelRole.OWNER -> stringResource(R.string.contacts_role_owner)
    UiChannelRole.MODERATOR -> stringResource(R.string.contacts_role_moderator)
    UiChannelRole.MEMBER -> stringResource(R.string.contacts_role_member)
    UiChannelRole.BANNED -> stringResource(R.string.contacts_role_banned)
}
