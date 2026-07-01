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
import io.photonmessenger.core.model.AppError
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

    /**
     * Showing what would be authorized; the user approves or denies. [needsPassphrase] is set once the
     * server has gated approval on the account passphrase (428); [passphraseError] carries a wrong-
     * passphrase message (403) on a retry.
     */
    data class Confirm(
        val info: PairingRequestInfo,
        val needsPassphrase: Boolean = false,
        val passphraseError: String? = null,
    ) : ApproveDeviceUiState

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

    @Volatile
    private var pendingInfo: PairingRequestInfo? = null

    /** Called by the scanner; ignored unless we are still waiting for a code. */
    fun onScanned(qrText: String) {
        if (_uiState.value != ApproveDeviceUiState.Scanning) return
        scannedQr = qrText
        _uiState.value = ApproveDeviceUiState.Loading
        viewModelScope.launch {
            repository.readRequest(qrText)
                .onSuccess {
                    pendingInfo = it
                    _uiState.value = ApproveDeviceUiState.Confirm(it)
                }
                .onFailure { _uiState.value = ApproveDeviceUiState.Failed(it.userMessage()) }
        }
    }

    fun approve(passphrase: String? = null) {
        val qr = scannedQr ?: return
        val info = pendingInfo ?: return
        _uiState.value = ApproveDeviceUiState.Approving
        viewModelScope.launch {
            repository.approve(qr, passphrase)
                .onSuccess { _uiState.value = ApproveDeviceUiState.Done(approved = true) }
                .onFailure { _uiState.value = it.toApproveState(info) }
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
        pendingInfo = null
        _uiState.value = ApproveDeviceUiState.Scanning
    }

    private fun Throwable.userMessage(): String = message ?: "Pairing failed"

    /**
     * A passphrase-protected account gates approval: 428 asks for the passphrase, 403 reports a wrong
     * one. Both keep the user on the confirmation step (with a passphrase field); anything else fails.
     */
    private fun Throwable.toApproveState(info: PairingRequestInfo): ApproveDeviceUiState = when (this) {
        is AppError.PassphraseRequired -> ApproveDeviceUiState.Confirm(info, needsPassphrase = true)
        is AppError.Forbidden ->
            ApproveDeviceUiState.Confirm(info, needsPassphrase = true, passphraseError = "Wrong passphrase")
        else -> ApproveDeviceUiState.Failed(userMessage())
    }
}
