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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.photonmessenger.feature.settings.data.DevicePairingRepository
import io.photonmessenger.feature.settings.data.PairingRequestInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Existing-device side of multi-device pairing (spec 2.4, M6-4): scans the new device's QR, confirms
 * what is being authorized, then seals the user key to it. The Director only relays the sealed blob.
 */
sealed interface ApproveDeviceUiState {
    /** Camera is open, waiting for a pairing QR. */
    data object Scanning : ApproveDeviceUiState

    /** Reading the scanned request from the Director. */
    data object Loading : ApproveDeviceUiState

    /** Showing what would be authorized; the user approves or denies. */
    data class Confirm(val info: PairingRequestInfo) : ApproveDeviceUiState

    /** Sealing and uploading the user key. */
    data object Approving : ApproveDeviceUiState

    /** The new device was authorized (or the request was denied). */
    data class Done(val approved: Boolean) : ApproveDeviceUiState

    data class Failed(val message: String) : ApproveDeviceUiState
}

@HiltViewModel
class ApproveDeviceViewModel @Inject constructor(
    private val repository: DevicePairingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ApproveDeviceUiState>(ApproveDeviceUiState.Scanning)
    val uiState: StateFlow<ApproveDeviceUiState> = _uiState.asStateFlow()

    @Volatile
    private var scannedQr: String? = null

    /** Called by the scanner; ignored unless we are still waiting for a code. */
    fun onScanned(qrText: String) {
        if (_uiState.value != ApproveDeviceUiState.Scanning) return
        scannedQr = qrText
        _uiState.value = ApproveDeviceUiState.Loading
        viewModelScope.launch {
            repository.readRequest(qrText)
                .onSuccess { _uiState.value = ApproveDeviceUiState.Confirm(it) }
                .onFailure { _uiState.value = ApproveDeviceUiState.Failed(it.userMessage()) }
        }
    }

    fun approve() {
        val qr = scannedQr ?: return
        _uiState.value = ApproveDeviceUiState.Approving
        viewModelScope.launch {
            repository.approve(qr)
                .onSuccess { _uiState.value = ApproveDeviceUiState.Done(approved = true) }
                .onFailure { _uiState.value = ApproveDeviceUiState.Failed(it.userMessage()) }
        }
    }

    fun deny() {
        val qr = scannedQr ?: return
        viewModelScope.launch {
            repository.deny(qr)
                .onSuccess { _uiState.value = ApproveDeviceUiState.Done(approved = false) }
                .onFailure { _uiState.value = ApproveDeviceUiState.Failed(it.userMessage()) }
        }
    }

    /** Return to scanning after an error or a non-matching scan. */
    fun rescan() {
        scannedQr = null
        _uiState.value = ApproveDeviceUiState.Scanning
    }

    private fun Throwable.userMessage(): String = message ?: "Pairing failed"
}
