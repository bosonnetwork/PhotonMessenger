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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import io.photonmessenger.feature.contacts.model.UiContact
import io.photonmessenger.feature.contacts.model.UiFriendRequest

private enum class ContactsTab(val label: String) { FRIENDS("Friends"), REQUESTS("Requests"), CHANNELS("Channels") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    modifier: Modifier = Modifier,
    onOpenChannel: (String) -> Unit = {},
    onCreateChannel: () -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                actions = {
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
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
                        text = { Text(if (count > 0) "${tab.label} ($count)" else tab.label) },
                    )
                }
            }

            when {
                state.loading -> CenterBox { CircularProgressIndicator() }
                state.error != null -> CenterBox { Text(state.error!!) }
                else -> when (tabs[selectedTab]) {
                    ContactsTab.FRIENDS -> ContactList(
                        contacts = state.friends,
                        emptyText = "No friends yet. Tap + to add one.",
                        onMute = viewModel::setMuted,
                        onBlock = viewModel::setBlocked,
                        onRemove = viewModel::remove,
                    )
                    ContactsTab.REQUESTS -> RequestList(
                        requests = state.requests,
                        onAccept = viewModel::accept,
                        onDecline = viewModel::decline,
                    )
                    ContactsTab.CHANNELS -> ContactList(
                        contacts = state.channels,
                        emptyText = "No channels yet. Tap the group icon to create one.",
                        onMute = viewModel::setMuted,
                        onBlock = viewModel::setBlocked,
                        onRemove = viewModel::remove,
                        onClick = onOpenChannel,
                    )
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
}

@Composable
private fun ContactList(
    contacts: List<UiContact>,
    emptyText: String,
    onMute: (String, Boolean) -> Unit,
    onBlock: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onClick: ((String) -> Unit)? = null,
) {
    if (contacts.isEmpty()) {
        CenterBox { Text(emptyText) }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(contacts, key = { it.id }) { contact ->
            var menuOpen by remember(contact.id) { mutableStateOf(false) }
            ListItem(
                modifier = if (onClick != null) Modifier.clickable { onClick(contact.id) } else Modifier,
                headlineContent = { Text(contact.displayName) },
                supportingContent = {
                    val flags = buildList {
                        if (contact.muted) add("muted")
                        if (contact.blocked) add("blocked")
                    }
                    if (flags.isNotEmpty()) Text(flags.joinToString(", "))
                },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (contact.muted) "Unmute" else "Mute") },
                                onClick = { onMute(contact.id, !contact.muted); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text(if (contact.blocked) "Unblock" else "Block") },
                                onClick = { onBlock(contact.id, !contact.blocked); menuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                onClick = { onRemove(contact.id); menuOpen = false },
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
        CenterBox { Text("No pending requests.") }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(requests, key = { it.userId }) { request ->
            ListItem(
                headlineContent = { Text(request.userId) },
                supportingContent = { if (request.hello.isNotBlank()) Text(request.hello) },
                trailingContent = {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onDecline(request.userId) }) { Text("Decline") }
                        TextButton(onClick = { onAccept(request.userId) }) { Text("Accept") }
                    }
                },
            )
        }
    }
}

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
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
