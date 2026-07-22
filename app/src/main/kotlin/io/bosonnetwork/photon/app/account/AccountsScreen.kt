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

package io.bosonnetwork.photon.app.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.bosonnetwork.photon.app.R
import io.bosonnetwork.photon.core.security.Profile

/**
 * Account switcher: lists the on-device profiles (one per Boson identity), lets the user switch to,
 * add, or remove one. Switching/adding/removing-the-active-profile relaunches the app so the whole
 * singleton graph rebinds to the selected profile's stores.
 *
 * In [signInMode] (opened from onboarding as a returning-user picker) it becomes sign-in focused: only
 * profiles that already hold an identity are listed, tapping a row signs straight into it (including
 * the just-signed-out active profile) with no confirm dialog, and the destructive remove action and
 * active marker are hidden to keep the onboarding entry low-risk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    profiles: List<Profile>,
    activeProfileId: String,
    onSwitch: (String) -> Unit,
    onAddAccount: () -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
    signInMode: Boolean = false,
) {
    var confirmRemove by remember { mutableStateOf<Profile?>(null) }
    var confirmSwitch by remember { mutableStateOf<Profile?>(null) }

    // Sign-in picker only offers accounts that already have a bound identity to sign into.
    val shown = if (signInMode) profiles.filter { it.userId != null } else profiles

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (signInMode) R.string.app_accounts_sign_in_title
                            else R.string.app_accounts_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.app_accounts_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { profile ->
                    val active = profile.id == activeProfileId
                    ListItem(
                        headlineContent = { Text(profileTitle(profile)) },
                        supportingContent = {
                            // Always the short id; the active profile is marked by the trailing check icon.
                            Text(
                                profile.userId?.let(::shortId)
                                    ?: stringResource(R.string.app_accounts_not_signed_in),
                            )
                        },
                        trailingContent = {
                            when {
                                // Sign-in picker: every row is a tappable sign-in target; no active
                                // marker (the user is signed out) and no destructive remove here.
                                signInMode -> Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                                active -> {
                                    // Match the delete IconButton's 48dp slot so the active check and the
                                    // remove icon are the same size and share the same trailing position.
                                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = stringResource(R.string.app_accounts_active),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                else -> IconButton(onClick = { confirmRemove = profile }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.app_accounts_remove_account_cd),
                                    )
                                }
                            }
                        },
                        // Sign-in mode signs in directly (no session to abandon); the switcher confirms
                        // first because switching abandons the live session, and never re-selects active.
                        modifier = if (signInMode) {
                            Modifier.clickable { onSwitch(profile.id) }
                        } else {
                            Modifier.clickable(enabled = !active) { confirmSwitch = profile }
                        },
                    )
                    HorizontalDivider()
                }
            }
            OutlinedButton(
                onClick = onAddAccount,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(
                    stringResource(
                        if (signInMode) R.string.app_accounts_sign_in_add
                        else R.string.app_accounts_add_account,
                    ),
                )
            }
        }
    }

    confirmSwitch?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            title = { Text(stringResource(R.string.app_accounts_switch_dialog_title)) },
            text = {
                Text(stringResource(R.string.app_accounts_switch_dialog_message, profileTitle(profile)))
            },
            confirmButton = {
                TextButton(onClick = { onSwitch(profile.id); confirmSwitch = null }) {
                    Text(stringResource(R.string.app_accounts_switch))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSwitch = null }) {
                    Text(stringResource(R.string.app_accounts_cancel))
                }
            },
        )
    }

    confirmRemove?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.app_accounts_remove_dialog_title)) },
            text = {
                Text(stringResource(R.string.app_accounts_remove_dialog_message, profileTitle(profile)))
            },
            confirmButton = {
                TextButton(onClick = { onRemove(profile.id); confirmRemove = null }) {
                    Text(stringResource(R.string.app_accounts_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(R.string.app_accounts_cancel))
                }
            },
        )
    }
}

@Composable
private fun profileTitle(profile: Profile): String =
    profile.displayName?.takeIf { it.isNotBlank() }
        ?: profile.userId?.let(::shortId)
        ?: stringResource(R.string.app_accounts_new_account)

/** first8...last8 abbreviation of a base58 id; short ids are returned as-is. */
private fun shortId(id: String): String =
    if (id.length > 20) "${id.take(8)}...${id.takeLast(8)}" else id
