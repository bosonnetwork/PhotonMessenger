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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import io.bosonnetwork.photon.feature.contacts.model.UiContact
import kotlinx.coroutines.launch

/**
 * Contact profile (design spec screen 5 detail): avatar, name/alias, the full Boson user id
 * (copyable - it is what friends exchange to connect), notification/privacy toggles, and removal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var editAlias by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.removed.collect { onBack() } }

    val topBarScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = topBarScroll,
                title = { Text(stringResource(R.string.contacts_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.contacts_cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ResponsiveContent(modifier = Modifier.padding(padding)) {
            val contact = state.contact
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(state.error ?: stringResource(R.string.contacts_error_generic))
                contact == null -> EmptyState(stringResource(R.string.contacts_contact_removed))
                else -> ContactDetailContent(
                    contact = contact,
                    bio = state.bio,
                    snackbar = snackbar,
                    onOpenChat = { onOpenChat(contact.id) },
                    onMute = viewModel::setMuted,
                    onBlockRequest = {
                        if (contact.blocked) viewModel.setBlocked(false) else showBlockConfirm = true
                    },
                    onEditAlias = { editAlias = true },
                    onRemove = { showRemoveConfirm = true },
                )
            }
        }
    }

    val contact = state.contact
    if (showRemoveConfirm && contact != null) {
        ConfirmDialog(
            title = stringResource(R.string.contacts_remove_contact_title),
            text = stringResource(R.string.contacts_remove_contact_message, contact.displayName),
            confirmLabel = stringResource(R.string.contacts_action_remove),
            onConfirm = { viewModel.remove() },
            onDismiss = { showRemoveConfirm = false },
        )
    }
    if (showBlockConfirm && contact != null) {
        ConfirmDialog(
            title = stringResource(R.string.contacts_block_contact_title, contact.displayName),
            text = stringResource(R.string.contacts_block_contact_message),
            confirmLabel = stringResource(R.string.contacts_action_block),
            onConfirm = { viewModel.setBlocked(true) },
            onDismiss = { showBlockConfirm = false },
        )
    }
    if (editAlias && contact != null) {
        EditAliasDialog(
            contact = contact,
            onDismiss = { editAlias = false },
            onSubmit = { alias ->
                viewModel.setRemark(alias)
                editAlias = false
            },
        )
    }
}

@Composable
private fun ContactDetailContent(
    contact: UiContact,
    bio: String?,
    snackbar: SnackbarHostState,
    onOpenChat: () -> Unit,
    onMute: (Boolean) -> Unit,
    onBlockRequest: () -> Unit,
    onEditAlias: () -> Unit,
    onRemove: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val userIdCopiedMessage = stringResource(R.string.contacts_user_id_copied)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Identity header
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PhotonAvatar(
                model = contact.avatarUrl,
                name = contact.displayName,
                colorKey = contact.id,
                size = 96.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(contact.displayName, style = MaterialTheme.typography.headlineSmall)
            // When an alias masks the profile name, still show who they call themselves.
            if (contact.remark != null && contact.name != null && contact.remark != contact.name) {
                Text(
                    contact.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!bio.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (contact.blocked) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.contacts_status_blocked),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenChat) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.contacts_action_message))
            }
        }
        HorizontalDivider()

        // Identity
        ListItem(
            headlineContent = { Text(stringResource(R.string.contacts_label_user_id)) },
            supportingContent = {
                Text(
                    contact.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(contact.id))
                    scope.launch { snackbar.showSnackbar(userIdCopiedMessage) }
                }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.contacts_cd_copy_user_id),
                    )
                }
            },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.contacts_label_alias)) },
            supportingContent = {
                Text(contact.remark ?: stringResource(R.string.contacts_alias_none_hint))
            },
            modifier = Modifier.clickable(role = Role.Button, onClick = onEditAlias),
        )
        HorizontalDivider()

        // Notifications & privacy
        ListItem(
            modifier = Modifier.toggleable(
                value = contact.muted,
                onValueChange = onMute,
                role = Role.Switch,
            ),
            headlineContent = { Text(stringResource(R.string.contacts_label_mute)) },
            supportingContent = { Text(stringResource(R.string.contacts_mute_description)) },
            trailingContent = { Switch(checked = contact.muted, onCheckedChange = null) },
        )
        ListItem(
            modifier = Modifier.toggleable(
                value = contact.blocked,
                onValueChange = { onBlockRequest() },
                role = Role.Switch,
            ),
            headlineContent = { Text(stringResource(R.string.contacts_action_block)) },
            supportingContent = { Text(stringResource(R.string.contacts_block_description)) },
            trailingContent = { Switch(checked = contact.blocked, onCheckedChange = null) },
        )
        HorizontalDivider()

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.contacts_button_remove_contact),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
