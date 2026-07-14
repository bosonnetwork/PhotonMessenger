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

package io.bosonnetwork.photon.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.displayProfile
import io.bosonnetwork.photon.feature.contacts.data.ChannelRepository
import io.bosonnetwork.photon.feature.contacts.data.ContactRepository
import io.bosonnetwork.photon.feature.contacts.model.UiContact
import io.bosonnetwork.photon.feature.contacts.model.UiFriendRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val channelRepository: ChannelRepository,
    private val profileResolver: ProfileResolver,
) : ViewModel() {

    val uiState: StateFlow<ContactsUiState> =
        combine(resolvedContacts(), resolvedFriendRequests()) { contacts, requests ->
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

    /**
     * Contacts enriched from the shared identity policy ([displayProfile]): every friend gets a
     * Director-resolved avatar, and one whose name is only a short id (no remark, no library name) is
     * upgraded to the resolved name. Local names always win, so a named contact keeps its title; the
     * short-id fallback is only replaced by a genuine resolved name. Channels pass through untouched.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun resolvedContacts(): Flow<List<UiContact>> =
        repository.contacts().flatMapLatest { contacts ->
            val friends = contacts.filter { !it.isChannel }
            if (friends.isEmpty()) return@flatMapLatest flowOf(contacts)
            val displayFlows = friends.map { contact ->
                profileResolver.displayProfile(contact.id, localName = contact.remark ?: contact.name)
                    .map { contact.id to it }
            }
            combine(displayFlows) { pairs ->
                val byId = pairs.toMap()
                contacts.map { contact ->
                    val display = byId[contact.id] ?: return@map contact
                    contact.copy(
                        // Keep the contact's own (local) name unless the Director resolved a real one.
                        displayName = if (display.nameIsFallback) contact.displayName else display.displayName,
                        avatarUrl = display.avatarUrl,
                    )
                }
            }
        }

    /**
     * Friend requests enriched with the sender's Director-resolved public profile ([displayProfile]):
     * raw ids become names + avatars once resolution completes (requests carry nothing but the id +
     * hello). [UiFriendRequest.name] stays null until a real name resolves, so the row shows the short
     * id in the meantime.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun resolvedFriendRequests(): Flow<List<UiFriendRequest>> =
        repository.friendRequests().flatMapLatest { requests ->
            if (requests.isEmpty()) return@flatMapLatest flowOf(emptyList())
            val enriched = requests.map { request ->
                profileResolver.displayProfile(request.userId).map { display ->
                    request.copy(
                        name = display.displayName.takeUnless { display.nameIsFallback },
                        avatarUrl = display.avatarUrl,
                    )
                }
            }
            combine(enriched) { it.toList() }
        }

    /** Transient one-shot messages (e.g. action failures) for a snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    /** Emits the id of a channel just joined via an invite ticket, so the screen can open it. */
    private val _joinedChannel = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val joinedChannel = _joinedChannel.asSharedFlow()

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

    fun setRemark(contactId: String, remark: String?) = run("Couldn't update alias") {
        repository.setRemark(contactId, remark)
    }

    fun remove(contactId: String) = run("Couldn't remove contact") { repository.removeContact(contactId) }

    /** Joins a channel from a shared invite-ticket string; opens it on success via [joinedChannel]. */
    fun joinChannel(ticket: String) {
        viewModelScope.launch {
            channelRepository.joinChannel(ticket.trim())
                .onSuccess { _joinedChannel.tryEmit(it) }
                .onFailure { _messages.tryEmit("Couldn't join channel: ${it.message ?: "unknown error"}") }
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
