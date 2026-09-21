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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.bosonnetwork.photon.core.database.entity.ChannelEntity
import io.bosonnetwork.photon.core.database.entity.ChannelInviteEntity
import io.bosonnetwork.photon.core.database.entity.ChannelMemberEntity
import io.bosonnetwork.photon.core.database.entity.ContactEntity
import io.bosonnetwork.photon.core.database.entity.ContactsRevisionEntity
import io.bosonnetwork.photon.core.database.entity.FriendRequestEntity
import io.bosonnetwork.photon.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** Room DAO for the messaging store. Methods are synchronous; the store runs them off the event loop. */
@Dao
interface MessagingDao {

    // --- Messages ---
    @Insert
    fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET sentAt = :sentAt WHERE id = :messageId")
    fun updateMessageSentTime(messageId: ByteArray, sentAt: Long): Int

    @Query("SELECT * FROM messages WHERE conversationId = :cid AND createdAt >= :begin AND createdAt < :end ORDER BY rid ASC")
    fun getMessagesInRange(cid: ByteArray, begin: Long, end: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :cid AND createdAt <= :until ORDER BY rid DESC LIMIT :limit OFFSET :offset")
    fun getMessagesBefore(cid: ByteArray, until: Long, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY rid DESC LIMIT 1")
    fun getLastMessage(cid: ByteArray): MessageEntity?

    @Query("DELETE FROM messages WHERE rid IN (:rids)")
    fun deleteMessagesByRids(rids: List<Long>): Int

    @Query("DELETE FROM messages WHERE conversationId = :cid")
    fun deleteMessagesByConversation(cid: ByteArray): Int

    @Query("DELETE FROM messages")
    fun deleteAllMessages()

    // --- Friend requests ---
    @Upsert
    fun upsertFriendRequest(request: FriendRequestEntity)

    @Query("SELECT * FROM friend_requests WHERE id = :id")
    fun getFriendRequest(id: ByteArray): FriendRequestEntity?

    // Newest first, as the library's own SQL stores return them.
    @Query("SELECT * FROM friend_requests ORDER BY createdAt DESC")
    fun getFriendRequests(): List<FriendRequestEntity>

    @Query("DELETE FROM friend_requests WHERE id = :id")
    fun deleteFriendRequest(id: ByteArray): Int

    @Query("DELETE FROM friend_requests WHERE id IN (:ids)")
    fun deleteFriendRequests(ids: List<ByteArray>): Int

    @Query("DELETE FROM friend_requests")
    fun deleteAllFriendRequests()

    // --- Contacts & channels ---
    @Upsert
    fun upsertContact(contact: ContactEntity)

    @Upsert
    fun upsertChannel(channel: ChannelEntity)

    @Query("SELECT * FROM contacts WHERE id = :id")
    fun getContact(id: ByteArray): ContactEntity?

    @Query("SELECT * FROM channels WHERE id = :id")
    fun getChannel(id: ByteArray): ChannelEntity?

    @Query("SELECT * FROM contacts WHERE id IN (:ids)")
    fun getContacts(ids: List<ByteArray>): List<ContactEntity>

    @Query("SELECT * FROM contacts")
    fun getAllContacts(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE id IN (SELECT DISTINCT conversationId FROM messages)")
    fun getConversationContacts(): List<ContactEntity>

    @Query("DELETE FROM contacts WHERE id = :id")
    fun deleteContact(id: ByteArray): Int

    @Query("DELETE FROM channels WHERE id = :id")
    fun deleteChannel(id: ByteArray): Int

    @Query("DELETE FROM contacts WHERE id IN (:ids)")
    fun deleteContacts(ids: List<ByteArray>): Int

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    fun deleteChannels(ids: List<ByteArray>): Int

    @Query("DELETE FROM contacts")
    fun deleteAllContacts()

    @Query("DELETE FROM channels")
    fun deleteAllChannels()

    @Query("UPDATE channels SET owner = :owner WHERE id = :channelId")
    fun setChannelOwner(channelId: ByteArray, owner: ByteArray): Int

    // --- Contacts revision ---
    @Query("SELECT * FROM contacts_revision WHERE id = 1")
    fun getRevisionRow(): ContactsRevisionEntity?

    @Upsert
    fun setRevisionRow(row: ContactsRevisionEntity)

    // --- Channel members ---
    @Upsert
    fun upsertMembers(members: List<ChannelMemberEntity>)

    @Query("SELECT * FROM channel_members WHERE channelId = :channelId AND id = :id")
    fun getMember(channelId: ByteArray, id: ByteArray): ChannelMemberEntity?

    @Query("SELECT * FROM channel_members WHERE channelId = :channelId AND id IN (:ids) ORDER BY joined ASC")
    fun getMembers(channelId: ByteArray, ids: List<ByteArray>): List<ChannelMemberEntity>

    @Query("SELECT * FROM channel_members WHERE channelId = :channelId ORDER BY joined ASC")
    fun getAllMembers(channelId: ByteArray): List<ChannelMemberEntity>

    @Query("DELETE FROM channel_members WHERE channelId = :channelId AND id IN (:ids)")
    fun deleteMembers(channelId: ByteArray, ids: List<ByteArray>): Int

    @Query("DELETE FROM channel_members WHERE channelId = :channelId")
    fun deleteAllMembers(channelId: ByteArray)

    @Query("UPDATE channel_members SET role = :role WHERE channelId = :channelId AND id IN (:ids)")
    fun updateMembersRole(channelId: ByteArray, ids: List<ByteArray>, role: Int): Int

    @Query("UPDATE channel_members SET role = :role WHERE channelId = :channelId AND id = :id")
    fun updateMemberRole(channelId: ByteArray, id: ByteArray, role: Int): Int

    // --- Channel invites (app-local recipient action state) ---
    @Upsert
    fun upsertChannelInvite(invite: ChannelInviteEntity)

    @Query("SELECT * FROM channel_invites WHERE messageId = :messageId")
    fun getChannelInvite(messageId: ByteArray): ChannelInviteEntity?

    @Query("SELECT * FROM channel_invites")
    fun channelInvites(): Flow<List<ChannelInviteEntity>>

    // --- Transactional composites ---

    /**
     * Records the recipient's action on a channel invite, preserving a JOINED as terminal: a later
     * IGNORED never overrides an existing JOINED (an ignored invite can still be joined until expiry).
     * Missing [channelId]/[channelName] fall back to any already-stored value, and the original
     * [ChannelInviteEntity.createdAt] is kept.
     */
    @Transaction
    fun setChannelInviteAction(
        messageId: ByteArray,
        channelId: ByteArray?,
        channelName: String?,
        action: Int,
        now: Long,
    ) {
        val existing = getChannelInvite(messageId)
        if (existing?.action == ChannelInviteEntity.ACTION_JOINED &&
            action == ChannelInviteEntity.ACTION_IGNORED
        ) {
            return
        }
        upsertChannelInvite(
            ChannelInviteEntity(
                messageId = messageId,
                channelId = channelId ?: existing?.channelId,
                channelName = channelName ?: existing?.channelName,
                action = action,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }


    @Transaction
    fun putContactWithRevision(revision: Int, contact: ContactEntity, channel: ChannelEntity?, updatedAt: Long) {
        upsertContact(contact)
        if (channel != null) upsertChannel(channel)
        setRevisionRow(ContactsRevisionEntity(revision = revision, updatedAt = updatedAt))
    }

    @Transaction
    fun putContactsWithRevision(revision: Int, contacts: List<ContactEntity>, channels: List<ChannelEntity>, updatedAt: Long) {
        contacts.forEach { upsertContact(it) }
        channels.forEach { upsertChannel(it) }
        setRevisionRow(ContactsRevisionEntity(revision = revision, updatedAt = updatedAt))
    }

    @Transaction
    fun putContactLocal(contact: ContactEntity, channel: ChannelEntity?) {
        upsertContact(contact)
        if (channel != null) upsertChannel(channel)
    }

    @Transaction
    fun removeContactWithRevision(revision: Int, id: ByteArray, updatedAt: Long): Boolean {
        val removed = deleteContact(id) > 0
        deleteChannel(id)
        deleteMessagesByConversation(id)
        setRevisionRow(ContactsRevisionEntity(revision = revision, updatedAt = updatedAt))
        return removed
    }

    @Transaction
    fun removeContactsWithRevision(revision: Int, ids: List<ByteArray>, updatedAt: Long): Boolean {
        val removed = deleteContacts(ids) > 0
        deleteChannels(ids)
        ids.forEach { deleteMessagesByConversation(it) }
        setRevisionRow(ContactsRevisionEntity(revision = revision, updatedAt = updatedAt))
        return removed
    }

    @Transaction
    fun clearContactsWithRevision(revision: Int, updatedAt: Long) {
        deleteAllChannels()
        deleteAllContacts()
        setRevisionRow(ContactsRevisionEntity(revision = revision, updatedAt = updatedAt))
    }

    @Transaction
    fun removeContactLocal(id: ByteArray): Boolean {
        val removed = deleteContact(id) > 0
        deleteChannel(id)
        deleteMessagesByConversation(id)
        return removed
    }

    @Transaction
    fun refillMembers(channelId: ByteArray, members: List<ChannelMemberEntity>) {
        deleteAllMembers(channelId)
        upsertMembers(members)
    }

    @Transaction
    fun transferOwnership(channelId: ByteArray, oldOwner: ByteArray, newOwner: ByteArray, ownerRole: Int, memberRole: Int) {
        setChannelOwner(channelId, newOwner)
        updateMemberRole(channelId, oldOwner, memberRole)
        updateMemberRole(channelId, newOwner, ownerRole)
    }
}
