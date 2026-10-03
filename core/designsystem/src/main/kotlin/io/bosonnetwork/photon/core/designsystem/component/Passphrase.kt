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

package io.bosonnetwork.photon.core.designsystem.component

import android.content.Intent
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.bosonnetwork.photon.core.designsystem.R

/** The fewest characters a new passphrase may have; the Director refuses a shorter one. */
const val PASSPHRASE_MIN_LENGTH = 8

/** The fewest different characters a new passphrase may have; the Director refuses one with fewer. */
const val PASSPHRASE_MIN_DISTINCT = 5

/**
 * What is wrong with a new [passphrase], as the Director judges it, or null if it would be accepted:
 * shorter than [PASSPHRASE_MIN_LENGTH] characters, or made of fewer than [PASSPHRASE_MIN_DISTINCT]
 * different ones. Characters are code points, as on the Director.
 */
@StringRes
fun passphraseProblem(passphrase: String): Int? {
    val codePoints = passphrase.codePoints().toArray()
    return when {
        codePoints.size < PASSPHRASE_MIN_LENGTH -> R.string.ds_passphrase_too_short
        codePoints.distinct().size < PASSPHRASE_MIN_DISTINCT -> R.string.ds_passphrase_too_plain
        else -> null
    }
}

/**
 * The message for a passphrase-gated action refused after too many wrong passphrases: how long to wait,
 * in seconds, minutes or hours as fits [retryAfterSeconds].
 */
fun Resources.passphraseLockedMessage(retryAfterSeconds: Long): String {
    val wait = when {
        retryAfterSeconds < 90 -> retryAfterSeconds.toInt().let { getQuantityString(R.plurals.ds_wait_seconds, it, it) }
        retryAfterSeconds < 90 * 60 ->
            ceilDiv(retryAfterSeconds, 60).let { getQuantityString(R.plurals.ds_wait_minutes, it, it) }
        else -> ceilDiv(retryAfterSeconds, 3600).let { getQuantityString(R.plurals.ds_wait_hours, it, it) }
    }
    return getString(R.string.ds_passphrase_locked, wait)
}

private fun ceilDiv(value: Long, by: Long): Int = ((value + by - 1) / by).toInt()

/**
 * Shows new passphrase recovery codes, once: the Director keeps only their hashes. Each resets a forgotten
 * passphrase once, without the user key, so they are kept apart from it. The user copies or shares them,
 * and the dialog closes only after "I saved these codes".
 */
@Composable
fun RecoveryCodesDialog(
    codes: List<String>,
    userId: String,
    onDone: () -> Unit,
) {
    var saved by rememberSaveable(codes) { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val text = stringResource(R.string.ds_recovery_codes_text, userId, codes.joinToString("\n"))
    val shareTitle = stringResource(R.string.ds_recovery_codes_title)

    AlertDialog(
        onDismissRequest = { if (saved) onDone() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(shareTitle) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.ds_recovery_codes_body))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        codes.forEach { Text(it, fontFamily = FontFamily.Monospace) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text(stringResource(R.string.ds_action_copy), Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, shareTitle)
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Text(stringResource(R.string.ds_action_share), Modifier.padding(start = 6.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = saved, onCheckedChange = null)
                    Text(stringResource(R.string.ds_recovery_codes_saved), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone, enabled = saved) { Text(stringResource(R.string.ds_action_done)) }
        },
    )
}
