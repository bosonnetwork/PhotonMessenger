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
import io.photonmessenger.feature.contacts.data.ContactRepository
import io.photonmessenger.feature.contacts.model.UiContact
import io.photonmessenger.feature.contacts.model.UiFriendRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactsUiState(
    val loading: Boolean = true,
    val friends: List<UiContact> = emptyList(),
    val channels: List<UiContact> = emptyList(),
    val requests: List<UiFriendRequest> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactRepository,
) : ViewModel() {

    val uiState: StateFlow<ContactsUiState> =
        combine(repository.contacts(), repository.friendRequests()) { contacts, requests ->
            ContactsUiState(
                loading = false,
                friends = contacts.filter { !it.isChannel },
                channels = contacts.filter { it.isChannel },
                requests = requests,
            )
        }.catch { e ->
            emit(ContactsUiState(loading = false, error = e.message))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ContactsUiState(loading = true),
        )

    /** Transient one-shot messages (e.g. action failures) for a snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    fun addFriend(idText: String, hello: String) = run("Couldn't send friend request") {
        repository.sendFriendRequest(idText, hello)
    }

    fun accept(userId: String) = run("Couldn't accept request") { repository.acceptFriendRequest(userId) }

    fun decline(userId: String) = run("Couldn't decline request") { repository.declineFriendRequest(userId) }

    fun setMuted(contactId: String, muted: Boolean) = run("Couldn't update contact") {
        repository.setMuted(contactId, muted)
    }

    fun setBlocked(contactId: String, blocked: Boolean) = run("Couldn't update contact") {
        repository.setBlocked(contactId, blocked)
    }

    fun remove(contactId: String) = run("Couldn't remove contact") { repository.removeContact(contactId) }

    private fun run(failurePrefix: String, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            action().onFailure { e ->
                _messages.tryEmit("$failurePrefix: ${e.message ?: "unknown error"}")
            }
        }
    }
}
