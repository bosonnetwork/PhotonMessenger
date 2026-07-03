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

package io.photonmessenger.feature.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.photonmessenger.core.qr.QrScanner

/** Welcome / OAuth onboarding (design spec screen 1, wireframe 1). */
@Composable
fun OnboardingScreen(
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Open the Director authorize URL in a Custom Tab (no WebView; spec 4.8).
    LaunchedEffect(Unit) {
        viewModel.launchAuthUrl.collect { url ->
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        }
    }

    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.Authenticated) onAuthenticated()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "PhotonMessenger", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "decentralized messaging",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        when {
            state.loading -> CircularProgressIndicator()

            state.step == OnboardingStep.Server -> {
                Text("Connect to your server", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = viewModel::onServerUrlChange,
                    label = { Text("Server URL") },
                    placeholder = { Text("https://your-node:9000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = viewModel::confirmServer,
                    enabled = state.serverUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue")
                }
            }

            state.step == OnboardingStep.ChooseIdentity -> {
                Text("Set up your identity", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.allowCreateIdentity) {
                        "Create a new identity key, or bring an existing one from another device."
                    } else {
                        "This device needs your identity key. Import it from a device where you are " +
                            "already signed in."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                if (state.allowCreateIdentity) {
                    Button(onClick = viewModel::chooseCreateNew, modifier = Modifier.fillMaxWidth()) {
                        Text("Create new identity")
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Button(onClick = viewModel::chooseScanKey, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan QR from another device")
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::choosePasteKey, modifier = Modifier.fillMaxWidth()) {
                    Text("Enter key manually")
                }
            }

            state.step == OnboardingStep.ScanKey ->
                KeyScanStep(onScanned = viewModel::onKeyScanned, onCancel = viewModel::backToChoose)

            state.step == OnboardingStep.PasteKey -> {
                Text("Enter your identity key", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Paste your user private key (base58 or hex) from another device.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.keyInput,
                    onValueChange = viewModel::onKeyInputChange,
                    label = { Text("Private key") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = viewModel::importPastedKey,
                    enabled = state.keyInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import identity")
                }
                TextButton(onClick = viewModel::backToChoose) { Text("Back") }
            }

            state.step == OnboardingStep.CreateProfile -> {
                Text("Create your profile", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::onDisplayNameChange,
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.bio,
                    onValueChange = viewModel::onBioChange,
                    label = { Text("Bio (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = viewModel::completeProfile,
                    enabled = state.displayName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue")
                }
            }

            state.step == OnboardingStep.Passphrase -> {
                Text("Enter your account passphrase", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your account is protected by a passphrase. Enter it to register this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.passphraseInput,
                    onValueChange = viewModel::onPassphraseInputChange,
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = viewModel::submitPassphrase,
                    enabled = state.passphraseInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Register this device")
                }
            }

            else -> {
                state.providers.forEach { provider ->
                    Button(
                        onClick = { viewModel.onProviderSelected(provider) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue with ${provider.name}")
                    }
                    Spacer(Modifier.height(12.dp))
                }
                TextButton(onClick = viewModel::editServer) {
                    Text("Change server")
                }
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/** Camera step for scanning a raw identity-key QR from another device (O4). Requests CAMERA inline. */
@Composable
private fun KeyScanStep(onScanned: (String) -> Unit, onCancel: () -> Unit) {
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

    Text("Scan the identity QR", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    if (hasCameraPermission) {
        QrScanner(
            onScanned = onScanned,
            modifier = Modifier.fillMaxWidth().height(280.dp),
        )
    } else {
        Text(
            "Camera permission is needed to scan the QR.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
            Text("Grant camera access")
        }
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onCancel) { Text("Back") }
}
