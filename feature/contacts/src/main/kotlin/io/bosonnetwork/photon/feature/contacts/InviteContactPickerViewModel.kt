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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.displayProfile
import io.bosonnetwork.photon.feature.contacts.data.ChannelRepository
import io.bosonnetwork.photon.feature.contacts.data.ContactRepository
import io.bosonnetwork.photon.feature.contacts.model.UiContact
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

data class InvitePickerUiState(
    val loading: Boolean = true,
    val contacts: List<UiContact> = emptyList(),
    val error: String? = null,
    /** True when a non-blank query filtered every contact out (drives the empty-state copy). */
    val filteredEmpty: Boolean = false,
    /** True while an invitation send is in flight, so rows disable to prevent a double send. */
    val sending: Boolean = false,
) {
    val isEmpty: Boolean get() = contacts.isEmpty()
}

/** One-shot outcome of an invite send. */
sealed interface InvitePickerEvent {
    data class Sent(val contactName: String) : InvitePickerEvent
    data class Error(val message: String) : InvitePickerEvent
}

/**
 * Contact picker for a NAMED channel invite. Mirrors the message-forward picker (search + a single
 * "Contacts" section, live name/avatar enrichment via the shared profile resolver) but lists only
 * contacts - no channels, no recent-conversations section. Selecting a contact mints a named ticket
 * and delivers it to them as a channel-invite message ([ChannelRepository.inviteContact]).
 */
@HiltViewModel
class InviteContactPickerViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val channelRepository: ChannelRepository,
    private val profileResolver: ProfileResolver,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val channelId: String = checkNotNull(savedStateHandle["channelId"]) { "channelId arg missing" }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun onQueryChange(value: String) { _query.value = value }

    private val _sending = MutableStateFlow(false)

    private val _events = MutableSharedFlow<InvitePickerEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun resolvedContacts(): Flow<List<UiContact>> =
        contactRepository.contacts().flatMapLatest { contacts ->
            val friends = contacts.filter { !it.isChannel && !it.blocked }
            if (friends.isEmpty()) return@flatMapLatest flowOf(emptyList())
            val displayFlows = friends.map { contact ->
                profileResolver.displayProfile(contact.id, localName = contact.remark ?: contact.name)
                    .map { contact.id to it }
            }
            combine(displayFlows) { pairs ->
                val byId = pairs.toMap()
                friends.map { contact ->
                    val display = byId[contact.id] ?: return@map contact
                    contact.copy(
                        displayName = if (display.nameIsFallback) contact.displayName else display.displayName,
                        avatarUrl = display.avatarUrl,
                    )
                }.sortedBy { it.displayName.lowercase() }
            }
        }

    val uiState: StateFlow<InvitePickerUiState> =
        combine(resolvedContacts(), _query, _sending) { list, query, sending ->
            val trimmed = query.trim()
            val filtered = if (trimmed.isEmpty()) list
            else list.filter { it.displayName.contains(trimmed, ignoreCase = true) }
            InvitePickerUiState(
                loading = false,
                contacts = filtered,
                filteredEmpty = filtered.isEmpty() && list.isNotEmpty(),
                sending = sending,
            )
        }
            .catch { emit(InvitePickerUiState(loading = false, error = it.message)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = InvitePickerUiState(loading = true),
            )

    /** Mints a named ticket for [contact] and sends it as a channel-invite message. */
    fun invite(contact: UiContact) {
        if (_sending.value) return
        _sending.value = true
        viewModelScope.launch {
            channelRepository.inviteContact(channelId, contact.id)
                .onSuccess { _events.tryEmit(InvitePickerEvent.Sent(contact.displayName)) }
                .onFailure {
                    _events.tryEmit(InvitePickerEvent.Error(it.message ?: "Couldn't send invitation"))
                }
            _sending.value = false
        }
    }
}
