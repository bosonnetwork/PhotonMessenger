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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.bosonnetwork.photon.core.designsystem.component.LoadingState
import io.bosonnetwork.photon.core.qr.QrScanner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.photon.feature.settings.R

/**
 * "Approve a device" - scans a new device's pairing QR, confirms what is being authorized, then seals
 * the user key to it (spec 2.4, M6-4). Requires CAMERA permission, requested inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApproveDeviceScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    viewModel: ApproveDeviceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_approve_device_action)) },
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
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is ApproveDeviceUiState.Scanning -> {
                    if (hasCameraPermission) {
                        QrScanner(onScanned = viewModel::onScanned, modifier = Modifier.fillMaxSize())
                        Text(
                            stringResource(R.string.settings_approve_scan_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                        )
                    } else {
                        CenteredAction(
                            message = stringResource(R.string.settings_camera_permission_message),
                            actionLabel = stringResource(R.string.settings_camera_permission_action),
                            onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        )
                    }
                }

                is ApproveDeviceUiState.Loading ->
                    LoadingState(label = stringResource(R.string.settings_approve_loading_reading))

                is ApproveDeviceUiState.Approving ->
                    LoadingState(label = stringResource(R.string.settings_approve_loading_authorizing))

                is ApproveDeviceUiState.Confirm -> {
                    var passphrase by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = viewModel::rescan,
                        title = { Text(stringResource(R.string.settings_approve_confirm_title)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(
                                        R.string.settings_approve_confirm_body,
                                        s.info.deviceName,
                                        s.info.appName,
                                    ),
                                )
                                if (s.needsPassphrase) {
                                    OutlinedTextField(
                                        value = passphrase,
                                        onValueChange = { passphrase = it },
                                        label = { Text(stringResource(R.string.settings_passphrase_label)) },
                                        singleLine = true,
                                        isError = s.passphraseError != null,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (s.passphraseError != null) {
                                        Text(s.passphraseError, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.approve(passphrase.takeIf { s.needsPassphrase }) },
                                enabled = !s.needsPassphrase || passphrase.isNotBlank(),
                            ) { Text(stringResource(R.string.settings_approve_action)) }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::deny) { Text(stringResource(R.string.settings_deny_action)) }
                        },
                    )
                }

                is ApproveDeviceUiState.Done ->
                    CenteredAction(
                        message = if (s.approved) {
                            stringResource(R.string.settings_approve_done_approved)
                        } else {
                            stringResource(R.string.settings_approve_done_denied)
                        },
                        actionLabel = stringResource(R.string.settings_action_done),
                        onAction = onFinished,
                    )

                is ApproveDeviceUiState.Failed ->
                    CenteredAction(
                        message = s.message,
                        actionLabel = stringResource(R.string.settings_scan_again_action),
                        onAction = viewModel::rescan,
                    )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CenteredAction(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) { Text(actionLabel) }
    }
}
