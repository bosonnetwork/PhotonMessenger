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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.feature.settings.data.SettingsRepository
import io.bosonnetwork.photon.feature.settings.model.PassphrasePrompt
import io.bosonnetwork.photon.feature.settings.model.UiDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val loading: Boolean = true,
    val sessions: List<UiDevice> = emptyList(),
    val error: String? = null,
    /** Non-null while a passphrase-protected device removal is waiting for the user's passphrase (M6). */
    val passphrasePrompt: PassphrasePrompt? = null,
)

/**
 * Live messaging sessions (service-level, spec screen 6): one row per connected device. Revoking a
 * session signs that device out of messaging; an optional flag also deregisters the device from the
 * account. The account-level counterpart (manage all registered devices) is [DevicesViewModel].
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            repository.loadSessions()
                .onSuccess { _uiState.value = SessionsUiState(loading = false, sessions = it) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(loading = false, error = it.message)
                }
        }
    }

    /**
     * Revokes the device's messaging session (service-level, M6-3); by default the device stays
     * registered and can reconnect. When [alsoRemoveDevice] is set, the device is additionally
     * deregistered from the account after the session is revoked; a passphrase-protected account
     * gates that removal, so we fall back to the passphrase prompt.
     */
    fun revokeSession(deviceId: String, alsoRemoveDevice: Boolean) {
        viewModelScope.launch {
            val revoked = repository.revokeSession(deviceId)
            if (revoked.isFailure) {
                _messages.tryEmit("Couldn't revoke session: ${revoked.exceptionOrNull()?.message ?: "unknown error"}")
                return@launch
            }
            if (alsoRemoveDevice) {
                repository.removeDevice(deviceId, passphrase = null)
                    .onSuccess { refresh() }
                    .onFailure { handleRemoveFailure(deviceId, it) }
            } else {
                refresh()
            }
        }
    }

    /** Retries a gated device removal with the passphrase the user supplied in the prompt. */
    fun confirmRemoveWithPassphrase(passphrase: String) {
        val deviceId = _uiState.value.passphrasePrompt?.deviceId ?: return
        viewModelScope.launch {
            repository.removeDevice(deviceId, passphrase = passphrase)
                .onSuccess {
                    _uiState.update { it.copy(passphrasePrompt = null) }
                    refresh()
                }
                .onFailure { handleRemoveFailure(deviceId, it) }
        }
    }

    fun dismissPassphrasePrompt() {
        _uiState.update { it.copy(passphrasePrompt = null) }
    }

    private fun handleRemoveFailure(deviceId: String, error: Throwable) {
        when (error) {
            is AppError.PassphraseRequired ->
                _uiState.update { it.copy(passphrasePrompt = PassphrasePrompt(deviceId)) }
            is AppError.Forbidden ->
                _uiState.update { it.copy(passphrasePrompt = PassphrasePrompt(deviceId, "Wrong passphrase")) }
            else ->
                _messages.tryEmit("Couldn't remove device: ${error.message ?: "unknown error"}")
        }
    }
}
