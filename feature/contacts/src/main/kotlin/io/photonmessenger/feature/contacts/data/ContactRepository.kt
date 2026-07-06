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

package io.photonmessenger.feature.contacts.data

import io.photonmessenger.core.boson.BosonSessionManager
import io.photonmessenger.core.boson.awaitResult
import io.photonmessenger.core.model.AppError
import io.photonmessenger.core.model.AvatarUrls
import io.photonmessenger.feature.contacts.model.UiContact
import io.photonmessenger.feature.contacts.model.UiFriendRequest
import io.photonmessenger.feature.contacts.model.toUi
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.Contact
import io.bosonnetwork.photonmessaging.ContactListener
import io.bosonnetwork.photonmessaging.FriendRequestListener
import io.bosonnetwork.photonmessaging.MessagingClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
    private val avatarUrls: AvatarUrls,
) : ContactRepository {

    private fun client(): MessagingClient =
        session.messagingClient ?: throw AppError.Network("Not connected to the messaging service")

    private fun Contact.toUiWithAvatar(): UiContact =
        toUi(avatarUrl = if (type == Contact.Type.CHANNEL) null else avatarUrls.forUser(id.toString()))

    override fun contacts(): Flow<List<UiContact>> = callbackFlow {
        val client = session.messagingClient
        if (client == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val current = LinkedHashMap<Id, Contact>()
        client.getContacts().awaitResult().forEach { current[it.id] = it }
        trySend(current.values.map { it.toUiWithAvatar() })

        val listener = object : ContactListener {
            override fun onContactAdded(contact: Contact) {
                current[contact.id] = contact
                trySend(current.values.map { it.toUiWithAvatar() })
            }

            override fun onContactsUpdated(contacts: List<Contact>) {
                contacts.forEach { current[it.id] = it }
                trySend(current.values.map { it.toUiWithAvatar() })
            }

            override fun onContactsRemoved(contactIds: List<Id>) {
                contactIds.forEach { current.remove(it) }
                trySend(current.values.map { it.toUiWithAvatar() })
            }

            override fun onContactsCleared() {
                current.clear()
                trySend(emptyList())
            }
        }
        client.addContactListener(listener)
        awaitClose { client.removeContactListener(listener) }
    }

    override fun contact(contactId: String): Flow<UiContact?> = callbackFlow {
        val client = client()
        val id = parseId(contactId)

        suspend fun emit() {
            val contact = client.getContact(id).awaitResult().orElse(null)
            trySend(contact?.toUiWithAvatar())
        }
        emit()

        val listener = object : ContactListener {
            override fun onContactAdded(contact: Contact) {
                if (contact.id == id) trySend(contact.toUiWithAvatar())
            }

            override fun onContactsUpdated(contacts: List<Contact>) {
                contacts.firstOrNull { it.id == id }?.let { trySend(it.toUiWithAvatar()) }
            }

            override fun onContactsRemoved(contactIds: List<Id>) {
                if (id in contactIds) trySend(null)
            }

            override fun onContactsCleared() {
                trySend(null)
            }
        }
        client.addContactListener(listener)
        awaitClose { client.removeContactListener(listener) }
    }

    override fun friendRequests(): Flow<List<UiFriendRequest>> = callbackFlow {
        val client = session.messagingClient
        if (client == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val pending = LinkedHashMap<Id, String>()
        client.getFriendRequests().awaitResult()
            .filter { !it.isAccepted }
            .forEach { pending[it.userId] = it.hello ?: "" }
        trySend(pending.map { UiFriendRequest(it.key.toString(), it.value) })

        val listener = object : FriendRequestListener {
            override fun onFriendRequest(userId: Id, hello: String) {
                pending[userId] = hello
                trySend(pending.map { UiFriendRequest(it.key.toString(), it.value) })
            }

            override fun onFriendRequestAccepted(userId: Id) {
                pending.remove(userId)
                trySend(pending.map { UiFriendRequest(it.key.toString(), it.value) })
            }
        }
        client.addFriendRequestListener(listener)
        awaitClose { client.removeFriendRequestListener(listener) }
    }

    override suspend fun sendFriendRequest(idText: String, hello: String): Result<Unit> = runCatching {
        client().friendRequest(parseId(idText), hello).awaitResult()
        Unit
    }

    override suspend fun acceptFriendRequest(userIdText: String): Result<Unit> = runCatching {
        client().acceptFriendRequest(parseId(userIdText)).awaitResult()
        Unit
    }

    override suspend fun declineFriendRequest(userIdText: String): Result<Unit> = runCatching {
        client().removeFriendRequest(parseId(userIdText)).awaitResult()
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
        Unit
    }

    private suspend fun editContact(contactId: String, edit: (Contact) -> Contact): Result<Unit> = runCatching {
        val client = client()
        val contact = client.getContact(parseId(contactId)).awaitResult().orElse(null)
            ?: throw AppError.NotFound("Contact not found")
        client.updateContact(edit(contact)).awaitResult()
        Unit
    }

    private fun parseId(text: String): Id =
        try {
            Id.of(text.trim())
        } catch (e: Exception) {
            throw AppError.InvalidInput("Invalid Boson ID", e)
        }
}
