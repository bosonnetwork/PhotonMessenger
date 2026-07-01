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

package io.photonmessenger.feature.settings

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.photonmessenger.feature.settings.model.UiDevice
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    onAddDevice: () -> Unit,
    onApproveDevice: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices & sessions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PairingActions(onAddDevice = onAddDevice, onApproveDevice = onApproveDevice)
            HorizontalDivider()
            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error != null -> Text(
                        state.error ?: "Error",
                        Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    state.devices.isEmpty() -> Text("No devices", Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.devices, key = { it.deviceId }) { device ->
                            DeviceRow(device, viewModel)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    state.passphrasePrompt?.let { prompt ->
        RemoveDevicePassphraseDialog(
            error = prompt.error,
            onDismiss = viewModel::dismissPassphrasePrompt,
            onSubmit = viewModel::confirmRemoveWithPassphrase,
        )
    }
}

/** Prompts for the account passphrase when the server gated a device removal (428/403). */
@Composable
private fun RemoveDevicePassphraseDialog(
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your account is protected by a passphrase. Enter it to remove this device.")
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(passphrase) }, enabled = passphrase.isNotBlank()) {
                Text("Remove")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PairingActions(onAddDevice: () -> Unit, onApproveDevice: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onAddDevice, modifier = Modifier.weight(1f)) {
            Text("Add this device")
        }
        OutlinedButton(onClick = onApproveDevice, modifier = Modifier.weight(1f)) {
            Text("Approve a device")
        }
    }
}

@Composable
private fun DeviceRow(device: UiDevice, viewModel: DevicesViewModel) {
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(if (device.isCurrent) "${device.name} (this device)" else device.name)
        },
        supportingContent = { Text(device.subtitle()) },
        leadingContent = {
            // Decorative: the online/offline state is already in the row subtitle, and a disabled
            // chip would otherwise be announced as "disabled" by TalkBack (X-A1).
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(if (device.online) "Online" else "Offline") },
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        trailingContent = {
            if (!device.isCurrent) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Device actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Sign out session") },
                            enabled = device.online,
                            onClick = {
                                menuOpen = false
                                viewModel.revokeSession(device.deviceId)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove device") },
                            onClick = {
                                menuOpen = false
                                viewModel.removeDevice(device.deviceId)
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun UiDevice.subtitle(): String {
    val parts = buildList {
        app?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (online) {
            add("Active now")
        } else if (lastActive > 0) {
            add("Last active ${formatTimestamp(lastActive)}")
        }
        lastAddress?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return parts.joinToString(" - ").ifEmpty { "Registered ${formatTimestamp(registeredAt)}" }
}

private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
