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

package io.bosonnetwork.photon.feature.contacts.data

import io.bosonnetwork.photon.core.boson.BosonSessionManager
import io.bosonnetwork.photon.core.boson.awaitResult
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.feature.contacts.model.UiContact
import io.bosonnetwork.photon.feature.contacts.model.UiFriendRequest
import io.bosonnetwork.photon.feature.contacts.model.toUi
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.Contact
import io.bosonnetwork.photonmessaging.ContactListener
import io.bosonnetwork.photonmessaging.FriendRequestListener
import io.bosonnetwork.photonmessaging.MessagingClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Contacts and friend requests over the Boson [MessagingClient] (spec 1.2, screen 5, M2). Returns UI
 * models so the ViewModel stays decoupled from the client types (and unit-testable with a fake).
 */
interface ContactRepository {
    /** Live friends + channels; seeded from getContacts() and kept current via ContactListener. */
    fun contacts(): Flow<List<UiContact>>

    /** Live pending friend requests; seeded from getFriendRequests() and kept current via the listener. */
    fun friendRequests(): Flow<List<UiFriendRequest>>

    /** Live view of a single contact (for the detail screen); emits null once the contact is removed. */
    fun contact(contactId: String): Flow<UiContact?>

    suspend fun sendFriendRequest(idText: String, hello: String): Result<Unit>
    suspend fun acceptFriendRequest(userIdText: String): Result<Unit>
    suspend fun declineFriendRequest(userIdText: String): Result<Unit>
    suspend fun setMuted(contactId: String, muted: Boolean): Result<Unit>
    suspend fun setBlocked(contactId: String, blocked: Boolean): Result<Unit>

    /** Sets (or clears, when blank) the local alias/remark for a contact (M2-6). */
    suspend fun setRemark(contactId: String, remark: String?): Result<Unit>
    suspend fun removeContact(contactId: String): Result<Unit>
}

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val session: BosonSessionManager,
) : ContactRepository {

    private fun client(): MessagingClient =
        session.messagingClient ?: throw AppError.Network("Not connected to the messaging service")

    // Local mutations (accept/decline a request, edit/remove a contact) are initiated by THIS device,
    // so the messaging client deliberately does not fire the corresponding listener callback here (it
    // fires on the user's OTHER devices instead). We therefore poke the live flows ourselves after a
    // successful local op so the lists refresh without waiting for a callback that will never come.
    private val contactsRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val requestsRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Avatars (and any Director-resolved name upgrade) are enriched in the ViewModel via the shared
    // ProfileResolver, so the repository emits contacts (via Contact.toUi()) with only their
    // locally-known name and no avatar.

    // Keyed off the session's client flow (not a one-shot read) so a cold start - which composes the
    // UI before connect() completes - fills the list as soon as the session comes up, instead of
    // parking on an empty list until the tab is re-subscribed.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun contacts(): Flow<List<UiContact>> =
        session.client.flatMapLatest { client ->
            if (client == null) flowOf(emptyList()) else contactsOf(client)
        }

    private fun contactsOf(client: MessagingClient): Flow<List<UiContact>> = callbackFlow {
        suspend fun refresh() {
            trySend(client.getContacts().awaitResult().map { it.toUi() })
        }
        refresh()

        val listener = object : ContactListener {
            override fun onContactAdded(contact: Contact) { launch { refresh() } }
            override fun onContactsUpdated(contacts: List<Contact>) { launch { refresh() } }
            override fun onContactsRemoved(contactIds: List<Id>) { launch { refresh() } }
            override fun onContactsCleared() { trySend(emptyList()) }
        }
        client.addContactListener(listener)
        val refreshJob = launch { contactsRefresh.collect { refresh() } }
        awaitClose { client.removeContactListener(listener); refreshJob.cancel() }
    }

    override fun contact(contactId: String): Flow<UiContact?> = callbackFlow {
        val client = client()
        val id = parseId(contactId)

        suspend fun refresh() {
            val contact = client.getContact(id).awaitResult().orElse(null)
            trySend(contact?.toUi())
        }
        refresh()

        val listener = object : ContactListener {
            override fun onContactAdded(contact: Contact) { if (contact.id == id) launch { refresh() } }
            override fun onContactsUpdated(contacts: List<Contact>) {
                if (contacts.any { it.id == id }) launch { refresh() }
            }
            override fun onContactsRemoved(contactIds: List<Id>) { if (id in contactIds) trySend(null) }
            override fun onContactsCleared() { trySend(null) }
        }
        client.addContactListener(listener)
        val refreshJob = launch { contactsRefresh.collect { refresh() } }
        awaitClose { client.removeContactListener(listener); refreshJob.cancel() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun friendRequests(): Flow<List<UiFriendRequest>> =
        session.client.flatMapLatest { client ->
            if (client == null) flowOf(emptyList()) else friendRequestsOf(client)
        }

    private fun friendRequestsOf(client: MessagingClient): Flow<List<UiFriendRequest>> = callbackFlow {
        suspend fun refresh() {
            val pending = client.getFriendRequests().awaitResult()
                .filter { !it.isAccepted }
                .map { UiFriendRequest(it.userId.toString(), it.hello ?: "") }
            trySend(pending)
        }
        refresh()

        val listener = object : FriendRequestListener {
            override fun onFriendRequest(userId: Id, hello: String) { launch { refresh() } }
            override fun onFriendRequestAccepted(userId: Id) {
                // On the INITIATOR side, the peer accepting our request auto-adds them as a contact
                // but fires no ContactListener callback (that addition is a continuation of our own
                // outbound request), so poke the contacts list too - not just the request list.
                launch { refresh() }
                contactsRefresh.tryEmit(Unit)
            }
        }
        client.addFriendRequestListener(listener)
        val refreshJob = launch { requestsRefresh.collect { refresh() } }
        awaitClose { client.removeFriendRequestListener(listener); refreshJob.cancel() }
    }

    override suspend fun sendFriendRequest(idText: String, hello: String): Result<Unit> = runCatching {
        client().friendRequest(parseId(idText), hello).awaitResult()
        Unit
    }

    override suspend fun acceptFriendRequest(userIdText: String): Result<Unit> = runCatching {
        client().acceptFriendRequest(parseId(userIdText)).awaitResult()
        // Accepting adds the sender as a contact AND clears the pending request on this device.
        requestsRefresh.tryEmit(Unit)
        contactsRefresh.tryEmit(Unit)
        Unit
    }

    override suspend fun declineFriendRequest(userIdText: String): Result<Unit> = runCatching {
        client().removeFriendRequest(parseId(userIdText)).awaitResult()
        requestsRefresh.tryEmit(Unit)
        Unit
    }

    override suspend fun setMuted(contactId: String, muted: Boolean): Result<Unit> =
        editContact(contactId) { it.edit().setMuted(muted).build() }

    override suspend fun setBlocked(contactId: String, blocked: Boolean): Result<Unit> =
        editContact(contactId) { it.edit().setBlocked(blocked).build() }

    override suspend fun setRemark(contactId: String, remark: String?): Result<Unit> =
        editContact(contactId) { it.edit().setRemark(remark?.trim()?.ifBlank { null }).build() }

    override suspend fun removeContact(contactId: String): Result<Unit> = runCatching {
        client().removeContacts(listOf(parseId(contactId))).awaitResult()
        contactsRefresh.tryEmit(Unit)
        Unit
    }

    private suspend fun editContact(contactId: String, edit: (Contact) -> Contact): Result<Unit> = runCatching {
        val client = client()
        val contact = client.getContact(parseId(contactId)).awaitResult().orElse(null)
            ?: throw AppError.NotFound("Contact not found")
        client.updateContact(edit(contact)).awaitResult()
        contactsRefresh.tryEmit(Unit)
        Unit
    }

    private fun parseId(text: String): Id =
        try {
            Id.of(text.trim())
        } catch (e: Exception) {
            throw AppError.InvalidInput("Invalid Boson ID", e)
        }
}
