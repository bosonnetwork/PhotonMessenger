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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.photon.core.designsystem.component.ConfirmDialog
import io.bosonnetwork.photon.core.designsystem.component.LoadingState
import io.bosonnetwork.photon.core.designsystem.component.PhotonAvatar
import io.bosonnetwork.photon.core.designsystem.component.ResponsiveContent
import io.bosonnetwork.photon.core.model.AppLanguage
import io.bosonnetwork.photon.core.model.NotificationPreferences
import io.bosonnetwork.photon.core.model.ThemeMode
import io.bosonnetwork.photon.core.qr.rememberQrBitmap
import io.bosonnetwork.photon.feature.settings.R
import io.bosonnetwork.photon.feature.settings.model.UiProfile
import kotlinx.coroutines.launch

/** Settings hub: profile, appearance, devices, account (design spec screen 6, 2.6, M6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentLanguage: AppLanguage,
    onOpenAccounts: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenLanguage: () -> Unit,
    onShowIdentityKey: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.signedOut.collect { onSignedOut() } }

    var editing by rememberSaveable { mutableStateOf(false) }
    var showSetPassphrase by rememberSaveable { mutableStateOf(false) }
    var showRemovePassphrase by rememberSaveable { mutableStateOf(false) }
    var showIdQr by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.passphraseUpdated.collect {
            showSetPassphrase = false
            showRemovePassphrase = false
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.updateAvatar(uri.toString())
    }

    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, scrollBehavior = topBarScroll) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ResponsiveContent(modifier = Modifier.padding(padding)) {
            when {
                state.loading && state.profile == null -> LoadingState()

                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    ProfileHeader(
                        profile = state.profile,
                        onChangePhoto = { pickAvatar.launch("image/*") },
                        onRemovePhoto = { viewModel.removeAvatar() },
                        onEdit = { editing = true },
                    )
                    HorizontalDivider()

                    state.profile?.let { profile ->
                        SectionTitle(stringResource(R.string.settings_section_account))
                        UserIdRow(
                            userId = profile.id,
                            snackbar = snackbar,
                            onShowQr = { showIdQr = true },
                            onRevealIdentityKey = onShowIdentityKey,
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.settings_accounts_title)) },
                            supportingContent = { Text(stringResource(R.string.settings_accounts_subtitle)) },
                            modifier = Modifier.clickable(role = Role.Button, onClick = onOpenAccounts),
                        )
                        HorizontalDivider()
                    }

                    SectionTitle(stringResource(R.string.settings_section_appearance))
                    ThemeModeRow(theme.mode, viewModel::setThemeMode)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(
                            title = stringResource(R.string.settings_dynamic_color_title),
                            subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                            checked = theme.dynamicColor,
                            onCheckedChange = viewModel::setDynamicColor,
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_language_title)) },
                        supportingContent = { Text(currentLanguage.displayName()) },
                        modifier = Modifier.clickable(role = Role.Button, onClick = onOpenLanguage),
                    )
                    HorizontalDivider()

                    SectionTitle(stringResource(R.string.settings_section_notifications))
                    NotificationSettings(
                        prefs = notifications,
                        onEnabledChange = viewModel::setNotificationsEnabled,
                        onPreviewChange = viewModel::setNotificationPreview,
                    )
                    HorizontalDivider()

                    SectionTitle(stringResource(R.string.settings_section_security))
                    PassphraseSection(
                        protected = state.profile?.passphraseProtected == true,
                        onSet = { showSetPassphrase = true },
                        onRemove = { showRemovePassphrase = true },
                    )
                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_sessions_title)) },
                        supportingContent = { Text(stringResource(R.string.settings_sessions_subtitle)) },
                        modifier = Modifier.clickable(role = Role.Button, onClick = onOpenSessions),
                    )
                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_devices_title)) },
                        supportingContent = { Text(stringResource(R.string.settings_devices_subtitle)) },
                        modifier = Modifier.clickable(role = Role.Button, onClick = onOpenDevices),
                    )
                    HorizontalDivider()

                    AboutSection()
                    HorizontalDivider()

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmSignOut = true },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text(stringResource(R.string.settings_sign_out_action), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (editing) {
        EditProfileDialog(
            profile = state.profile,
            saving = state.savingProfile,
            requirePassphrase = state.profile?.passphraseProtected == true,
            onDismiss = { editing = false },
            onSave = { name, bio, email, passphrase ->
                viewModel.saveProfile(name, bio, email, passphrase)
                editing = false
            },
        )
    }

    if (showSetPassphrase) {
        SetPassphraseDialog(
            protected = state.profile?.passphraseProtected == true,
            onDismiss = { showSetPassphrase = false },
            onSubmit = { current, new -> viewModel.setPassphrase(new, current) },
        )
    }

    if (showRemovePassphrase) {
        RemovePassphraseDialog(
            onDismiss = { showRemovePassphrase = false },
            onSubmit = { current -> viewModel.clearPassphrase(current) },
        )
    }

    if (showIdQr) {
        state.profile?.let { profile ->
            UserIdQrDialog(
                userId = profile.id,
                name = profile.name,
                onDismiss = { showIdQr = false },
            )
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = stringResource(R.string.settings_sign_out_confirm_title),
            text = stringResource(R.string.settings_sign_out_confirm_body),
            confirmLabel = stringResource(R.string.settings_sign_out_action),
            onConfirm = { viewModel.signOut() },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/**
 * The user's own Boson ID - the value friends need to send a friend request - with one-tap copy
 * and a QR for in-person sharing. This is the public identity, safe to show and share.
 */
@Composable
private fun UserIdRow(
    userId: String,
    snackbar: SnackbarHostState,
    onShowQr: () -> Unit,
    onRevealIdentityKey: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bosonIdCopiedMessage = stringResource(R.string.settings_boson_id_copied_message)

    // Hidden gesture, in the spirit of Android's "tap Build number 7 times": tapping the row body 7
    // times in quick succession unlocks the identity-key screen (which is itself reveal-gated). The
    // trailing copy/QR icon buttons keep their own single-tap actions, so nothing is lost.
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    ListItem(
        modifier = Modifier.clickable(role = Role.Button) {
            val now = System.currentTimeMillis()
            tapCount = if (now - lastTapAt > IDENTITY_KEY_TAP_WINDOW_MS) 1 else tapCount + 1
            lastTapAt = now
            val remaining = IDENTITY_KEY_TAP_COUNT - tapCount
            if (remaining <= 0) {
                tapCount = 0
                onRevealIdentityKey()
            } else if (remaining <= IDENTITY_KEY_TAP_COUNTDOWN_FROM) {
                scope.launch {
                    // Dismiss the previous hint so the countdown updates promptly (toast-like).
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(
                        context.resources.getQuantityString(
                            R.plurals.settings_identity_key_taps_remaining,
                            remaining,
                            remaining,
                        ),
                    )
                }
            }
        },
        headlineContent = { Text(stringResource(R.string.settings_boson_id_title)) },
        supportingContent = {
            // A full base58 id (~44 chars) wraps onto multiple lines even at a small size, so show an
            // abbreviated first-8...last-8 form on a single line, in the same style as the other
            // setting descriptions. The full id stays available via copy and the QR dialog.
            Text(abbreviateId(userId))
        },
        trailingContent = {
            Row {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(userId))
                    scope.launch { snackbar.showSnackbar(bosonIdCopiedMessage) }
                }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.settings_boson_id_copy_content_description),
                    )
                }
                IconButton(onClick = onShowQr) {
                    Icon(
                        Icons.Filled.QrCode2,
                        contentDescription = stringResource(R.string.settings_boson_id_qr_content_description),
                    )
                }
            }
        },
    )
}

// The hidden identity-key gesture: 7 quick taps on the Boson ID row, hinting for the final few.
private const val IDENTITY_KEY_TAP_COUNT = 7
private const val IDENTITY_KEY_TAP_COUNTDOWN_FROM = 3
private const val IDENTITY_KEY_TAP_WINDOW_MS = 2000L

/**
 * Abbreviates a long id to "first8...last8" so it fits on one line; short ids are returned as-is.
 */
private fun abbreviateId(id: String): String =
    if (id.length > 20) "${id.take(8)}...${id.takeLast(8)}" else id

@Composable
private fun UserIdQrDialog(userId: String, name: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_my_id_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(
                        R.string.settings_my_id_message,
                        name.ifBlank { stringResource(R.string.settings_my_id_fallback_name) },
                    ),
                )
                rememberQrBitmap(userId)?.let { qr ->
                    Image(
                        bitmap = qr,
                        contentDescription = stringResource(R.string.settings_my_id_qr_content_description),
                        modifier = Modifier.size(220.dp),
                    )
                }
                SelectionContainer {
                    // fillMaxWidth so that when the id wraps, every line stays centered within the
                    // dialog width rather than the wrapped lines collapsing to left-aligned.
                    Text(
                        userId,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_done)) } },
    )
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    SectionTitle(stringResource(R.string.settings_section_about))
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_about_app_name)) },
        supportingContent = {
            Text(
                stringResource(
                    R.string.settings_about_version_line,
                    version ?: stringResource(R.string.settings_about_version_unknown),
                ),
            )
        },
    )
}

/**
 * Account security section (Director Passphrase Management). Shows a warning when no passphrase is
 * configured (prompting the user to add one) and a success indicator when one is set, with change/
 * remove actions. Mirrors the User Portal indicator behavior.
 */
@Composable
private fun PassphraseSection(
    protected: Boolean,
    onSet: () -> Unit,
    onRemove: () -> Unit,
) {
    val icon = if (protected) Icons.Filled.CheckCircle else Icons.Filled.Warning
    val tint = if (protected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    ListItem(
        headlineContent = {
            Text(
                if (protected) {
                    stringResource(R.string.settings_passphrase_protected_title)
                } else {
                    stringResource(R.string.settings_passphrase_unset_title)
                },
            )
        },
        supportingContent = {
            Text(
                if (protected) {
                    stringResource(R.string.settings_passphrase_protected_subtitle)
                } else {
                    stringResource(R.string.settings_passphrase_unset_subtitle)
                },
            )
        },
        trailingContent = { Icon(icon, contentDescription = null, tint = tint) },
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onSet) {
            Text(
                if (protected) {
                    stringResource(R.string.settings_passphrase_change_action)
                } else {
                    stringResource(R.string.settings_passphrase_set_action)
                },
            )
        }
        if (protected) {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.settings_action_remove)) }
        }
    }
}

@Composable
private fun SetPassphraseDialog(
    protected: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (current: String?, new: String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val matches = new.isNotBlank() && new == confirm
    val currentOk = !protected || current.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (protected) {
                    stringResource(R.string.settings_passphrase_change_action)
                } else {
                    stringResource(R.string.settings_passphrase_set_action)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (protected) {
                    PassphraseField(current, { current = it }, stringResource(R.string.settings_passphrase_current_label))
                }
                PassphraseField(new, { new = it }, stringResource(R.string.settings_passphrase_new_label))
                PassphraseField(confirm, { confirm = it }, stringResource(R.string.settings_passphrase_confirm_label))
                if (confirm.isNotBlank() && !matches) {
                    Text(
                        stringResource(R.string.settings_passphrase_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(current.takeIf { protected }, new) },
                enabled = matches && currentOk,
            ) { Text(stringResource(R.string.settings_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

@Composable
private fun RemovePassphraseDialog(
    onDismiss: () -> Unit,
    onSubmit: (current: String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_passphrase_remove_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_passphrase_remove_body))
                PassphraseField(current, { current = it }, stringResource(R.string.settings_passphrase_current_label))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(current) }, enabled = current.isNotBlank()) {
                Text(stringResource(R.string.settings_action_remove))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

@Composable
private fun PassphraseField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProfileHeader(
    profile: UiProfile?,
    onChangePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val changePhotoContentDescription = stringResource(R.string.settings_change_photo_content_description)
        PhotonAvatar(
            model = profile?.avatarUrl,
            name = profile?.name,
            size = 96.dp,
            contentDescription = changePhotoContentDescription,
            modifier = Modifier
                .clickable(onClick = onChangePhoto)
                .semantics { contentDescription = changePhotoContentDescription },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = profile?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_no_name_set),
            style = MaterialTheme.typography.titleLarge,
        )
        profile?.email?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        profile?.bio?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        profile?.plan?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_plan_line, it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.settings_edit_profile_action)) }
            if (profile?.avatarUrl != null) {
                TextButton(onClick = onRemovePhoto) { Text(stringResource(R.string.settings_remove_photo_action)) }
            }
        }
    }
}

@Composable
private fun NotificationSettings(
    prefs: NotificationPreferences,
    onEnabledChange: (Boolean) -> Unit,
    onPreviewChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    // Re-read OS-level grant/exemption state whenever we return to this screen.
    var refreshKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshKey++ }

    val notificationsAllowed = remember(refreshKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    val batteryUnrestricted = remember(refreshKey) {
        val pm = context.getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshKey++ }

    SwitchRow(
        title = stringResource(R.string.settings_notifications_enabled_title),
        subtitle = stringResource(R.string.settings_notifications_enabled_subtitle),
        checked = prefs.enabled,
        onCheckedChange = onEnabledChange,
    )
    if (prefs.enabled) {
        SwitchRow(
            title = stringResource(R.string.settings_notifications_preview_title),
            subtitle = stringResource(R.string.settings_notifications_preview_subtitle),
            checked = prefs.showPreview,
            onCheckedChange = onPreviewChange,
        )
        if (!notificationsAllowed) {
            GuidanceCard(
                title = stringResource(R.string.settings_notifications_off_title),
                body = stringResource(R.string.settings_notifications_off_body),
                action = stringResource(R.string.settings_notifications_allow_action),
                onClick = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
        if (!batteryUnrestricted) {
            GuidanceCard(
                title = stringResource(R.string.settings_battery_title),
                body = stringResource(R.string.settings_battery_body),
                action = stringResource(R.string.settings_open_settings_action),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun GuidanceCard(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeRow(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val modes = ThemeMode.entries
    // M3 single-choice button group: one connected control for the mutually-exclusive theme options,
    // with the selected segment marked (check) - clearer as a single selector than separate chips.
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(mode.label()) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * A settings toggle whose entire row is the touch target with `Role.Switch` semantics, so TalkBack
 * announces the label plus on/off state and the 48dp+ row (not just the switch thumb) is actionable
 * (X-A1). The [Switch] is presentational (`onCheckedChange = null`); the row drives the change.
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(
            value = checked,
            onValueChange = onCheckedChange,
            role = Role.Switch,
        ),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun EditProfileDialog(
    profile: UiProfile?,
    saving: Boolean,
    requirePassphrase: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, bio: String, email: String, passphrase: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(profile?.name.orEmpty()) }
    var bio by rememberSaveable { mutableStateOf(profile?.bio.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(profile?.email.orEmpty()) }
    var passphrase by rememberSaveable { mutableStateOf("") }
    val passphraseOk = !requirePassphrase || passphrase.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_edit_profile_action)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.settings_email_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text(stringResource(R.string.settings_bio_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (requirePassphrase) {
                    PassphraseField(passphrase, { passphrase = it }, stringResource(R.string.settings_passphrase_label))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), bio.trim(), email.trim(), passphrase.takeIf { requirePassphrase }) },
                enabled = !saving && passphraseOk,
            ) { Text(stringResource(R.string.settings_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

@Composable
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
}
