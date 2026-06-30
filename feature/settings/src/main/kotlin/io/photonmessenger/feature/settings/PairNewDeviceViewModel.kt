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

package io.photonmessenger.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.photonmessenger.feature.settings.data.DevicePairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * New-device side of multi-device pairing (spec 2.4, M6-4): registers this device, shows the QR a
 * trusted existing device scans, then waits for that device to approve and relay the user key.
 */
sealed interface PairNewDeviceUiState {
    /** Registering the device key and minting the ephemeral key. */
    data object Preparing : PairNewDeviceUiState

    /** Showing the QR and long-polling for the existing device's approval. */
    data class WaitingForApproval(val qrText: String) : PairNewDeviceUiState

    /** The user key arrived and was stored; this device is now signed in. */
    data class Paired(val userId: String) : PairNewDeviceUiState

    data class Failed(val message: String) : PairNewDeviceUiState
}

@HiltViewModel
class PairNewDeviceViewModel @Inject constructor(
    private val repository: DevicePairingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PairNewDeviceUiState>(PairNewDeviceUiState.Preparing)
    val uiState: StateFlow<PairNewDeviceUiState> = _uiState.asStateFlow()

    init {
        start()
    }

    fun start() {
        _uiState.value = PairNewDeviceUiState.Preparing
        viewModelScope.launch {
            repository.createInvite(deviceName())
                .onFailure { _uiState.value = PairNewDeviceUiState.Failed(it.userMessage()) }
                .onSuccess { invite ->
                    _uiState.value = PairNewDeviceUiState.WaitingForApproval(invite.qrText)
                    repository.awaitApproval()
                        .onSuccess { userId -> _uiState.value = PairNewDeviceUiState.Paired(userId) }
                        .onFailure { _uiState.value = PairNewDeviceUiState.Failed(it.userMessage()) }
                }
        }
    }

    private fun deviceName(): String =
        listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifBlank { "Android device" }

    private fun Throwable.userMessage(): String = message ?: "Pairing failed"
}
