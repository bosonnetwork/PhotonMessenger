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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.photon.core.designsystem.component.ConfirmDialog
import io.bosonnetwork.photon.core.designsystem.component.EmptyState
import io.bosonnetwork.photon.core.designsystem.component.ErrorState
import io.bosonnetwork.photon.core.designsystem.component.LoadingState
import io.bosonnetwork.photon.core.designsystem.component.ResponsiveContent
import io.bosonnetwork.photon.feature.settings.R
import io.bosonnetwork.photon.feature.settings.model.UiDevice

/**
 * Account-level device management (spec screen 6): every device registered under the account,
 * regardless of whether it has a live messaging session. Removing a device deregisters its device
 * key from the Director; the session-level view (sign a device out of messaging) is [SessionsScreen].
 * Device pairing (add this device / approve a device) and revealing this device's identity key live
 * here too, at the bottom and behind the current-device indicator respectively.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    onAddDevice: () -> Unit,
    onApproveDevice: () -> Unit,
    onShowKey: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var removeTarget by remember { mutableStateOf<UiDevice?>(null) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = topBarScroll,
                title = { Text(stringResource(R.string.settings_devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_content_description),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ResponsiveContent(modifier = Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when {
                        state.loading -> LoadingState()
                        state.error != null ->
                            ErrorState(state.error ?: stringResource(R.string.settings_unknown_error))
                        state.devices.isEmpty() ->
                            EmptyState(
                                stringResource(R.string.settings_devices_empty),
                                icon = Icons.Outlined.Devices,
                            )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(state.devices, key = { it.deviceId }) { device ->
                                DeviceRow(
                                    device = device,
                                    onRemove = { removeTarget = it },
                                    onShowKey = onShowKey,
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                HorizontalDivider()
                DevicePairingActions(onAddDevice = onAddDevice, onApproveDevice = onApproveDevice)
            }
        }
    }

    removeTarget?.let { device ->
        ConfirmDialog(
            title = stringResource(R.string.settings_device_remove_confirm_title, device.name),
            text = stringResource(R.string.settings_device_remove_confirm_body),
            confirmLabel = stringResource(R.string.settings_action_remove),
            onConfirm = {
                viewModel.removeDevice(device.deviceId)
                removeTarget = null
            },
            onDismiss = { removeTarget = null },
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

/** Pair another device to this account, or approve a device that is requesting to join. */
@Composable
private fun DevicePairingActions(onAddDevice: () -> Unit, onApproveDevice: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onAddDevice, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_add_device_action))
        }
        OutlinedButton(onClick = onApproveDevice, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_approve_device_action))
        }
    }
}

/** Prompts for the account passphrase when the server gated a device removal (428/403). */
@Composable
internal fun RemoveDevicePassphraseDialog(
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_enter_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_enter_passphrase_body))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.settings_passphrase_label)) },
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
                Text(stringResource(R.string.settings_action_remove))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

@Composable
private fun DeviceRow(device: UiDevice, onRemove: (UiDevice) -> Unit, onShowKey: () -> Unit) {
    DeviceListRow(
        title = device.name,
        device = device,
        trailing = {
            // Both actions are 48dp icon buttons, so they share a horizontal center; DeviceListRow
            // centers them vertically on the block.
            if (device.isCurrent) {
                // The current device cannot deregister itself here; instead its indicator is the tap
                // target to reveal this device's identity key (for importing it onto another device).
                IconButton(onClick = onShowKey) {
                    Icon(
                        Icons.Filled.Smartphone,
                        contentDescription = stringResource(R.string.settings_device_current_content_description),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                IconButton(onClick = { onRemove(device) }) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.settings_device_remove_content_description),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}
