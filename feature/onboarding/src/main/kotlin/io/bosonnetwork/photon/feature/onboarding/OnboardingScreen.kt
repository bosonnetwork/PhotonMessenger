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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.photon.core.designsystem.component.SecureScreen
import io.bosonnetwork.photon.core.qr.QrScanner
import io.bosonnetwork.photon.core.qr.rememberQrBitmap

/**
 * Bringing an identity into Photon, identity first: a new one, or one the user already has (see
 * [OnboardingStep]).
 */
@Composable
fun OnboardingScreen(
    onAuthenticated: () -> Unit,
    onHandoffProfile: (ProfileHandoff) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opens the returning-user account picker. Non-null only when at least one signed-in-before profile
     * exists on this device; null hides the affordance entirely (nothing to sign back into).
     */
    onOpenExistingAccounts: (() -> Unit)? = null,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The identity belongs to a different profile: hand off to the app to open it.
    LaunchedEffect(Unit) {
        viewModel.handoff.collect { onHandoffProfile(it) }
    }

    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.Authenticated) onAuthenticated()
    }

    BackHandler(enabled = viewModel.canGoBack() && !state.loading) { viewModel.back() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = state.loading to state.step,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 16 })
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "onboarding-step",
            ) { (loading, step) ->
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (loading) {
                        CircularProgressIndicator()
                    } else {
                        Step(step, state, viewModel)
                    }
                }
            }

            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Text(text = error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }

        if (viewModel.canGoBack() && !state.loading) {
            IconButton(onClick = viewModel::back, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onb_back))
            }
        }

        // Returning-user shortcut: only on the welcome screen, and only when the app reports existing
        // accounts to sign back into (sign-out keeps their keys/data, just ends the session).
        if (onOpenExistingAccounts != null && state.step == OnboardingStep.Welcome) {
            IconButton(
                onClick = onOpenExistingAccounts,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.SwitchAccount, contentDescription = stringResource(R.string.onb_use_existing_account))
            }
        }
    }
}

@Composable
private fun Step(step: OnboardingStep, state: OnboardingUiState, viewModel: OnboardingViewModel) {
    when (step) {
        OnboardingStep.Welcome -> WelcomeStep(onCreate = viewModel::chooseCreate, onExisting = viewModel::chooseExisting)

        OnboardingStep.NewIdentity ->
            state.newUserId?.let { NewIdentityStep(it, onContinue = viewModel::continueToBackup) }

        OnboardingStep.BackupKey -> {
            val key = state.backupKeyBase58
            val userId = state.newUserId
            if (key != null && userId != null)
                BackupKeyStep(key, userId, onContinue = viewModel::continueFromBackup)
        }

        OnboardingStep.CheckBackup ->
            state.challenge?.let { challenge ->
                CheckBackupStep(
                    challenge = challenge,
                    failed = state.checkFailed,
                    onCheck = viewModel::checkBackup,
                    onShowKeyAgain = viewModel::showKeyAgain,
                    onSkip = viewModel::checkLater,
                )
            }

        OnboardingStep.Server -> ServerStep(state, viewModel)

        OnboardingStep.ScanServer ->
            CameraScanStep(
                title = stringResource(R.string.onb_scan_super_node_title),
                description = stringResource(
                    if (state.existing) R.string.onb_scan_home_node_desc else R.string.onb_scan_super_node_desc,
                ),
                onScanned = viewModel::onServerQrScanned,
            )

        OnboardingStep.CreateProfile -> {
            StepTitle(stringResource(R.string.onb_create_profile_title))
            StepText(stringResource(R.string.onb_create_profile_body))
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                label = { Text(stringResource(R.string.onb_display_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.bio,
                onValueChange = viewModel::onBioChange,
                label = { Text(stringResource(R.string.onb_bio_label)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::createAccount,
                enabled = state.displayName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onb_create_account))
            }
        }

        OnboardingStep.Solving -> {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            StepTitle(stringResource(R.string.onb_solving_title))
            StepText(stringResource(R.string.onb_solving_desc))
            TextButton(onClick = viewModel::cancelSolving) { Text(stringResource(R.string.onb_cancel)) }
        }

        OnboardingStep.ChooseWay -> {
            StepTitle(stringResource(R.string.onb_choose_way_title))
            StepText(stringResource(R.string.onb_choose_way_body))
            WayCard(
                icon = Icons.Filled.Devices,
                title = stringResource(R.string.onb_way_pair_title),
                body = stringResource(R.string.onb_way_pair_body),
                onClick = viewModel::choosePairing,
            )
            Spacer(Modifier.height(12.dp))
            WayCard(
                icon = Icons.Filled.Key,
                title = stringResource(R.string.onb_way_import_title),
                body = stringResource(R.string.onb_way_import_body),
                onClick = viewModel::chooseImport,
            )
        }

        OnboardingStep.Pairing -> PairingStep(state, viewModel)

        OnboardingStep.ImportBackup -> ImportBackupStep(state, viewModel)

        OnboardingStep.ScanBackup -> {
            SecureScreen()
            CameraScanStep(
                title = stringResource(R.string.onb_scan_backup_title),
                description = stringResource(R.string.onb_scan_backup_desc),
                onScanned = viewModel::onBackupScanned,
            )
        }

        OnboardingStep.Passphrase -> {
            StepTitle(stringResource(R.string.onb_passphrase_title))
            StepText(stringResource(R.string.onb_passphrase_desc))
            OutlinedTextField(
                value = state.passphraseInput,
                onValueChange = viewModel::onPassphraseInputChange,
                label = { Text(stringResource(R.string.onb_passphrase_label)) },
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
                Text(stringResource(R.string.onb_register_device))
            }
        }

        OnboardingStep.Authenticated -> CircularProgressIndicator()
    }
}

@Composable
private fun WelcomeStep(onCreate: () -> Unit, onExisting: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
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
    Text(text = stringResource(R.string.onb_welcome_title), style = MaterialTheme.typography.headlineMedium)
    Text(
        text = stringResource(R.string.onb_welcome_tagline),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(32.dp))
    StepText(stringResource(R.string.onb_welcome_body))
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_welcome_create))
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onExisting, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_welcome_existing))
    }
}

@Composable
private fun ServerStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepTitle(stringResource(if (state.existing) R.string.onb_home_node_existing_title else R.string.onb_home_node_new_title))
    StepText(stringResource(if (state.existing) R.string.onb_home_node_existing_body else R.string.onb_home_node_new_body))
    OutlinedButton(onClick = viewModel::scanServerQr, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
        Text(stringResource(R.string.onb_scan_super_node), Modifier.padding(start = 8.dp))
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = viewModel::onServerUrlChange,
        label = { Text(stringResource(R.string.onb_server_url_label)) },
        placeholder = { Text(stringResource(R.string.onb_server_url_placeholder)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    var showAdvanced by remember { mutableStateOf(state.directorNodeId.isNotBlank()) }
    Spacer(Modifier.height(8.dp))
    if (showAdvanced) {
        OutlinedTextField(
            value = state.directorNodeId,
            onValueChange = viewModel::onDirectorNodeIdChange,
            label = { Text(stringResource(R.string.onb_server_id_label)) },
            placeholder = { Text(stringResource(R.string.onb_server_id_placeholder)) },
            supportingText = { Text(stringResource(R.string.onb_server_id_supporting_text)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        TextButton(onClick = { showAdvanced = true }) { Text(stringResource(R.string.onb_advanced_options)) }
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = viewModel::confirmServer,
        enabled = state.serverUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onb_continue))
    }
}

@Composable
private fun WayCard(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * This device's pairing code, for Boson Identity or Photon on another device to scan and approve, and
 * the wait for that answer. The code itself holds no secret: only a device that holds the identity can
 * approve it.
 */
@Composable
private fun PairingStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepTitle(stringResource(R.string.onb_pairing_title))
    val code = state.pairingCode
    when {
        code != null -> {
            StepText(stringResource(R.string.onb_pairing_body))
            rememberQrBitmap(code)?.let {
                Image(it, contentDescription = stringResource(R.string.onb_pairing_qr_description), modifier = Modifier.size(240.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.onb_pairing_waiting), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onb_pairing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        state.pairingExpired -> {
            StepText(stringResource(R.string.onb_pairing_expired))
            Button(onClick = viewModel::choosePairing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onb_pairing_new_code))
            }
        }
        state.error != null ->
            Button(onClick = viewModel::choosePairing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onb_try_again))
            }
        else -> CircularProgressIndicator()
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = viewModel::cancelPairing) { Text(stringResource(R.string.onb_cancel)) }
}

/**
 * Bringing in a backup: the key file (opened through the system's file picker), its QR code, or the key
 * typed or pasted, with or without the row numbers of its printed form. Kept out of screenshots.
 */
@Composable
private fun ImportBackupStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    SecureScreen()
    val context = LocalContext.current
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    // A key file is a few hundred bytes; anything far larger is not one.
                    String(input.readNBytesCompat(MAX_KEY_FILE_BYTES), Charsets.UTF_8)
                }
            }.getOrNull()
            viewModel.onBackupFileOpened(text.orEmpty())
        }
    }

    StepTitle(stringResource(R.string.onb_import_title))
    StepText(stringResource(R.string.onb_import_body))
    OutlinedButton(onClick = { openFile.launch(arrayOf("text/*", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.FileOpen, contentDescription = null)
        Text(stringResource(R.string.onb_import_open_file), Modifier.padding(start = 8.dp))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = viewModel::scanBackup, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
        Text(stringResource(R.string.onb_import_scan), Modifier.padding(start = 8.dp))
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = state.backupInput,
        onValueChange = viewModel::onBackupInputChange,
        label = { Text(stringResource(R.string.onb_private_key_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = viewModel::importTyped,
        enabled = state.backupInput.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onb_import_identity))
    }
}

/**
 * Camera step that decodes a QR code and hands each raw payload to [onScanned]. Requests CAMERA inline
 * and shows a rationale plus a grant button when the permission is denied. Back returns to the step
 * before (the screen's back arrow and the system back gesture).
 */
@Composable
private fun CameraScanStep(title: String, description: String?, onScanned: (String) -> Unit) {
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

    StepTitle(title)
    if (description != null)
        StepText(description)
    if (hasCameraPermission) {
        QrScanner(onScanned = onScanned, modifier = Modifier.fillMaxWidth().height(280.dp))
    } else {
        StepText(stringResource(R.string.onb_camera_permission_needed))
        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
            Text(stringResource(R.string.onb_grant_camera_access))
        }
    }
}

private const val MAX_KEY_FILE_BYTES = 16 * 1024

// InputStream.readNBytes(int) is Java 11 (Android API 33); read in a loop so the bound holds anywhere.
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (out.size() < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
