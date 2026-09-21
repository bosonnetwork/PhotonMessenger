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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.photon.core.designsystem.component.ConfirmDialog
import io.bosonnetwork.photon.core.designsystem.component.CountBadge
import io.bosonnetwork.photon.core.designsystem.component.EmptyState
import io.bosonnetwork.photon.core.designsystem.component.ErrorState
import io.bosonnetwork.photon.core.designsystem.component.LoadingState
import io.bosonnetwork.photon.core.designsystem.component.PhotonAvatar
import io.bosonnetwork.photon.core.designsystem.component.ResponsiveContent
import io.bosonnetwork.photon.core.model.shortId
import io.bosonnetwork.photon.core.qr.QrScanner
import io.bosonnetwork.photon.feature.contacts.model.FriendRequestAction
import io.bosonnetwork.photon.feature.contacts.model.FriendRequestStatus
import io.bosonnetwork.photon.feature.contacts.model.UiContact
import io.bosonnetwork.photon.feature.contacts.model.UiFriendRequest

private enum class ContactsTab { FRIENDS, CHANNELS, REQUESTS }

@Composable
private fun ContactsTab.label(): String = when (this) {
    ContactsTab.FRIENDS -> stringResource(R.string.contacts_tab_friends)
    ContactsTab.CHANNELS -> stringResource(R.string.contacts_tab_channels)
    ContactsTab.REQUESTS -> stringResource(R.string.contacts_tab_requests)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    modifier: Modifier = Modifier,
    onOpenConversation: (String) -> Unit = {},
    onOpenChannel: (String) -> Unit = {},
    onOpenContactDetail: (String) -> Unit = {},
    onOpenChannelDetail: (String) -> Unit = {},
    onCreateChannel: () -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var editAliasContact by remember { mutableStateOf<UiContact?>(null) }
    var blockTarget by remember { mutableStateOf<UiContact?>(null) }
    var removeTarget by remember { mutableStateOf<UiContact?>(null) }
    var resendTarget by remember { mutableStateOf<UiFriendRequest?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.joinedChannel.collect(onOpenChannel)
    }

    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = topBarScroll,
                title = { Text(stringResource(R.string.contacts_title)) },
                actions = {
                    IconButton(onClick = { showJoinDialog = true }) {
                        Icon(
                            Icons.Outlined.AddLink,
                            contentDescription = stringResource(R.string.contacts_cd_join_channel),
                        )
                    }
                    IconButton(onClick = onCreateChannel) {
                        Icon(
                            Icons.Outlined.GroupAdd,
                            contentDescription = stringResource(R.string.contacts_cd_new_channel),
                        )
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Outlined.PersonAdd,
                            contentDescription = stringResource(R.string.contacts_cd_add_friend),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ResponsiveContent(modifier = Modifier.padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                val tabs = ContactsTab.entries
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, tab ->
                        val count = when (tab) {
                            ContactsTab.FRIENDS -> state.friends.size
                            ContactsTab.REQUESTS -> state.requests.size
                            ContactsTab.CHANNELS -> state.channels.size
                        }
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                val label = tab.label()
                                // Requests waiting for an answer are actionable notifications, so they get
                                // a badge; otherwise (and for friends/channels) the total is a plain
                                // "(count)" suffix.
                                if (tab == ContactsTab.REQUESTS && state.requestsAwaitingAnswer > 0) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(label)
                                        CountBadge(state.requestsAwaitingAnswer)
                                    }
                                } else {
                                    Text(
                                        if (count > 0) {
                                            stringResource(R.string.contacts_tab_count_format, label, count)
                                        } else {
                                            label
                                        },
                                    )
                                }
                            },
                        )
                    }
                }

                when {
                    state.loading -> LoadingState()
                    state.error != null -> ErrorState(state.error!!)
                    else -> when (tabs[selectedTab]) {
                        ContactsTab.FRIENDS -> FriendList(
                            contacts = state.friends,
                            onOpen = onOpenConversation,
                            onOpenProfile = onOpenContactDetail,
                            onMute = viewModel::setMuted,
                            onBlock = { contact ->
                                if (contact.blocked) viewModel.setBlocked(contact.id, false)
                                else blockTarget = contact
                            },
                            onEditAlias = { editAliasContact = it },
                            onRemove = { removeTarget = it },
                        )
                        ContactsTab.REQUESTS -> RequestList(
                            requests = state.requests,
                            onOpen = viewModel::openRequest,
                            onAccept = viewModel::accept,
                        )
                        ContactsTab.CHANNELS -> ChannelList(
                            channels = state.channels,
                            onOpen = onOpenChannel,
                            onOpenDetail = onOpenChannelDetail,
                            onMute = viewModel::setMuted,
                        )
                    }
                }
            }
        }
    }

    state.openedRequest?.let { request ->
        FriendRequestDialog(
            request = request,
            onAccept = { viewModel.accept(request.userId) },
            onIgnore = viewModel::ignore,
            onResend = {
                viewModel.closeRequest()
                resendTarget = request
            },
            onRemove = { viewModel.removeRequest(request.userId) },
            onDismiss = viewModel::closeRequest,
        )
    }

    resendTarget?.let { request ->
        ResendRequestDialog(
            request = request,
            onDismiss = { resendTarget = null },
            onSubmit = { hello ->
                viewModel.resend(request.userId, hello)
                resendTarget = null
            },
        )
    }

    if (showAddDialog) {
        AddFriendDialog(
            onDismiss = { showAddDialog = false },
            onSubmit = { id, hello ->
                viewModel.addFriend(id, hello)
                showAddDialog = false
            },
        )
    }

    if (showJoinDialog) {
        JoinChannelDialog(
            onDismiss = { showJoinDialog = false },
            onSubmit = { ticket ->
                viewModel.joinChannel(ticket)
                showJoinDialog = false
            },
        )
    }

    editAliasContact?.let { contact ->
        EditAliasDialog(
            contact = contact,
            onDismiss = { editAliasContact = null },
            onSubmit = { alias ->
                viewModel.setRemark(contact.id, alias)
                editAliasContact = null
            },
        )
    }

    blockTarget?.let { contact ->
        ConfirmDialog(
            title = stringResource(R.string.contacts_block_contact_title, contact.displayName),
            text = stringResource(R.string.contacts_block_contact_message),
            confirmLabel = stringResource(R.string.contacts_action_block),
            onConfirm = { viewModel.setBlocked(contact.id, true) },
            onDismiss = { blockTarget = null },
        )
    }

    removeTarget?.let { contact ->
        ConfirmDialog(
            title = stringResource(R.string.contacts_remove_contact_title),
            text = stringResource(R.string.contacts_remove_contact_message, contact.displayName),
            confirmLabel = stringResource(R.string.contacts_action_remove),
            onConfirm = { viewModel.remove(contact.id) },
            onDismiss = { removeTarget = null },
        )
    }
}

@Composable
private fun FriendList(
    contacts: List<UiContact>,
    onOpen: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onMute: (String, Boolean) -> Unit,
    onBlock: (UiContact) -> Unit,
    onEditAlias: (UiContact) -> Unit,
    onRemove: (UiContact) -> Unit,
) {
    if (contacts.isEmpty()) {
        EmptyState(
            stringResource(R.string.contacts_empty_friends),
            icon = Icons.Outlined.PersonAdd,
        )
        return
    }
    val mutedLabel = stringResource(R.string.contacts_status_muted)
    val blockedLabel = stringResource(R.string.contacts_status_blocked)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(contacts, key = { it.id }) { contact ->
            var menuOpen by remember(contact.id) { mutableStateOf(false) }
            ListItem(
                modifier = Modifier
                    .animateItem()
                    .clickable(role = Role.Button) { onOpen(contact.id) },
                leadingContent = {
                    PhotonAvatar(
                        model = contact.avatarUrl,
                        name = contact.displayName,
                        colorKey = contact.id,
                        size = 48.dp,
                    )
                },
                headlineContent = {
                    Text(contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    val flags = buildList {
                        if (contact.muted) add(mutedLabel)
                        if (contact.blocked) add(blockedLabel)
                    }
                    if (flags.isNotEmpty()) {
                        Text(flags.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.contacts_cd_actions))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.contacts_action_view_profile)) },
                                onClick = { menuOpen = false; onOpenProfile(contact.id) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.contacts_action_edit_alias)) },
                                onClick = { menuOpen = false; onEditAlias(contact) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (contact.muted) R.string.contacts_action_unmute else R.string.contacts_action_mute,
                                        ),
                                    )
                                },
                                onClick = { menuOpen = false; onMute(contact.id, !contact.muted) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (contact.blocked) R.string.contacts_action_unblock else R.string.contacts_action_block,
                                        ),
                                    )
                                },
                                onClick = { menuOpen = false; onBlock(contact) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.contacts_action_remove),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = { menuOpen = false; onRemove(contact) },
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<UiContact>,
    onOpen: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onMute: (String, Boolean) -> Unit,
) {
    if (channels.isEmpty()) {
        EmptyState(
            stringResource(R.string.contacts_empty_channels),
            icon = Icons.Outlined.GroupAdd,
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(channels, key = { it.id }) { channel ->
            var menuOpen by remember(channel.id) { mutableStateOf(false) }
            ListItem(
                modifier = Modifier
                    .animateItem()
                    .clickable(role = Role.Button) { onOpen(channel.id) },
                leadingContent = {
                    PhotonAvatar(
                        model = null,
                        name = channel.displayName,
                        colorKey = channel.id,
                        isChannel = true,
                        size = 48.dp,
                    )
                },
                headlineContent = {
                    Text(channel.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    if (channel.muted) {
                        Text(
                            stringResource(R.string.contacts_status_muted),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.contacts_cd_channel_actions),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.contacts_action_channel_info)) },
                                onClick = { menuOpen = false; onOpenDetail(channel.id) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (channel.muted) R.string.contacts_action_unmute else R.string.contacts_action_mute,
                                        ),
                                    )
                                },
                                onClick = { menuOpen = false; onMute(channel.id, !channel.muted) },
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RequestList(
    requests: List<UiFriendRequest>,
    onOpen: (String) -> Unit,
    onAccept: (String) -> Unit,
) {
    if (requests.isEmpty()) {
        EmptyState(stringResource(R.string.contacts_empty_requests), icon = Icons.Outlined.PersonAdd)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(requests, key = { it.userId }) { request ->
            // Every record is listed; its look follows its state. A request waiting for an answer is
            // highlighted and offers Accept right in the row; an expired one is faded. Tapping any row
            // opens the actions its state allows.
            ListItem(
                modifier = Modifier
                    .animateItem()
                    .clickable { onOpen(request.userId) }
                    .alpha(if (request.status == FriendRequestStatus.EXPIRED) 0.6f else 1f),
                colors = if (request.awaitingAnswer) {
                    ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    )
                } else {
                    ListItemDefaults.colors()
                },
                leadingContent = {
                    PhotonAvatar(
                        model = request.avatarUrl,
                        name = request.name,
                        colorKey = request.userId,
                        size = 48.dp,
                    )
                },
                headlineContent = {
                    Text(
                        request.name ?: shortId(request.userId),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (request.name != null) {
                            Text(
                                shortId(request.userId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (request.hello.isNotBlank()) {
                            Text(
                                stringResource(R.string.contacts_request_hello_format, request.hello),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FriendRequestStatusLabel(request)
                    }
                },
                trailingContent = if (FriendRequestAction.ACCEPT in request.actions) {
                    {
                        FilledTonalButton(onClick = { onAccept(request.userId) }) {
                            Text(stringResource(R.string.contacts_action_accept))
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

/** Direction and state of a request as an icon + line, colored by state. */
@Composable
private fun FriendRequestStatusLabel(request: UiFriendRequest) {
    val (icon, color) = when (request.status) {
        FriendRequestStatus.PENDING ->
            if (request.outgoing) {
                Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.primary
            }
        FriendRequestStatus.ACCEPTED -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.tertiary
        FriendRequestStatus.EXPIRED -> Icons.Outlined.Schedule to MaterialTheme.colorScheme.outline
    }
    val text = stringResource(
        when (request.status) {
            FriendRequestStatus.PENDING ->
                if (request.outgoing) R.string.contacts_request_status_outgoing_pending
                else R.string.contacts_request_status_incoming_pending
            FriendRequestStatus.ACCEPTED ->
                if (request.outgoing) R.string.contacts_request_status_outgoing_accepted
                else R.string.contacts_request_status_incoming_accepted
            FriendRequestStatus.EXPIRED ->
                if (request.outgoing) R.string.contacts_request_status_outgoing_expired
                else R.string.contacts_request_status_incoming_expired
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/**
 * The details of a friend request with exactly the actions its state allows ([UiFriendRequest.actions]):
 * Accept / Ignore / Remove for an incoming request waiting for an answer, Resend / Remove for an
 * outgoing pending or expired one, and only Remove for an accepted one or an incoming expired one.
 * Ignore closes the dialog and nothing else.
 */
@Composable
private fun FriendRequestDialog(
    request: UiFriendRequest,
    onAccept: () -> Unit,
    onIgnore: () -> Unit,
    onResend: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = request.actions
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            PhotonAvatar(
                model = request.avatarUrl,
                name = request.name,
                colorKey = request.userId,
                size = 56.dp,
            )
        },
        title = {
            Text(request.name ?: shortId(request.userId), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    request.userId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FriendRequestStatusLabel(request)
                if (request.hello.isNotBlank()) {
                    Text(stringResource(R.string.contacts_request_hello_format, request.hello))
                }
            }
        },
        confirmButton = {
            when {
                FriendRequestAction.ACCEPT in actions -> FilledTonalButton(onClick = onAccept) {
                    Text(stringResource(R.string.contacts_action_accept))
                }
                FriendRequestAction.RESEND in actions -> FilledTonalButton(onClick = onResend) {
                    Text(stringResource(R.string.contacts_action_resend))
                }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.contacts_action_done)) }
            }
        },
        dismissButton = {
            Row {
                if (FriendRequestAction.REMOVE in actions) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.contacts_action_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                when {
                    FriendRequestAction.IGNORE in actions -> TextButton(onClick = onIgnore) {
                        Text(stringResource(R.string.contacts_action_ignore))
                    }
                    FriendRequestAction.RESEND in actions -> TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.contacts_action_cancel))
                    }
                }
            }
        },
    )
}

/** Resends an outgoing request; the hello starts as the previous one and can be changed. */
@Composable
private fun ResendRequestDialog(
    request: UiFriendRequest,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var hello by remember(request.userId) { mutableStateOf(request.hello) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_resend_request_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.contacts_resend_request_description,
                        request.name ?: shortId(request.userId),
                    ),
                )
                OutlinedTextField(
                    value = hello,
                    onValueChange = { hello = it },
                    label = { Text(stringResource(R.string.contacts_label_say_hello)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(hello) }) { Text(stringResource(R.string.contacts_action_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contacts_action_cancel)) }
        },
    )
}

@Composable
private fun AddFriendDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    // TextFieldValue (not a plain String) so that after Paste/Scan we can drop the caret at position 0.
    // A long base58 id (~44 chars) overflows the single-line field; with the caret left at the end only
    // the tail shows and the recognizable start is scrolled off. Caret-at-start reveals the start, and
    // the field still scrolls horizontally to reach the rest.
    var id by remember { mutableStateOf(TextFieldValue("")) }
    var hello by remember { mutableStateOf("") }
    // The dialog has two modes: the entry form and an in-place QR scanner. Scanning swaps the dialog
    // body for a camera preview and returns to the form with the id filled in, so the user can still
    // add a hello and review before sending (no auto-send: preserves the original review-then-send UX).
    var scanning by remember { mutableStateOf(false) }

    if (scanning) {
        AddFriendScanDialog(
            onScanned = { scanned ->
                id = fieldFromStart(scanned.trim())
                scanning = false
            },
            onCancel = { scanning = false },
        )
        return
    }

    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_add_friend_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.contacts_add_friend_description))
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text(stringResource(R.string.contacts_label_boson_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        // Paste + Scan sit inside the field's trailing area, keeping the
                        // primary shortcuts on the ID line without adding a separate button row.
                        Row {
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let { id = fieldFromStart(it.trim()) }
                            }) {
                                Icon(
                                    Icons.Outlined.ContentPaste,
                                    contentDescription = stringResource(R.string.contacts_cd_paste_user_id),
                                )
                            }
                            IconButton(onClick = { scanning = true }) {
                                Icon(
                                    Icons.Outlined.QrCodeScanner,
                                    contentDescription = stringResource(R.string.contacts_cd_scan_qr_code),
                                )
                            }
                        }
                    },
                )
                OutlinedTextField(
                    value = hello,
                    onValueChange = { hello = it },
                    label = { Text(stringResource(R.string.contacts_label_say_hello)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(id.text, hello) },
                enabled = id.text.isNotBlank(),
            ) { Text(stringResource(R.string.contacts_action_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contacts_action_cancel)) }
        },
    )
}

/** Wraps [text] in a [TextFieldValue] with the caret at the start, so the field shows the id's head. */
private fun fieldFromStart(text: String): TextFieldValue =
    TextFieldValue(text = text, selection = TextRange(0))

/**
 * The QR-scan mode of [AddFriendDialog]: a camera preview that requests CAMERA inline and reports the
 * first decoded payload. Kept as a separate dialog so the scanner tears its camera down when dismissed.
 */
@Composable
private fun AddFriendScanDialog(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.contacts_scan_qr_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (hasCameraPermission) {
                    Text(stringResource(R.string.contacts_scan_qr_instruction))
                    QrScanner(
                        onScanned = onScanned,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                } else {
                    Text(
                        stringResource(R.string.contacts_camera_permission_needed),
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.contacts_action_grant_camera_access))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.contacts_action_cancel)) }
        },
    )
}

@Composable
private fun JoinChannelDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var ticket by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_join_channel_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.contacts_join_channel_description))
                OutlinedTextField(
                    value = ticket,
                    onValueChange = { ticket = it },
                    label = { Text(stringResource(R.string.contacts_label_invite_ticket)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(ticket) }, enabled = ticket.isNotBlank()) {
                Text(stringResource(R.string.contacts_action_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contacts_action_cancel)) }
        },
    )
}

@Composable
internal fun EditAliasDialog(
    contact: UiContact,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var alias by remember { mutableStateOf(contact.remark.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contacts_action_edit_alias)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.contacts_edit_alias_description))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.contacts_label_alias)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(alias) }) { Text(stringResource(R.string.contacts_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contacts_action_cancel)) }
        },
    )
}
