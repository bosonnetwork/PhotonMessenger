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

package io.photonmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities mirroring the Photon messaging client storage model (see `1_initial_schema.sql`).
 * Ids are stored as their raw bytes; the store maps to/from Boson `Id`. This is the app's own Room
 * database implementing `MessagingStore` (Option A) - independent of any client SQL schema.
 */

@Entity(tableName = "contacts_revision")
data class ContactsRevisionEntity(
    @PrimaryKey val id: Int = 1,
    val revision: Int,
    val updatedAt: Long,
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: ByteArray,
    val type: Int,
    val sessionKey: ByteArray?,
    val name: String?,
    val remark: String?,
    val tags: String?,
    val muted: Boolean,
    val blocked: Boolean,
    val revision: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?) = this === other || (other is ContactEntity && id.contentEquals(other.id))
    override fun hashCode() = id.contentHashCode()
}

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: ByteArray,
    val owner: ByteArray,
    val permission: Int,
    val notice: String?,
    val announce: Boolean,
) {
    override fun equals(other: Any?) = this === other || (other is ChannelEntity && id.contentEquals(other.id))
    override fun hashCode() = id.contentHashCode()
}

@Entity(
    tableName = "channel_members",
    primaryKeys = ["id", "channelId"],
    indices = [Index(value = ["channelId", "joined"])],
)
data class ChannelMemberEntity(
    val id: ByteArray,
    val channelId: ByteArray,
    val role: Int,
    val joined: Long,
) {
    override fun equals(other: Any?) = this === other ||
        (other is ChannelMemberEntity && id.contentEquals(other.id) && channelId.contentEquals(other.channelId))
    override fun hashCode() = 31 * id.contentHashCode() + channelId.contentHashCode()
}

@Entity(tableName = "friend_requests")
data class FriendRequestEntity(
    @PrimaryKey val id: ByteArray,
    val initiator: ByteArray,
    val hello: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val accepted: Boolean,
    val acceptedAt: Long,
) {
    override fun equals(other: Any?) = this === other || (other is FriendRequestEntity && id.contentEquals(other.id))
    override fun hashCode() = id.contentHashCode()
}

@Entity(
    tableName = "messages",
    indices = [Index(value = ["id"], unique = true), Index(value = ["conversationId"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rid: Long = 0,
    val id: ByteArray,
    val conversationId: ByteArray,
    val version: Int,
    val recipient: ByteArray,
    val type: Int,
    val fromId: ByteArray?,
    val createdAt: Long,
    val contentType: String?,
    val contentDisposition: String?,
    val payload: ByteArray?,
    val sentAt: Long,
    val receivedAt: Long,
) {
    override fun equals(other: Any?) = this === other || (other is MessageEntity && rid == other.rid)
    override fun hashCode() = rid.hashCode()
}
