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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.photonmessenger.core.designsystem.component.ConfirmDialog
import io.photonmessenger.core.designsystem.component.CountBadge
import io.photonmessenger.core.designsystem.component.EmptyState
import io.photonmessenger.core.designsystem.component.ErrorState
import io.photonmessenger.core.designsystem.component.LoadingState
import io.photonmessenger.core.designsystem.component.PhotonAvatar
import io.photonmessenger.core.designsystem.component.ResponsiveContent
import io.photonmessenger.feature.contacts.model.UiContact
import io.photonmessenger.feature.contacts.model.UiFriendRequest

private enum class ContactsTab(val label: String) { FRIENDS("Friends"), REQUESTS("Requests"), CHANNELS("Channels") }

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
                title = { Text("Contacts") },
                actions = {
                    IconButton(onClick = { showJoinDialog = true }) {
                        Icon(Icons.Outlined.AddLink, contentDescription = "Join channel with a ticket")
                    }
                    IconButton(onClick = onCreateChannel) {
                        Icon(Icons.Outlined.GroupAdd, contentDescription = "New channel")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = "Add friend")
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
                                // Pending requests are actionable notifications, so they get a badge;
                                // friend/channel totals stay as a plain "(count)" suffix.
                                if (tab == ContactsTab.REQUESTS && count > 0) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(tab.label)
                                        CountBadge(count)
                                    }
                                } else {
                                    Text(if (count > 0) "${tab.label} ($count)" else tab.label)
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
                            onAccept = viewModel::accept,
                            onDecline = viewModel::decline,
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
            title = "Block ${contact.displayName}?",
            text = "You will no longer receive messages from this contact.",
            confirmLabel = "Block",
            onConfirm = { viewModel.setBlocked(contact.id, true) },
            onDismiss = { blockTarget = null },
        )
    }

    removeTarget?.let { contact ->
        ConfirmDialog(
            title = "Remove contact?",
            text = "${contact.displayName} will be removed from your contacts. " +
                "You can add them again later with their ID.",
            confirmLabel = "Remove",
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
            "No friends yet.\nShare your user ID (Settings) or add a friend with theirs.",
            icon = Icons.Outlined.PersonAdd,
        )
        return
    }
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
                        if (contact.muted) add("Muted")
                        if (contact.blocked) add("Blocked")
                    }
                    if (flags.isNotEmpty()) {
                        Text(flags.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("View profile") },
                                onClick = { menuOpen = false; onOpenProfile(contact.id) },
                            )
                            DropdownMenuItem(
                                text = { Text("Edit alias") },
                                onClick = { menuOpen = false; onEditAlias(contact) },
                            )
                            DropdownMenuItem(
                                text = { Text(if (contact.muted) "Unmute" else "Mute") },
                                onClick = { menuOpen = false; onMute(contact.id, !contact.muted) },
                            )
                            DropdownMenuItem(
                                text = { Text(if (contact.blocked) "Unblock" else "Block") },
                                onClick = { menuOpen = false; onBlock(contact) },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
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
            "No channels yet.\nCreate one with the group icon, or join with an invite ticket.",
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
                        Text("Muted", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Channel actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Channel info") },
                                onClick = { menuOpen = false; onOpenDetail(channel.id) },
                            )
                            DropdownMenuItem(
                                text = { Text(if (channel.muted) "Unmute" else "Mute") },
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
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
) {
    if (requests.isEmpty()) {
        EmptyState("No pending requests.", icon = Icons.Outlined.PersonAdd)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(requests, key = { it.userId }) { request ->
            ListItem(
                modifier = Modifier.animateItem(),
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
                        request.name ?: shortRequestId(request.userId),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (request.name != null) {
                            Text(
                                shortRequestId(request.userId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (request.hello.isNotBlank()) {
                            Text(
                                "\"${request.hello}\"",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { onAccept(request.userId) }) { Text("Accept") }
                            TextButton(onClick = { onDecline(request.userId) }) { Text("Decline") }
                        }
                    }
                },
            )
        }
    }
}

private fun shortRequestId(id: String): String =
    if (id.length <= 16) id else id.take(10) + "..." + id.takeLast(4)

@Composable
private fun AddFriendDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var hello by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ask your friend for their user ID (shown in their Settings).")
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Boson ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hello,
                    onValueChange = { hello = it },
                    label = { Text("Say hello (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(id, hello) }, enabled = id.isNotBlank()) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        title = { Text("Join channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste the invite ticket you were given.")
                OutlinedTextField(
                    value = ticket,
                    onValueChange = { ticket = it },
                    label = { Text("Invite ticket") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(ticket) }, enabled = ticket.isNotBlank()) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        title = { Text("Edit alias") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Set a local alias for this contact. Leave blank to clear it.")
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Alias") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(alias) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
