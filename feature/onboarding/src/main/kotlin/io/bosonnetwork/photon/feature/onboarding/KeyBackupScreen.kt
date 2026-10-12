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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.bosonnetwork.crypto.KeyBackup
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.KeyManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Backing up this profile's identity key, and checking the copy: from Settings, at any time. */
data class KeyBackupUiState(
    val keyBase58: String? = null,
    val userId: String? = null,
    val challenge: KeyBackup.Challenge? = null,
    val checkFailed: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class KeyBackupViewModel @Inject constructor(private val keyManager: KeyManager) : ViewModel() {
    private val _uiState = MutableStateFlow(KeyBackupUiState())
    val uiState: StateFlow<KeyBackupUiState> = _uiState.asStateFlow()

    init {
        keyManager.userKeyPair()?.let { key ->
            _uiState.update {
                it.copy(
                    keyBase58 = KeyBackup.encode(BosonCrypto.privateKeyBytes64(key)),
                    userId = BosonCrypto.idOf(key).toString(),
                )
            }
        }
    }

    /** The user saved the key: ask for two of its groups. */
    fun continueFromBackup() {
        val key = _uiState.value.keyBase58 ?: return
        _uiState.update { it.copy(challenge = KeyBackup.challenge(key), checkFailed = false) }
    }

    /** Checks the two groups; when they match, the reminder in Settings goes away. */
    fun check(first: String, second: String) {
        val s = _uiState.value
        val key = s.keyBase58 ?: return
        val challenge = s.challenge ?: return
        if (KeyBackup.verify(key, challenge, first, second)) {
            keyManager.setBackupPending(false)
            _uiState.update { it.copy(done = true) }
        } else {
            _uiState.update { it.copy(checkFailed = true) }
        }
    }

    fun showKeyAgain() = _uiState.update { it.copy(challenge = null, checkFailed = false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyBackupScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: KeyBackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onb_backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onb_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val key = state.keyBase58
                val userId = state.userId
                val challenge = state.challenge
                when {
                    key == null || userId == null -> StepText(stringResource(R.string.onb_backup_no_key))
                    challenge == null -> BackupKeyStep(key, userId, onContinue = viewModel::continueFromBackup)
                    else -> CheckBackupStep(
                        challenge = challenge,
                        failed = state.checkFailed,
                        onCheck = viewModel::check,
                        onShowKeyAgain = viewModel::showKeyAgain,
                        onSkip = null,
                    )
                }
            }
        }
    }
}
