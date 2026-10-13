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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.bosonnetwork.Id
import io.bosonnetwork.photon.core.designsystem.component.ActionRow
import io.bosonnetwork.photon.core.designsystem.component.ConfirmDialog
import io.bosonnetwork.photon.core.designsystem.component.LoadingState
import io.bosonnetwork.photon.core.designsystem.component.PageColumn
import io.bosonnetwork.photon.core.designsystem.component.PhotonAvatar
import io.bosonnetwork.photon.core.designsystem.component.RecoveryCodesDialog
import io.bosonnetwork.photon.core.designsystem.component.Section
import io.bosonnetwork.photon.core.designsystem.component.SectionCard
import io.bosonnetwork.photon.core.designsystem.component.cardItemColors
import io.bosonnetwork.photon.core.designsystem.component.passphraseProblem
import io.bosonnetwork.photon.core.designsystem.theme.brand
import io.bosonnetwork.photon.core.model.AppLanguage
import io.bosonnetwork.photon.core.model.NotificationPreferences
import io.bosonnetwork.photon.core.model.ThemeMode
import io.bosonnetwork.photon.core.qr.rememberQrBitmap
import io.bosonnetwork.photon.feature.settings.R
import io.bosonnetwork.photon.feature.settings.model.UiProfile
import io.bosonnetwork.photon.feature.settings.model.UiRecoveryMethods
import kotlinx.coroutines.launch

/**
 * Settings hub: profile, account and devices, appearance, notifications, security and about, as card
 * sections like Boson Identity's (design spec screen 6, 2.6, M6). The theme applies at once;
 * [onLanguageChange] persists the language and restarts the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentLanguage: AppLanguage,
    onOpenAccounts: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenDevices: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onShowIdentityKey: () -> Unit,
    onBackUpKey: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val backupPending by viewModel.backupPending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshBackupState()
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.signedOut.collect { onSignedOut() } }

    var editing by rememberSaveable { mutableStateOf(false) }
    var showSetPassphrase by rememberSaveable { mutableStateOf(false) }
    var showRemovePassphrase by rememberSaveable { mutableStateOf(false) }
    var showNewRecoveryCodes by rememberSaveable { mutableStateOf(false) }
    val recoveryCodes by viewModel.recoveryCodes.collectAsStateWithLifecycle()
    val card by viewModel.card.collectAsStateWithLifecycle()
    var showForgot by rememberSaveable { mutableStateOf(false) }
    var confirmPublishCard by rememberSaveable { mutableStateOf(false) }
    var confirmStopCard by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var showIdQr by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    var choosingLanguage by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.passphraseUpdated.collect {
            showSetPassphrase = false
            showRemovePassphrase = false
            showNewRecoveryCodes = false
        }
    }
    LaunchedEffect(Unit) {
        viewModel.openUrl.collect { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(it)) }
    }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.updateAvatar(uri.toString())
    }

    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = topBarScroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading && state.profile == null) {
            LoadingState(Modifier.padding(padding))
        } else {
            PageColumn(Modifier.padding(padding)) {
                if (backupPending) BackupReminder(onBackUpKey)
                SectionCard {
                    ProfileHeader(
                        profile = state.profile,
                        onChangePhoto = { pickAvatar.launch("image/*") },
                        onRemovePhoto = { viewModel.removeAvatar() },
                        onEdit = { editing = true },
                    )
                    ProfileCardRow(
                        card = card,
                        onPublish = { confirmPublishCard = true },
                        onStop = { confirmStopCard = true },
                        onUpdate = viewModel::publishCard,
                        onRetry = viewModel::checkCard,
                    )
                }

                Section(stringResource(R.string.settings_section_account)) {
                    state.profile?.let { profile ->
                        UserIdRow(
                            userId = profile.id,
                            snackbar = snackbar,
                            onShowQr = { showIdQr = true },
                            onRevealIdentityKey = onShowIdentityKey,
                        )
                    }
                    ActionRow(
                        Icons.Outlined.SwitchAccount,
                        stringResource(R.string.settings_accounts_title),
                        stringResource(R.string.settings_accounts_subtitle),
                        onClick = onOpenAccounts,
                    )
                    ActionRow(
                        Icons.Outlined.Devices,
                        stringResource(R.string.settings_devices_title),
                        stringResource(R.string.settings_devices_subtitle),
                        onClick = onOpenDevices,
                    )
                    ActionRow(
                        Icons.AutoMirrored.Outlined.Login,
                        stringResource(R.string.settings_sessions_title),
                        stringResource(R.string.settings_sessions_subtitle),
                        onClick = onOpenSessions,
                    )
                }

                Section(stringResource(R.string.settings_section_appearance)) {
                    ActionRow(
                        Icons.Outlined.Palette,
                        stringResource(R.string.settings_theme_title),
                        theme.mode.label(),
                        onClick = { choosingTheme = true },
                    )
                    ActionRow(
                        Icons.Outlined.Language,
                        stringResource(R.string.settings_language_title),
                        currentLanguage.label(),
                        onClick = { choosingLanguage = true },
                    )
                }

                Section(stringResource(R.string.settings_section_notifications)) {
                    NotificationSettings(
                        prefs = notifications,
                        onEnabledChange = viewModel::setNotificationsEnabled,
                        onPreviewChange = viewModel::setNotificationPreview,
                    )
                }

                // The account's passphrase is known only once the node answered.
                if (state.profile?.synced == true) {
                    Section(stringResource(R.string.settings_section_security)) {
                        PassphraseSection(
                            protected = state.profile?.passphraseProtected == true,
                            recovery = state.profile?.recovery,
                            onSet = { showSetPassphrase = true },
                            onRemove = { showRemovePassphrase = true },
                            onNewRecoveryCodes = { showNewRecoveryCodes = true },
                            onForgot = { showForgot = true },
                        )
                    }
                }

                Section(stringResource(R.string.settings_section_about)) {
                    AboutSection()
                }

                OutlinedButton(
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_sign_out_action), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (choosingTheme) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme_title),
            options = ThemeMode.entries,
            selected = theme.mode,
            label = { it.label() },
            onSelect = {
                choosingTheme = false
                viewModel.setThemeMode(it)
            },
            onDismiss = { choosingTheme = false },
        )
    }

    if (choosingLanguage) {
        ChoiceDialog(
            title = stringResource(R.string.settings_language_title),
            options = AppLanguage.selectable,
            selected = currentLanguage,
            label = { it.label() },
            note = stringResource(R.string.settings_language_restart),
            onSelect = {
                choosingLanguage = false
                if (it != currentLanguage) onLanguageChange(it)
            },
            onDismiss = { choosingLanguage = false },
        )
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

    if (showNewRecoveryCodes) {
        NewRecoveryCodesDialog(
            onDismiss = { showNewRecoveryCodes = false },
            onSubmit = { current -> viewModel.makeRecoveryCodes(current) },
        )
    }

    recoveryCodes?.let { codes ->
        RecoveryCodesDialog(
            codes = codes,
            userId = state.profile?.id.orEmpty(),
            onDone = viewModel::dismissRecoveryCodes,
        )
    }

    if (showForgot) {
        ForgotPassphraseDialog(
            recovery = state.profile?.recovery,
            onDismiss = { showForgot = false },
            onOpenPortal = {
                showForgot = false
                viewModel.openPortal()
            },
        )
    }

    if (confirmPublishCard) {
        ConfirmDialog(
            title = stringResource(R.string.settings_card_publish_title),
            text = stringResource(R.string.settings_card_publish_body),
            confirmLabel = stringResource(R.string.settings_card_publish_action),
            onConfirm = viewModel::publishCard,
            onDismiss = { confirmPublishCard = false },
        )
    }

    if (confirmStopCard) {
        ConfirmDialog(
            title = stringResource(R.string.settings_card_stop_title),
            text = stringResource(R.string.settings_card_stop_body),
            confirmLabel = stringResource(R.string.settings_card_stop_action),
            onConfirm = viewModel::unpublishCard,
            onDismiss = { confirmStopCard = false },
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
        colors = cardItemColors(),
        leadingContent = { Icon(Icons.Outlined.Badge, contentDescription = null) },
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
            Text(abbreviateId(userId), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            // The icon buttons' own 12 dp inset would set the QR code further in than the other rows'
            // end icons: reaching into the row's end padding lines it up, and leaves the id that much
            // more room on its line.
            Row(
                Modifier.layout { measurable, constraints ->
                    val reach = 12.dp.roundToPx()
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width - reach, placeable.height) { placeable.place(0, 0) }
                },
            ) {
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
                // The id as a W3C DID: what Boson apps read, and what any DID tool recognizes.
                rememberQrBitmap(remember(userId) { Id.of(userId).toDIDString() })?.let { qr ->
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

/** The app's name and version, and the Boson network's website. */
@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    ActionRow(
        Icons.Outlined.Info,
        stringResource(R.string.settings_about_app_name),
        stringResource(
            R.string.settings_about_version_line,
            version ?: stringResource(R.string.settings_about_version_unknown),
        ),
    )
    ActionRow(
        Icons.AutoMirrored.Outlined.OpenInNew,
        stringResource(R.string.settings_about_website),
        stringResource(R.string.settings_about_website_detail),
        onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)))
            } catch (e: ActivityNotFoundException) {
                // No browser: nothing to open it with.
            }
        },
    )
}

private const val WEBSITE = "https://bosonnetwork.io"

/** One of [options], picked with radio buttons; picking one closes the dialog. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.brand.card,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = option == selected, role = Role.RadioButton) { onSelect(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
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
    recovery: UiRecoveryMethods?,
    onSet: () -> Unit,
    onRemove: () -> Unit,
    onNewRecoveryCodes: () -> Unit,
    onForgot: () -> Unit,
) {
    val icon = if (protected) Icons.Filled.CheckCircle else Icons.Filled.Warning
    val tint = if (protected) MaterialTheme.brand.success else MaterialTheme.colorScheme.error
    ListItem(
        colors = cardItemColors(),
        leadingContent = { Icon(Icons.Outlined.Password, contentDescription = null) },
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
    if (protected) {
        // How a forgotten passphrase can be reset; with no way at all, say so plainly.
        if (recovery != null) {
            Text(
                if (recovery.any) {
                    stringResource(R.string.settings_recovery_codes_left, recovery.recoveryCodes)
                } else {
                    stringResource(R.string.settings_recovery_none_warning)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (recovery.any) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // One action a line; the buttons' own 12 dp inset puts their labels in line with the button above.
        Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) {
            TextButton(onClick = onNewRecoveryCodes) { Text(stringResource(R.string.settings_recovery_codes_new_action)) }
            TextButton(onClick = onForgot) { Text(stringResource(R.string.settings_forgot_action)) }
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
    val problem = passphraseProblem(new)

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
                if (new.isNotEmpty() && problem != null) {
                    Text(
                        stringResource(problem),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PassphraseField(confirm, { confirm = it }, stringResource(R.string.settings_passphrase_confirm_label))
                if (!protected) {
                    Text(
                        stringResource(R.string.settings_passphrase_codes_next),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                enabled = matches && currentOk && problem == null,
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
private fun NewRecoveryCodesDialog(
    onDismiss: () -> Unit,
    onSubmit: (current: String) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_recovery_codes_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_recovery_codes_new_body))
                PassphraseField(current, { current = it }, stringResource(R.string.settings_passphrase_current_label))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(current) }, enabled = current.isNotBlank()) {
                Text(stringResource(R.string.settings_recovery_codes_new_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

/**
 * A forgotten passphrase is reset on the node's web portal or in Boson Identity, with a recovery code, a
 * linked account or a passkey; the reset grant those win returns only to the Director's own site.
 */
@Composable
private fun ForgotPassphraseDialog(
    recovery: UiRecoveryMethods?,
    onDismiss: () -> Unit,
    onOpenPortal: () -> Unit,
) {
    // Unknown methods (an older Director): offer the portal and let it decide.
    val resettable = recovery == null || recovery.any
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_forgot_title)) },
        text = {
            Text(
                if (resettable) stringResource(R.string.settings_forgot_body)
                else stringResource(R.string.settings_forgot_no_method),
            )
        },
        confirmButton = {
            if (resettable) {
                TextButton(onClick = onOpenPortal) { Text(stringResource(R.string.settings_forgot_open_portal)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (resettable) R.string.settings_action_cancel else R.string.settings_action_close))
            }
        },
    )
}

/**
 * The public profile card: the profile as a Boson card anyone can look up by the id, published through
 * the home node. A switch turns it on and off; a card that no longer shows the profile offers an update.
 */
@Composable
private fun ProfileCardRow(
    card: ProfileCardUi,
    onPublish: () -> Unit,
    onStop: () -> Unit,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
) {
    val status = card.status
    val switchable = status != ProfileCardUi.Status.Unavailable && status != ProfileCardUi.Status.Unknown
    val enabled = switchable && status != ProfileCardUi.Status.Checking && !card.busy
    val toggle = { on: Boolean -> if (on) onPublish() else onStop() }
    val modifier = when {
        status == ProfileCardUi.Status.Unknown -> Modifier.clickable(role = Role.Button, onClick = onRetry)
        enabled -> Modifier.toggleable(value = card.published, role = Role.Switch, onValueChange = toggle)
        else -> Modifier
    }
    ListItem(
        colors = cardItemColors(),
        leadingContent = { Icon(Icons.Outlined.Public, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_card_title)) },
        supportingContent = {
            Text(
                stringResource(
                    when (status) {
                        ProfileCardUi.Status.Checking -> R.string.settings_card_checking
                        ProfileCardUi.Status.Off -> R.string.settings_card_off
                        ProfileCardUi.Status.On -> R.string.settings_card_on
                        ProfileCardUi.Status.Outdated -> R.string.settings_card_outdated
                        ProfileCardUi.Status.Unavailable -> R.string.settings_card_unavailable
                        ProfileCardUi.Status.Unknown -> R.string.settings_card_unknown
                    },
                ),
                color = if (status == ProfileCardUi.Status.Outdated) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = if (switchable) {
            { Switch(checked = card.published, onCheckedChange = null, enabled = enabled) }
        } else {
            null
        },
        modifier = modifier,
    )
    if (status == ProfileCardUi.Status.Outdated) {
        TextButton(
            onClick = onUpdate,
            enabled = !card.busy,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(R.string.settings_card_update)) }
    }
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
        // Changing the profile needs the node: until it answered, the profile as kept is only shown.
        val editable = profile?.synced == true
        val changePhotoContentDescription = stringResource(R.string.settings_change_photo_content_description)
        PhotonAvatar(
            model = profile?.avatar,
            name = profile?.name,
            size = 96.dp,
            contentDescription = changePhotoContentDescription,
            modifier = Modifier
                .clickable(enabled = editable, onClick = onChangePhoto)
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
            OutlinedButton(onClick = onEdit, enabled = editable) { Text(stringResource(R.string.settings_edit_profile_action)) }
            if (profile?.avatar != null) {
                TextButton(onClick = onRemovePhoto, enabled = editable) { Text(stringResource(R.string.settings_remove_photo_action)) }
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
        icon = Icons.Outlined.Notifications,
        title = stringResource(R.string.settings_notifications_enabled_title),
        subtitle = stringResource(R.string.settings_notifications_enabled_subtitle),
        checked = prefs.enabled,
        onCheckedChange = onEnabledChange,
    )
    if (prefs.enabled) {
        SwitchRow(
            icon = Icons.Outlined.Preview,
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
    Surface(
        color = MaterialTheme.brand.warning.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

/**
 * A settings toggle whose entire row is the touch target with `Role.Switch` semantics, so TalkBack
 * announces the label plus on/off state and the 48dp+ row (not just the switch thumb) is actionable
 * (X-A1). The [Switch] is presentational (`onCheckedChange = null`); the row drives the change.
 */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = cardItemColors(),
        modifier = Modifier.toggleable(
            value = checked,
            onValueChange = onCheckedChange,
            role = Role.Switch,
        ),
        leadingContent = { Icon(icon, contentDescription = null) },
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

// Each language is named in itself, so it is found whatever the app shows now.
@Composable
private fun AppLanguage.label(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
    AppLanguage.ENGLISH -> stringResource(R.string.settings_language_native_english)
    AppLanguage.SIMPLIFIED_CHINESE -> stringResource(R.string.settings_language_native_chinese)
}

/**
 * Shown while the backup of an identity made on this phone hasn't been checked: without a copy, losing
 * the phone loses the identity.
 */
@Composable
private fun BackupReminder(onBackUpKey: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.settings_backup_reminder_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_backup_reminder_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBackUpKey) { Text(stringResource(R.string.settings_backup_reminder_action)) }
        }
    }
}
