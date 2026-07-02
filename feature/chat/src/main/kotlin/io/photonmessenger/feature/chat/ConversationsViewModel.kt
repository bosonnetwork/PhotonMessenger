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
import io.photonmessenger.feature.chat.data.ChatRepository
import io.photonmessenger.feature.chat.model.UiConversation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun onQueryChange(value: String) { _query.value = value }

    val uiState: StateFlow<ConversationsUiState> =
        combine(repository.conversations(), _query) { conversations, query ->
            val trimmed = query.trim()
            val filtered = if (trimmed.isEmpty()) conversations else conversations.filter {
                it.title.contains(trimmed, ignoreCase = true) || it.preview.contains(trimmed, ignoreCase = true)
            }
            ConversationsUiState(
                loading = false,
                conversations = filtered,
                filteredEmpty = filtered.isEmpty() && conversations.isNotEmpty(),
            )
        }
            .catch { emit(ConversationsUiState(loading = false, error = it.message)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConversationsUiState(loading = true),
            )
}
