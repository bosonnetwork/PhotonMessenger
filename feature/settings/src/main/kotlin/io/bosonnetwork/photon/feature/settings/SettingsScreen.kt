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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import io.bosonnetwork.photon.core.model.NotificationPreferences
import io.bosonnetwork.photon.core.model.ThemeMode
import io.bosonnetwork.photon.core.qr.rememberQrBitmap
import io.bosonnetwork.photon.feature.settings.model.UiProfile
import kotlinx.coroutines.launch

/** Settings hub: profile, appearance, devices, account (design spec screen 6, 2.6, M6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenSessions: () -> Unit,
    onOpenDevices: () -> Unit,
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
        topBar = { TopAppBar(title = { Text("Settings") }, scrollBehavior = topBarScroll) },
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
                        SectionTitle("Account")
                        UserIdRow(
                            userId = profile.id,
                            snackbar = snackbar,
                            onShowQr = { showIdQr = true },
                        )
                        HorizontalDivider()
                    }

                    SectionTitle("Appearance")
                    ThemeModeRow(theme.mode, viewModel::setThemeMode)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(
                            title = "Dynamic color",
                            subtitle = "Use colors from your wallpaper",
                            checked = theme.dynamicColor,
                            onCheckedChange = viewModel::setDynamicColor,
                        )
                    }
                    HorizontalDivider()

                    SectionTitle("Notifications")
                    NotificationSettings(
                        prefs = notifications,
                        onEnabledChange = viewModel::setNotificationsEnabled,
                        onPreviewChange = viewModel::setNotificationPreview,
                    )
                    HorizontalDivider()

                    SectionTitle("Security")
                    PassphraseSection(
                        protected = state.profile?.passphraseProtected == true,
                        onSet = { showSetPassphrase = true },
                        onRemove = { showRemovePassphrase = true },
                    )
                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("Devices & sessions") },
                        supportingContent = { Text("Manage where you're signed in") },
                        modifier = Modifier.clickable(role = Role.Button, onClick = onOpenSessions),
                    )
                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("Registered devices") },
                        supportingContent = { Text("Manage devices registered to your account") },
                        modifier = Modifier.clickable(role = Role.Button, onClick = onOpenDevices),
                    )
                    HorizontalDivider()

                    AboutSection()
                    HorizontalDivider()

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmSignOut = true },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
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
            title = "Sign out?",
            text = "Signing out removes your identity key from this device. Make sure it is " +
                "available on another device (or exported via Devices > Show my key), or you " +
                "will permanently lose access to this identity.",
            confirmLabel = "Sign out",
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
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onShowQr),
        headlineContent = { Text("User ID") },
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
                    scope.launch { snackbar.showSnackbar("User ID copied") }
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy user ID")
                }
                IconButton(onClick = onShowQr) {
                    Icon(Icons.Filled.QrCode2, contentDescription = "Show user ID as QR")
                }
            }
        },
    )
}

/**
 * Abbreviates a long id to "first8...last8" so it fits on one line; short ids are returned as-is.
 */
private fun abbreviateId(id: String): String =
    if (id.length > 20) "${id.take(8)}...${id.takeLast(8)}" else id

@Composable
private fun UserIdQrDialog(userId: String, name: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My ID") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Friends scan or type this ID to add ${name.ifBlank { "you" }}. " +
                        "It is public and safe to share.",
                )
                rememberQrBitmap(userId)?.let { qr ->
                    Image(
                        bitmap = qr,
                        contentDescription = "User ID QR code",
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }
    SectionTitle("About")
    ListItem(
        headlineContent = { Text("Photon") },
        supportingContent = { Text("Version $version - decentralized messaging on Boson") },
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
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = {
            Text(if (protected) "Protected by a passphrase" else "No passphrase set")
        },
        supportingContent = {
            Text(
                if (protected) {
                    "A passphrase is required to change sensitive account settings."
                } else {
                    "Add a passphrase to protect sensitive account changes even if your key is exposed."
                },
            )
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onSet) { Text(if (protected) "Change passphrase" else "Set passphrase") }
        if (protected) {
            TextButton(onClick = onRemove) { Text("Remove") }
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
        title = { Text(if (protected) "Change passphrase" else "Set passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (protected) {
                    PassphraseField(current, { current = it }, "Current passphrase")
                }
                PassphraseField(new, { new = it }, "New passphrase")
                PassphraseField(confirm, { confirm = it }, "Confirm new passphrase")
                if (confirm.isNotBlank() && !matches) {
                    Text(
                        "Passphrases don't match",
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
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        title = { Text("Remove passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter your current passphrase to remove it. Sensitive changes will no longer be protected.")
                PassphraseField(current, { current = it }, "Current passphrase")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(current) }, enabled = current.isNotBlank()) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        PhotonAvatar(
            model = profile?.avatarUrl,
            name = profile?.name,
            size = 96.dp,
            contentDescription = "Change profile photo",
            modifier = Modifier
                .clickable(onClick = onChangePhoto)
                .semantics { contentDescription = "Change profile photo" },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = profile?.name?.takeIf { it.isNotBlank() } ?: "No name set",
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
            Text("Plan: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("Edit profile") }
            if (profile?.avatarUrl != null) {
                TextButton(onClick = onRemovePhoto) { Text("Remove photo") }
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
        title = "Message notifications",
        subtitle = "Notify me about new messages",
        checked = prefs.enabled,
        onCheckedChange = onEnabledChange,
    )
    if (prefs.enabled) {
        SwitchRow(
            title = "Show preview",
            subtitle = "Show sender and message text",
            checked = prefs.showPreview,
            onCheckedChange = onPreviewChange,
        )
        if (!notificationsAllowed) {
            GuidanceCard(
                title = "Notifications are turned off",
                body = "Allow notifications so Photon can alert you to new messages.",
                action = "Allow",
                onClick = { requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
        if (!batteryUnrestricted) {
            GuidanceCard(
                title = "Allow background activity",
                body = "Exempt Photon from battery optimization so it stays connected and " +
                    "delivers messages reliably.",
                action = "Open settings",
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

@Composable
private fun ThemeModeRow(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            FilterChip(
                selected = current == mode,
                onClick = { onSelect(mode) },
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
        title = { Text("Edit profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("Bio") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (requirePassphrase) {
                    PassphraseField(passphrase, { passphrase = it }, "Passphrase")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), bio.trim(), email.trim(), passphrase.takeIf { requirePassphrase }) },
                enabled = !saving && passphraseOk,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
