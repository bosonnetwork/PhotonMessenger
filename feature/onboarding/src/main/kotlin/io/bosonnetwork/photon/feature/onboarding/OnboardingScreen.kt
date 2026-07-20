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

package io.bosonnetwork.photon.feature.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.photon.core.qr.QrScanner
import io.bosonnetwork.photon.core.qr.rememberQrBitmap

/** Welcome / OAuth onboarding (design spec screen 1, wireframe 1). */
@Composable
fun OnboardingScreen(
    onAuthenticated: () -> Unit,
    onHandoffProfile: (ProfileHandoff) -> Unit,
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

    // The signed-in account belongs to a different profile: hand off to the app to open it.
    LaunchedEffect(Unit) {
        viewModel.handoff.collect { onHandoffProfile(it) }
    }

    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.Authenticated) onAuthenticated()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
        ) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(text = "Photon", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Federated messaging powered by BosonNetwork",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        AnimatedContent(
            targetState = state.loading to state.step,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 16 })
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "onboarding-step",
        ) { (loading, step) ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    loading -> CircularProgressIndicator()

                    step == OnboardingStep.Server -> {
                        Text("Connect to your super node", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.serverUrl,
                            onValueChange = viewModel::onServerUrlChange,
                            label = { Text("Server URL") },
                            placeholder = { Text("https://your-node:9000") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        var showAdvanced by remember { mutableStateOf(state.directorNodeId.isNotBlank()) }
                        Spacer(Modifier.height(8.dp))
                        if (showAdvanced) {
                            OutlinedTextField(
                                value = state.directorNodeId,
                                onValueChange = viewModel::onDirectorNodeIdChange,
                                label = { Text("Server ID (advanced)") },
                                placeholder = { Text("Boson node id (for a self-signed server)") },
                                supportingText = {
                                    Text("Required to trust a self-signed HTTPS server; leave blank for a public CA.")
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            TextButton(onClick = { showAdvanced = true }) { Text("Advanced options") }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = viewModel::confirmServer,
                            enabled = state.serverUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Continue")
                        }
                    }

                    step == OnboardingStep.ChooseIdentity -> {
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
                        OutlinedButton(onClick = viewModel::chooseScanKey, modifier = Modifier.fillMaxWidth()) {
                            Text("Scan QR from another device")
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = viewModel::choosePasteKey, modifier = Modifier.fillMaxWidth()) {
                            Text("Enter key manually")
                        }
                    }

                    step == OnboardingStep.ScanKey ->
                        KeyScanStep(onScanned = viewModel::onKeyScanned, onCancel = viewModel::backToChoose)

                    step == OnboardingStep.PasteKey -> {
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

                    step == OnboardingStep.CreateProfile -> {
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
                        // Setting a passphrase only applies to permissionless account creation; the OAuth
                        // bind path manages account security differently, so hide it there.
                        if (state.creatingNewAccount) {
                            var showAdvanced by remember { mutableStateOf(state.createPassphrase.isNotBlank()) }
                            Spacer(Modifier.height(8.dp))
                            if (showAdvanced) {
                                OutlinedTextField(
                                    value = state.createPassphrase,
                                    onValueChange = viewModel::onCreatePassphraseChange,
                                    label = { Text("Passphrase (optional)") },
                                    supportingText = {
                                        Text("Protects sensitive actions like adding a device. You can set this later in Settings.")
                                    },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                TextButton(onClick = { showAdvanced = true }) { Text("Advanced options") }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = viewModel::completeProfile,
                            enabled = state.displayName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.creatingNewAccount) "Create account" else "Continue")
                        }
                    }

                    step == OnboardingStep.Passphrase -> {
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

                    step == OnboardingStep.BackupKey ->
                        state.backupKeyBase58?.let { key ->
                            BackupKeyStep(keyBase58 = key, onContinue = viewModel::continueFromBackup)
                        }

                    step == OnboardingStep.Solving -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Creating your account", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Running a one-time security check on this device. This can take a few " +
                                "seconds, and the time varies from run to run.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = viewModel::cancelSolving) { Text("Cancel") }
                    }

                    else -> {
                        // ChooseMethod hub: proof-of-work account creation is the primary, permissionless
                        // path; importing an existing identity and OAuth sign-in are secondary options.
                        Text("Set up your account", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        if (state.powAvailable) {
                            Button(onClick = viewModel::chooseCreateAccount, modifier = Modifier.fillMaxWidth()) {
                                Text("Create a new account")
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No sign-in needed. A quick one-time security check runs on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(20.dp))
                        }
                        Text(
                            "Already have an account?",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = viewModel::addFromAnotherDevice,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Add from another device")
                        }
                        if (state.providers.isNotEmpty()) {
                            Spacer(Modifier.height(20.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Other ways to sign in",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            state.providers.forEach { provider ->
                                OutlinedButton(
                                    onClick = { viewModel.onProviderSelected(provider) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Continue with ${provider.name}")
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = viewModel::editServer) {
                            Text("Change super node")
                        }
                    }
                }
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Post-create backup prompt: shown once, only when a brand-new identity key was generated (never on
 * import/restore). The key IS the account and cannot be recovered if lost, so it is offered as a QR and
 * as selectable/copyable base58 for the user to save before continuing.
 */
@Composable
private fun BackupKeyStep(keyBase58: String, onContinue: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }

    Text("Back up your identity key", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "This key is your BosonNetwork identity. It is the ONLY way to access and restore your account on " +
            "another device, and if you lose it no one can recover it for you. Save it somewhere safe and " +
            "private before continuing.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    rememberQrBitmap(keyBase58)?.let { qr ->
        Image(
            bitmap = qr,
            contentDescription = "Identity key QR",
            modifier = Modifier.size(220.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
    SelectionContainer {
        Text(
            text = keyBase58,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = {
            clipboard.setText(AnnotatedString(keyBase58))
            copied = true
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (copied) "Copied" else "Copy key")
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
        Text(
            "I have saved my identity key somewhere safe.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onContinue,
        enabled = acknowledged,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue")
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
