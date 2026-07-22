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

package io.bosonnetwork.photon.feature.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.photon.core.boson.UnreadTracker
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.displayProfile
import io.bosonnetwork.photon.feature.chat.R
import io.bosonnetwork.photon.feature.chat.data.ChatRepository
import io.bosonnetwork.photon.feature.chat.model.UiConversation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

data class ConversationsUiState(
    val loading: Boolean = true,
    val conversations: List<UiConversation> = emptyList(),
    val error: String? = null,
    /** True when a non-blank [query] filtered every conversation out (drives the empty-state copy). */
    val filteredEmpty: Boolean = false,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val profileResolver: ProfileResolver,
    private val unreadTracker: UnreadTracker,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun onQueryChange(value: String) { _query.value = value }

    /** Transient one-shot messages (action failures) for a snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    /** Deletes a conversation's message history (the contact/channel itself is unaffected). */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            repository.removeConversation(conversationId).onFailure { e ->
                val reason = e.message ?: context.getString(R.string.chat_error_unknown)
                _messages.tryEmit(context.getString(R.string.chat_error_delete_conversation, reason))
            }
        }
    }

    /**
     * DM conversations enriched from the shared identity policy ([displayProfile]): each gets a
     * Director-resolved avatar, and a title still on the abbreviated-id fallback (no remark, no library
     * name) is upgraded to the resolved name. A title already carrying a remark or library name is left
     * untouched. Channels pass through unchanged.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun namedConversations(): Flow<List<UiConversation>> =
        repository.conversations().flatMapLatest { conversations ->
            val dms = conversations.filter { !it.isChannel }
            if (dms.isEmpty()) return@flatMapLatest flowOf(conversations)
            val displayFlows = dms.map { convo ->
                profileResolver.displayProfile(convo.id, localName = convo.remark ?: convo.peerName)
                    .map { convo.id to it }
            }
            combine(displayFlows) { pairs ->
                val byId = pairs.toMap()
                conversations.map { convo ->
                    val display = byId[convo.id] ?: return@map convo
                    convo.copy(
                        title = if (display.nameIsFallback) convo.title else display.displayName,
                        avatarUrl = display.avatarUrl,
                    )
                }
            }
        }

    val uiState: StateFlow<ConversationsUiState> =
        combine(namedConversations(), unreadTracker.unread, _query) { conversations, unread, query ->
            val withUnread = conversations.map { it.copy(unreadCount = unread[it.id] ?: 0) }
            val trimmed = query.trim()
            val filtered = if (trimmed.isEmpty()) withUnread else withUnread.filter {
                it.title.contains(trimmed, ignoreCase = true) || it.preview.contains(trimmed, ignoreCase = true)
            }
            ConversationsUiState(
                loading = false,
                conversations = filtered,
                filteredEmpty = filtered.isEmpty() && withUnread.isNotEmpty(),
            )
        }
            .catch { emit(ConversationsUiState(loading = false, error = it.message)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConversationsUiState(loading = true),
            )
}
