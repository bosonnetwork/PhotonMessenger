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
import io.photonmessenger.feature.settings.data.SettingsRepository
import io.photonmessenger.feature.settings.model.UiDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DevicesUiState(
    val loading: Boolean = true,
    val devices: List<UiDevice> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            repository.loadDevices()
                .onSuccess { _uiState.value = DevicesUiState(loading = false, devices = it) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(loading = false, error = it.message)
                }
        }
    }

    /** Drops the live session (M6-3); the device stays registered and can reconnect. */
    fun revokeSession(deviceId: String) = run("Couldn't sign out device") {
        repository.revokeSession(deviceId)
    }

    /** Deregisters the device entirely (M6-3). */
    fun removeDevice(deviceId: String) = run("Couldn't remove device") {
        repository.removeDevice(deviceId)
    }

    private fun run(failurePrefix: String, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            action()
                .onSuccess { refresh() }
                .onFailure { _messages.tryEmit("$failurePrefix: ${it.message ?: "unknown error"}") }
        }
    }
}
