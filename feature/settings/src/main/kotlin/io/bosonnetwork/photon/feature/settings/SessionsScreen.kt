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

package io.bosonnetwork.photon.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.photon.core.designsystem.component.EmptyState
import io.bosonnetwork.photon.core.designsystem.component.ErrorState
import io.bosonnetwork.photon.core.designsystem.component.LoadingState
import io.bosonnetwork.photon.core.designsystem.component.ResponsiveContent
import io.bosonnetwork.photon.feature.settings.model.UiDevice

/**
 * Live messaging sessions (service-level, spec screen 6): each row is a connected device, with an
 * online/offline status icon and a Revoke action. Revoke signs a device out of messaging (optionally
 * also deregistering it). Device pairing and the account-level device registry live on [DevicesScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onBack: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var revokeTarget by remember { mutableStateOf<UiDevice?>(null) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = topBarScroll,
                title = { Text("Sessions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ResponsiveContent(modifier = Modifier.padding(padding)) {
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(state.error ?: "Error")
                state.sessions.isEmpty() ->
                    EmptyState("No active sessions", icon = Icons.Outlined.Devices)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.deviceId }) { session ->
                        SessionRow(session, onRevoke = { revokeTarget = it })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    revokeTarget?.let { device ->
        RevokeSessionDialog(
            device = device,
            onConfirm = { alsoRemoveDevice ->
                viewModel.revokeSession(device.deviceId, alsoRemoveDevice)
                revokeTarget = null
            },
            onDismiss = { revokeTarget = null },
        )
    }

    state.passphrasePrompt?.let { prompt ->
        RemoveDevicePassphraseDialog(
            error = prompt.error,
            onDismiss = viewModel::dismissPassphrasePrompt,
            onSubmit = viewModel::confirmRemoveWithPassphrase,
        )
    }
}

/**
 * Confirms revoking a device's messaging session, with an optional checkbox to also deregister the
 * device from the account. Revoke-only (default) leaves the device registered so it can reconnect;
 * checking the box additionally removes the device registration from the Director.
 */
@Composable
private fun RevokeSessionDialog(
    device: UiDevice,
    onConfirm: (alsoRemoveDevice: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var alsoRemoveDevice by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revoke session?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "${device.name} is signed out of the messaging service. The device stays " +
                        "registered and can reconnect later.",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = alsoRemoveDevice,
                            role = Role.Checkbox,
                            onValueChange = { alsoRemoveDevice = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = alsoRemoveDevice, onCheckedChange = null)
                    Column {
                        Text("Also remove this device from your account")
                        Text(
                            "The device is deregistered and can no longer sign in with its device key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(alsoRemoveDevice) }) { Text("Revoke") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SessionRow(session: UiDevice, onRevoke: (UiDevice) -> Unit) {
    DeviceListRow(
        title = session.name,
        device = session,
        leading = {
            // A filled dot reads as online, an outlined ring as offline; the contentDescription keeps
            // it announced for TalkBack now that the text label is gone. It centers on the title line.
            Icon(
                imageVector = if (session.online) Icons.Filled.Circle else Icons.Outlined.Circle,
                contentDescription = if (session.online) "Online" else "Offline",
                tint = if (session.online) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(DeviceRowLeadingSize),
            )
        },
        trailing = {
            // Fixed-width, centered slot so Revoke and the current-device indicator share one
            // horizontal center; DeviceListRow centers the slot vertically on the block.
            Box(Modifier.width(DeviceRowTrailingWidth), contentAlignment = Alignment.Center) {
                if (session.isCurrent) {
                    // The current device's own session cannot be revoked here (the service rejects it).
                    Icon(
                        Icons.Filled.Smartphone,
                        contentDescription = "This device",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(onClick = { onRevoke(session) }) { Text("Revoke") }
                }
            }
        },
    )
}
