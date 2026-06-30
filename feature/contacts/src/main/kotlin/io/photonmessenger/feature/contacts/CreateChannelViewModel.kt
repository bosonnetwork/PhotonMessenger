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

package io.photonmessenger.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.photonmessenger.feature.contacts.data.ChannelRepository
import io.photonmessenger.feature.contacts.model.UiChannelPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateChannelUiState(
    val name: String = "",
    val notice: String = "",
    val permission: UiChannelPermission = UiChannelPermission.OWNER_INVITE,
    val announce: Boolean = true,
    val submitting: Boolean = false,
) {
    val canSubmit: Boolean get() = name.isNotBlank() && !submitting
}

/** One-shot outcome of a create attempt. */
sealed interface CreateChannelEvent {
    data class Created(val channelId: String) : CreateChannelEvent
    data class Error(val message: String) : CreateChannelEvent
}

@HiltViewModel
class CreateChannelViewModel @Inject constructor(
    private val repository: ChannelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateChannelUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CreateChannelEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    fun setName(value: String) = _uiState.update { it.copy(name = value) }
    fun setNotice(value: String) = _uiState.update { it.copy(notice = value) }
    fun setPermission(permission: UiChannelPermission) = _uiState.update { it.copy(permission = permission) }
    fun setAnnounce(announce: Boolean) = _uiState.update { it.copy(announce = announce) }

    fun create() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            repository.createChannel(
                name = state.name.trim(),
                notice = state.notice.trim().ifBlank { null },
                permission = state.permission,
                announce = state.announce,
            ).onSuccess { _events.tryEmit(CreateChannelEvent.Created(it)) }
                .onFailure { _events.tryEmit(CreateChannelEvent.Error(it.message ?: "Couldn't create channel")) }
            _uiState.update { it.copy(submitting = false) }
        }
    }
}
