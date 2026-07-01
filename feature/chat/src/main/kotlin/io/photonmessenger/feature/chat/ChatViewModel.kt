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

package io.photonmessenger.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.photonmessenger.feature.chat.data.ChatRepository
import io.photonmessenger.feature.chat.model.AttachmentSource
import io.photonmessenger.feature.chat.model.ChatHeader
import io.photonmessenger.feature.chat.model.UiAttachment
import io.photonmessenger.feature.chat.model.UiMessage
import io.photonmessenger.feature.chat.model.shortId
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val loading: Boolean = true,
    val messages: List<UiMessage> = emptyList(),
    val error: String? = null,
)

/** Per-attachment download state, keyed by content id. */
sealed interface AttachmentDownload {
    data object Loading : AttachmentDownload
    data class Ready(val file: File) : AttachmentDownload
    data class Failed(val message: String) : AttachmentDownload
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val conversationId: String = checkNotNull(savedStateHandle["conversationId"]) {
        "conversationId is required"
    }

    private val _header = MutableStateFlow(ChatHeader(title = shortId(conversationId)))
    val header: StateFlow<ChatHeader> = _header.asStateFlow()

    init {
        viewModelScope.launch {
            repository.header(conversationId).onSuccess { _header.value = it }
        }
    }

    val uiState: StateFlow<ChatUiState> =
        repository.messages(conversationId)
            .map { ChatUiState(loading = false, messages = it) }
            .catch { emit(ChatUiState(loading = false, error = it.message)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ChatUiState(loading = true),
            )

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors = _messages.asSharedFlow()

    private val _downloads = MutableStateFlow<Map<String, AttachmentDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, AttachmentDownload>> = _downloads.asStateFlow()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.sendText(conversationId, trimmed).onFailure { e ->
                _messages.tryEmit("Couldn't send: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun sendAttachment(uri: String) {
        viewModelScope.launch {
            repository.sendAttachment(conversationId, uri).onFailure { e ->
                _messages.tryEmit("Couldn't send attachment: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Fetches a remote attachment (idempotent: ignores in-flight/ready downloads). */
    fun download(attachment: UiAttachment) {
        val source = attachment.source
        if (source !is AttachmentSource.Remote) return
        val key = source.contentId
        val current = _downloads.value[key]
        if (current is AttachmentDownload.Loading || current is AttachmentDownload.Ready) return

        _downloads.update { it + (key to AttachmentDownload.Loading) }
        viewModelScope.launch {
            repository.downloadAttachment(attachment)
                .onSuccess { file -> _downloads.update { it + (key to AttachmentDownload.Ready(file)) } }
                .onFailure { e ->
                    _downloads.update { it + (key to AttachmentDownload.Failed(e.message ?: "Download failed")) }
                }
        }
    }
}
