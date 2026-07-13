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

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
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
import io.photonmessenger.feature.contacts.model.UiContact

/**
 * Contact picker for a named channel invite (screen: Invite to channel). Follows the message-forward
 * picker's layout and interaction, but lists only contacts. Selecting a contact sends them the invite
 * and returns; there is no copy/share step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteContactPickerScreen(
    onBack: () -> Unit,
    onInvited: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InviteContactPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is InvitePickerEvent.Sent -> {
                    Toast.makeText(
                        context,
                        "Invitation sent to ${event.contactName}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    onInvited()
                }
                is InvitePickerEvent.Error -> snackbar.showSnackbar(event.message)
            }
        }
    }

    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Invite to channel") },
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
                    state.filteredEmpty -> EmptyState("No contacts match \"${query.trim()}\"")
                    state.isEmpty -> EmptyState("No contacts to invite yet.")
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item(key = "header-contacts") { SectionHeader("Contacts") }
                        items(state.contacts, key = { it.id }) { contact ->
                            InviteContactRow(
                                contact = contact,
                                enabled = !state.sending,
                                onClick = { viewModel.invite(contact) },
                            )
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
private fun InviteContactRow(
    contact: UiContact,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            PhotonAvatar(
                model = contact.avatarUrl,
                name = contact.displayName,
                colorKey = contact.id,
                size = 44.dp,
            )
        },
        headlineContent = {
            Text(
                contact.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
