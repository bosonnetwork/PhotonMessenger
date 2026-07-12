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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.photonmessenger.core.model.ProfileResolver
import io.photonmessenger.core.model.displayProfile
import io.photonmessenger.feature.chat.data.ChatRepository
import io.photonmessenger.feature.chat.data.ForwardPayload
import io.photonmessenger.feature.chat.data.ForwardPayloadStore
import io.photonmessenger.feature.chat.model.UiForwardTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ForwardUiState(
    val loading: Boolean = true,
    val recent: List<UiForwardTarget> = emptyList(),
    val others: List<UiForwardTarget> = emptyList(),
    val error: String? = null,
    /** True when a non-blank query filtered every target out (drives the empty-state copy). */
    val filteredEmpty: Boolean = false,
) {
    val isEmpty: Boolean get() = recent.isEmpty() && others.isEmpty()
}

/**
 * Picks a destination for a forwarded message (screen: Forward to...). What to forward - text or an
 * existing attachment - is handed over via the [ForwardPayloadStore] (an attachment cannot ride a
 * navigation string). Tapping a target sends it there and reports the target id back so the caller can
 * open that chat. Targets are loaded once and their DM titles/avatars are enriched live via the shared
 * profile resolver, exactly like the conversation list.
 */
@HiltViewModel
class ForwardViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val profileResolver: ProfileResolver,
    private val forwardPayloadStore: ForwardPayloadStore,
) : ViewModel() {

    private val payload: ForwardPayload? = forwardPayloadStore.peek()

    /** A short description of what is being forwarded, for the screen header. */
    val forwardingLabel: String = when (val p = payload) {
        is ForwardPayload.Attachment -> p.attachment.name.ifBlank { "attachment" }
        is ForwardPayload.Text -> p.text
        null -> ""
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun onQueryChange(value: String) { _query.value = value }

    /** Transient one-shot messages (action failures) for a snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    /** Emits the target id once a forward succeeds, so the screen can open that chat. */
    private val _forwarded = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val forwarded = _forwarded.asSharedFlow()

    /** null while the initial load is in flight; a (possibly empty) list once it settles. */
    private val targets = MutableStateFlow<List<UiForwardTarget>?>(null)

    init {
        viewModelScope.launch {
            repository.forwardTargets()
                .onSuccess { targets.value = it }
                .onFailure { e ->
                    targets.value = emptyList()
                    _messages.tryEmit("Couldn't load contacts: ${e.message ?: "unknown error"}")
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun namedTargets(): Flow<List<UiForwardTarget>?> =
        targets.flatMapLatest { list ->
            if (list == null) return@flatMapLatest flowOf(null)
            val dms = list.filter { !it.isChannel }
            if (dms.isEmpty()) return@flatMapLatest flowOf(list)
            val displayFlows = dms.map { target ->
                profileResolver.displayProfile(target.id, localName = target.remark ?: target.peerName)
                    .map { target.id to it }
            }
            combine(displayFlows) { pairs ->
                val byId = pairs.toMap()
                list.map { target ->
                    val display = byId[target.id] ?: return@map target
                    target.copy(
                        title = if (display.nameIsFallback) target.title else display.displayName,
                        avatarUrl = display.avatarUrl,
                    )
                }
            }
        }

    val uiState: StateFlow<ForwardUiState> =
        combine(namedTargets(), _query) { list, query ->
            if (list == null) return@combine ForwardUiState(loading = true)
            val trimmed = query.trim()
            val filtered = if (trimmed.isEmpty()) list
            else list.filter { it.title.contains(trimmed, ignoreCase = true) }
            ForwardUiState(
                loading = false,
                recent = filtered.filter { it.recent },
                others = filtered.filter { !it.recent },
                filteredEmpty = filtered.isEmpty() && list.isNotEmpty(),
            )
        }
            .catch { emit(ForwardUiState(loading = false, error = it.message)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ForwardUiState(loading = true),
            )

    /**
     * Sends the forwarded payload to [targetId]; on success clears the handoff and reports the target
     * so the caller can open that chat. A text payload is re-sent as text; an attachment is forwarded
     * via the repository (remote attachments re-reference the existing IonStore object - no re-upload).
     */
    fun forward(targetId: String) {
        val p = payload ?: return
        if (p is ForwardPayload.Text && p.text.isBlank()) return
        viewModelScope.launch {
            val result = when (p) {
                is ForwardPayload.Text -> repository.sendText(targetId, p.text)
                is ForwardPayload.Attachment -> repository.forwardAttachment(targetId, p.attachment)
            }
            result
                .onSuccess { forwardPayloadStore.clear(); _forwarded.tryEmit(targetId) }
                .onFailure { e -> _messages.tryEmit("Couldn't forward: ${e.message ?: "unknown error"}") }
        }
    }
}
