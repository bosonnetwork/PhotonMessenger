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
import io.bosonnetwork.photonmessaging.exceptions.MessageTimeoutException
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val loading: Boolean = true,
    val messages: List<UiMessage> = emptyList(),
    val error: String? = null,
)

/** A failed text send that can be retried with the same [text] (M3-8). */
data class SendFailure(val message: String, val text: String)

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

    /** Older pages loaded via pagination, merged with the live stream (M3-5). */
    private val olderMessages = MutableStateFlow<List<UiMessage>>(emptyList())
    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    @Volatile
    private var reachedStart = false

    init {
        viewModelScope.launch {
            repository.header(conversationId).onSuccess { _header.value = it }
        }
    }

    val uiState: StateFlow<ChatUiState> =
        combine(repository.messages(conversationId), olderMessages) { live, older ->
            // De-duplicate the pagination boundary by id, then order oldest -> newest.
            val merged = (older + live).associateBy { it.id }.values.sortedBy { it.createdAt }
            ChatUiState(loading = false, messages = merged)
        }
            .catch { emit(ChatUiState(loading = false, error = it.message)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ChatUiState(loading = true),
            )

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors = _messages.asSharedFlow()

    private val _sendFailures = MutableSharedFlow<SendFailure>(extraBufferCapacity = 1)
    val sendFailures = _sendFailures.asSharedFlow()

    /** Loads an older page when the user scrolls to the top (M3-5). Idempotent + stops at the start. */
    fun loadOlder() {
        if (_loadingOlder.value || reachedStart) return
        val oldest = uiState.value.messages.firstOrNull()?.createdAt ?: return
        _loadingOlder.value = true
        viewModelScope.launch {
            repository.loadOlder(conversationId, oldest, ChatRepository.PAGE_SIZE)
                .onSuccess { page ->
                    // getMessagesBefore is inclusive of the boundary, so a page that adds nothing new
                    // (only the already-known oldest) means we've reached the start.
                    if (page.size < ChatRepository.PAGE_SIZE) reachedStart = true
                    olderMessages.update { it + page }
                }
                .onFailure { e -> _messages.tryEmit("Couldn't load older messages: ${e.message ?: "unknown error"}") }
            _loadingOlder.value = false
        }
    }

    private val _downloads = MutableStateFlow<Map<String, AttachmentDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, AttachmentDownload>> = _downloads.asStateFlow()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.sendText(conversationId, trimmed).onFailure { e ->
                _sendFailures.tryEmit(SendFailure(sendErrorMessage(e), trimmed))
            }
        }
    }

    private fun sendErrorMessage(e: Throwable): String = when (e) {
        is MessageTimeoutException -> "Message timed out"
        else -> "Couldn't send: ${e.message ?: "unknown error"}"
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
