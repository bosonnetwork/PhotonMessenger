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

package io.bosonnetwork.photon.core.database

import io.bosonnetwork.photon.core.database.entity.ChannelEntity
import io.bosonnetwork.photon.core.database.entity.ChannelMemberEntity
import io.bosonnetwork.photon.core.database.entity.ContactEntity
import io.bosonnetwork.photon.core.database.entity.FriendRequestEntity
import io.bosonnetwork.photon.core.database.entity.MessageEntity
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.Channel
import io.bosonnetwork.photonmessaging.Contact
import io.bosonnetwork.photonmessaging.MessagingStore
import io.bosonnetwork.photonmessaging.MessagingStore.StoredChannel
import io.bosonnetwork.photonmessaging.MessagingStore.StoredChannelMember
import io.bosonnetwork.photonmessaging.MessagingStore.StoredContact
import io.bosonnetwork.photonmessaging.MessagingStore.StoredConversation
import io.bosonnetwork.photonmessaging.MessagingStore.StoredFriendRequest
import io.bosonnetwork.photonmessaging.MessagingStore.StoredMessage
import io.vertx.core.Future
import io.vertx.core.Vertx
import java.util.concurrent.Callable

/**
 * Native Android implementation of the Boson [MessagingStore] (Option A), backed by Room. Each
 * operation runs on a Vert.x worker thread (the DAO is synchronous) and completes a Vert.x Future,
 * so the client's event loop is never blocked. Records map to/from Room entities; Boson `Id`s are
 * stored as their raw bytes.
 */
class RoomMessagingStore(
    private val vertx: Vertx,
    private val db: PhotonDatabase,
) : MessagingStore {

    private val dao: MessagingDao = db.messagingDao()

    private val channelTypeValue = Contact.Type.CHANNEL.value()
    private val ownerRole = Channel.Role.OWNER.value()
    private val memberRole = Channel.Role.MEMBER.value()

    private fun <T> blocking(block: () -> T): Future<T> = vertx.executeBlocking(Callable { block() })

    override fun open(): Future<Void> = blocking { dao.getRevisionRow() }.mapEmpty()

    override fun close(): Future<Void> = blocking { db.close() }.mapEmpty()

    // --- Messages ---

    override fun putMessage(message: StoredMessage): Future<Long> =
        blocking { dao.insertMessage(toEntity(message)) }

    override fun updateMessageSentTime(messageId: Id, sentAt: Long): Future<Void> =
        blocking { dao.updateMessageSentTime(messageId.bytesUnsafe(), sentAt) }.mapEmpty()

    override fun getMessagesInRange(conversationId: Id, begin: Long, end: Long): Future<List<StoredMessage>> =
        blocking { dao.getMessagesInRange(conversationId.bytesUnsafe(), begin, end).map(::toStored) }

    override fun getMessagesBefore(conversationId: Id, until: Long, limit: Int, offset: Int): Future<List<StoredMessage>> =
        blocking { dao.getMessagesBefore(conversationId.bytesUnsafe(), until, limit, offset).map(::toStored) }

    override fun removeMessages(rids: Collection<Long>): Future<Boolean> =
        blocking { dao.deleteMessagesByRids(rids.toList()) > 0 }

    override fun removeMessagesByConversation(conversationId: Id): Future<Boolean> =
        blocking { dao.deleteMessagesByConversation(conversationId.bytesUnsafe()) > 0 }

    override fun clearMessages(): Future<Void> = blocking { dao.deleteAllMessages() }.mapEmpty()

    // --- Friend requests ---

    override fun putFriendRequest(friendRequest: StoredFriendRequest): Future<Void> =
        blocking { dao.upsertFriendRequest(toEntity(friendRequest)) }.mapEmpty()

    override fun getFriendRequest(userId: Id): Future<StoredFriendRequest?> =
        blocking { dao.getFriendRequest(userId.bytesUnsafe())?.let(::toStored) }

    override fun getFriendRequests(): Future<List<StoredFriendRequest>> =
        blocking { dao.getFriendRequests().map(::toStored) }

    override fun removeFriendRequest(userId: Id): Future<Boolean> =
        blocking { dao.deleteFriendRequest(userId.bytesUnsafe()) > 0 }

    override fun removeFriendRequests(userIds: Collection<Id>): Future<Boolean> =
        blocking { dao.deleteFriendRequests(userIds.map { it.bytesUnsafe() }) > 0 }

    override fun clearFriendRequests(): Future<Void> = blocking { dao.deleteAllFriendRequests() }.mapEmpty()

    // --- Conversations ---

    override fun getConversation(conversationId: Id): Future<StoredConversation?> = blocking {
        val contact = dao.getContact(conversationId.bytesUnsafe()) ?: return@blocking null
        val last = dao.getLastMessage(conversationId.bytesUnsafe()) ?: return@blocking null
        StoredConversation(toStoredContact(contact), toStored(last))
    }

    override fun getAllConversations(): Future<List<StoredConversation>> = blocking {
        dao.getConversationContacts()
            .mapNotNull { contact ->
                val last = dao.getLastMessage(contact.id) ?: return@mapNotNull null
                StoredConversation(toStoredContact(contact), toStored(last)) to last
            }
            .sortedWith(compareByDescending<Pair<StoredConversation, MessageEntity>> { it.second.receivedAt }
                .thenByDescending { it.second.rid })
            .map { it.first }
    }

    override fun removeConversation(conversationId: Id): Future<Boolean> =
        blocking { dao.deleteMessagesByConversation(conversationId.bytesUnsafe()) > 0 }

    override fun removeConversations(conversationIds: Collection<Id>): Future<Boolean> =
        blocking { conversationIds.fold(false) { acc, id -> (dao.deleteMessagesByConversation(id.bytesUnsafe()) > 0) || acc } }

    // --- Contacts ---

    override fun getContactsRevision(): Future<Int> = blocking { dao.getRevisionRow()?.revision ?: 0 }

    override fun putContactLocally(contact: StoredContact): Future<Void> =
        blocking { dao.putContactLocal(toEntity(contact), toChannelEntity(contact)) }.mapEmpty()

    override fun removeContactLocally(contactId: Id): Future<Boolean> =
        blocking { dao.removeContactLocal(contactId.bytesUnsafe()) }

    override fun putContact(revision: Int, contact: StoredContact): Future<Void> =
        blocking {
            dao.putContactWithRevision(revision, toEntity(contact), toChannelEntity(contact), System.currentTimeMillis())
        }.mapEmpty()

    override fun putContacts(revision: Int, contacts: Collection<StoredContact>): Future<Void> = blocking {
        val contactEntities = contacts.map(::toEntity)
        val channelEntities = contacts.mapNotNull(::toChannelEntity)
        dao.putContactsWithRevision(revision, contactEntities, channelEntities, System.currentTimeMillis())
    }.mapEmpty()

    override fun removeContact(revision: Int, contactId: Id): Future<Boolean> =
        blocking { dao.removeContactWithRevision(revision, contactId.bytesUnsafe(), System.currentTimeMillis()) }

    override fun removeContacts(revision: Int, contactIds: Collection<Id>): Future<Boolean> =
        blocking { dao.removeContactsWithRevision(revision, contactIds.map { it.bytesUnsafe() }, System.currentTimeMillis()) }

    override fun clearContacts(revision: Int): Future<Void> =
        blocking { dao.clearContactsWithRevision(revision, System.currentTimeMillis()) }.mapEmpty()

    override fun getContact(contactId: Id): Future<StoredContact?> =
        blocking { dao.getContact(contactId.bytesUnsafe())?.let(::toStoredContact) }

    override fun getContacts(contactIds: Collection<Id>): Future<List<StoredContact>> =
        blocking { dao.getContacts(contactIds.map { it.bytesUnsafe() }).map(::toStoredContact) }

    override fun getAllContacts(): Future<List<StoredContact>> =
        blocking { dao.getAllContacts().map(::toStoredContact) }

    // --- Channel members ---

    override fun updateChannelOwnership(channelId: Id, oldOwnerId: Id, newOwnerId: Id): Future<Void> = blocking {
        dao.transferOwnership(channelId.bytesUnsafe(), oldOwnerId.bytesUnsafe(), newOwnerId.bytesUnsafe(), ownerRole, memberRole)
    }.mapEmpty()

    override fun putChannelMembers(channelId: Id, members: Collection<StoredChannelMember>): Future<Void> =
        blocking { dao.upsertMembers(members.map(::toEntity)) }.mapEmpty()

    override fun refillChannelMembers(channelId: Id, members: Collection<StoredChannelMember>): Future<Void> =
        blocking { dao.refillMembers(channelId.bytesUnsafe(), members.map(::toEntity)) }.mapEmpty()

    override fun getChannelMember(channelId: Id, memberId: Id): Future<StoredChannelMember?> =
        blocking { dao.getMember(channelId.bytesUnsafe(), memberId.bytesUnsafe())?.let(::toStored) }

    override fun getChannelMembers(channelId: Id, memberIds: Collection<Id>): Future<List<StoredChannelMember>> =
        blocking { dao.getMembers(channelId.bytesUnsafe(), memberIds.map { it.bytesUnsafe() }).map(::toStored) }

    override fun getAllChannelMembers(channelId: Id): Future<List<StoredChannelMember>> =
        blocking { dao.getAllMembers(channelId.bytesUnsafe()).map(::toStored) }

    override fun updateChannelMembersRole(channelId: Id, memberIds: Collection<Id>, role: Int): Future<Boolean> =
        blocking { dao.updateMembersRole(channelId.bytesUnsafe(), memberIds.map { it.bytesUnsafe() }, role) > 0 }

    override fun removeChannelMembers(channelId: Id, memberIds: Collection<Id>): Future<Boolean> =
        blocking { dao.deleteMembers(channelId.bytesUnsafe(), memberIds.map { it.bytesUnsafe() }) > 0 }

    // --- Mapping: record -> entity ---

    private fun toEntity(m: StoredMessage) = MessageEntity(
        id = m.id().bytesUnsafe(),
        conversationId = m.conversationId().bytesUnsafe(),
        version = m.version(),
        recipient = m.recipient().bytesUnsafe(),
        type = m.type(),
        fromId = m.from()?.bytesUnsafe(),
        createdAt = m.createdAt(),
        contentType = m.contentType(),
        contentDisposition = m.contentDisposition(),
        payload = m.payload(),
        sentAt = m.sentAt(),
        receivedAt = m.receivedAt(),
    )

    private fun toEntity(c: StoredContact) = ContactEntity(
        id = c.id().bytesUnsafe(),
        type = c.type(),
        sessionKey = c.sessionKey(),
        name = c.name(),
        remark = c.remark(),
        tags = c.tags(),
        muted = c.muted(),
        blocked = c.blocked(),
        revision = c.revision(),
        createdAt = c.createdAt(),
        updatedAt = c.updatedAt(),
    )

    private fun toChannelEntity(c: StoredContact): ChannelEntity? = c.channel()?.let { ch ->
        ChannelEntity(
            id = ch.id().bytesUnsafe(),
            owner = ch.owner().bytesUnsafe(),
            permission = ch.permission(),
            notice = ch.notice(),
            announce = ch.announce(),
        )
    }

    private fun toEntity(f: StoredFriendRequest) = FriendRequestEntity(
        id = f.id().bytesUnsafe(),
        initiator = f.initiator().bytesUnsafe(),
        hello = f.hello(),
        createdAt = f.createdAt(),
        updatedAt = f.updatedAt(),
        accepted = f.accepted(),
        acceptedAt = f.acceptedAt(),
    )

    private fun toEntity(m: StoredChannelMember) = ChannelMemberEntity(
        id = m.id().bytesUnsafe(),
        channelId = m.channelId().bytesUnsafe(),
        role = m.role(),
        joined = m.joined(),
    )

    // --- Mapping: entity -> record ---

    private fun toStored(e: MessageEntity) = StoredMessage(
        e.rid,
        Id.of(e.id),
        Id.of(e.conversationId),
        e.version,
        Id.of(e.recipient),
        e.type,
        e.fromId?.let { Id.of(it) },
        e.createdAt,
        e.contentType,
        e.contentDisposition,
        e.payload,
        e.sentAt,
        e.receivedAt,
    )

    private fun toStoredContact(e: ContactEntity): StoredContact {
        val channel = if (e.type == channelTypeValue) {
            dao.getChannel(e.id)?.let { ch ->
                StoredChannel(Id.of(ch.id), Id.of(ch.owner), ch.permission, ch.notice, ch.announce)
            }
        } else {
            null
        }
        return StoredContact(
            Id.of(e.id), e.type, e.sessionKey, e.name, e.remark, e.tags,
            e.muted, e.blocked, e.revision, e.createdAt, e.updatedAt, channel,
        )
    }

    private fun toStored(e: FriendRequestEntity) = StoredFriendRequest(
        Id.of(e.id), Id.of(e.initiator), e.hello, e.createdAt, e.updatedAt, e.accepted, e.acceptedAt,
    )

    private fun toStored(e: ChannelMemberEntity) = StoredChannelMember(
        Id.of(e.id), Id.of(e.channelId), e.role, e.joined,
    )
}
