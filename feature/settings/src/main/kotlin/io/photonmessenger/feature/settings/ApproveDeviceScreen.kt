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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.photonmessenger.core.designsystem.component.LoadingState
import io.photonmessenger.core.qr.QrScanner
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
                title = { Text("Approve a device") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                            "Point the camera at the QR shown on the new device",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                        )
                    } else {
                        CenteredAction(
                            message = "Camera access is needed to scan the pairing code.",
                            actionLabel = "Grant camera access",
                            onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        )
                    }
                }

                is ApproveDeviceUiState.Loading ->
                    LoadingState(label = "Reading pairing request...")

                is ApproveDeviceUiState.Approving ->
                    LoadingState(label = "Authorizing device...")

                is ApproveDeviceUiState.Confirm -> {
                    var passphrase by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = viewModel::rescan,
                        title = { Text("Authorize this device?") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "\"${s.info.deviceName}\" (${s.info.appName}) wants to sign in to your " +
                                        "account. Approving shares your encrypted identity key with it.",
                                )
                                if (s.needsPassphrase) {
                                    OutlinedTextField(
                                        value = passphrase,
                                        onValueChange = { passphrase = it },
                                        label = { Text("Passphrase") },
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
                            ) { Text("Approve") }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::deny) { Text("Deny") }
                        },
                    )
                }

                is ApproveDeviceUiState.Done ->
                    CenteredAction(
                        message = if (s.approved) "Device approved." else "Request denied.",
                        actionLabel = "Done",
                        onAction = onFinished,
                    )

                is ApproveDeviceUiState.Failed ->
                    CenteredAction(
                        message = s.message,
                        actionLabel = "Scan again",
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
