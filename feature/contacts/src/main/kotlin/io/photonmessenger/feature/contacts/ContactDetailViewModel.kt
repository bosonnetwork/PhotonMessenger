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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.photonmessenger.feature.contacts.data.ContactRepository
import io.photonmessenger.feature.contacts.model.UiContact
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactDetailUiState(
    val loading: Boolean = true,
    val contact: UiContact? = null,
    val error: String? = null,
)

/** Contact profile: identity, alias, mute/block, and removal (design spec screen 5 detail). */
@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val repository: ContactRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val contactId: String = checkNotNull(savedStateHandle["contactId"]) { "contactId arg missing" }

    val uiState: StateFlow<ContactDetailUiState> =
        repository.contact(contactId)
            .map { ContactDetailUiState(loading = false, contact = it) }
            .catch { e -> emit(ContactDetailUiState(loading = false, error = e.message)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ContactDetailUiState(loading = true),
            )

    /** Transient one-shot messages (action failures) for a snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    /** Emitted after the contact is removed, so the screen can navigate away. */
    private val _removed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val removed = _removed.asSharedFlow()

    fun setMuted(muted: Boolean) = run("Couldn't update contact") { repository.setMuted(contactId, muted) }

    fun setBlocked(blocked: Boolean) = run("Couldn't update contact") { repository.setBlocked(contactId, blocked) }

    fun setRemark(remark: String?) = run("Couldn't update alias") { repository.setRemark(contactId, remark) }

    fun remove() {
        viewModelScope.launch {
            repository.removeContact(contactId)
                .onSuccess { _removed.tryEmit(Unit) }
                .onFailure { e -> _messages.tryEmit("Couldn't remove contact: ${e.message ?: "unknown error"}") }
        }
    }

    private fun run(failurePrefix: String, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            action().onFailure { e ->
                _messages.tryEmit("$failurePrefix: ${e.message ?: "unknown error"}")
            }
        }
    }
}
