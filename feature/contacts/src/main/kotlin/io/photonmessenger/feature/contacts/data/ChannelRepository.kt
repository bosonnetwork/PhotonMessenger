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
import io.photonmessenger.feature.contacts.model.UiChannel
import io.photonmessenger.feature.contacts.model.UiChannelDetail
import io.photonmessenger.feature.contacts.model.UiChannelMember
import io.photonmessenger.feature.contacts.model.UiChannelPermission
import io.photonmessenger.feature.contacts.model.UiChannelRole
import io.photonmessenger.feature.contacts.model.toBoson
import io.photonmessenger.feature.contacts.model.toUi
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.Channel
import io.bosonnetwork.photonmessaging.ChannelListener
import io.bosonnetwork.photonmessaging.Contact
import io.bosonnetwork.photonmessaging.ContactListener
import io.bosonnetwork.photonmessaging.InviteTicket
import io.bosonnetwork.photonmessaging.MessagingClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Channel creation, membership and moderation over the Boson [MessagingClient] (spec 1.2, screen 5
 * Channels tab, M4). Returns UI models so the ViewModel stays decoupled from the client types.
 *
 * Open invite-ticket sharing uses [InviteTicket]'s stable string codec ([InviteTicket.toString] /
 * [InviteTicket.fromString]): [invite] returns a shareable ticket string (bearer when no invitee is
 * given) and [joinChannel] consumes one, so a channel can be joined across two clients (M4-2).
 */
interface ChannelRepository {
    /** Live list of channels the user belongs to (filtered from contacts, kept current by listeners). */
    fun channels(): Flow<List<UiChannel>>

    /** Live channel + member roster for one channel; refreshed on channel events. */
    fun channelDetail(channelId: String): Flow<UiChannelDetail>

    suspend fun createChannel(
        name: String,
        notice: String?,
        permission: UiChannelPermission,
        announce: Boolean,
    ): Result<String>

    /** Invites [inviteeId] (when non-null) and returns the resulting shareable ticket string. */
    suspend fun invite(channelId: String, inviteeId: String?): Result<String>

    /** Joins a channel from a shareable invite-ticket string; returns the joined channel id. */
    suspend fun joinChannel(ticket: String): Result<String>
    suspend fun leave(channelId: String): Result<Unit>
    suspend fun remove(channelId: String): Result<Unit>
    suspend fun setRole(channelId: String, memberId: String, role: UiChannelRole): Result<Unit>
    suspend fun ban(channelId: String, memberId: String): Result<Unit>
    suspend fun unban(channelId: String, memberId: String): Result<Unit>
    suspend fun kick(channelId: String, memberId: String): Result<Unit>
    suspend fun transferOwnership(channelId: String, newOwnerId: String): Result<Unit>
    suspend fun updateInfo(channelId: String, name: String?, notice: String?): Result<Unit>

    /** Rotates the channel's session key (owner-only; M4-5). */
    suspend fun rotateSessionKey(channelId: String): Result<Unit>
}

@Singleton
class ChannelRepositoryImpl @Inject constructor(
    private val session: BosonSessionManager,
) : ChannelRepository {

    private fun client(): MessagingClient =
        session.messagingClient ?: throw AppError.Network("Not connected to the messaging service")

    override fun channels(): Flow<List<UiChannel>> = callbackFlow {
        val client = session.messagingClient
        if (client == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val myId = client.userId

        suspend fun snapshot(): List<UiChannel> {
            val result = mutableListOf<UiChannel>()
            for (channel in client.getContacts().awaitResult().filterIsInstance<Channel>()) {
                channel.loadMembers().awaitResult()
                result += channel.toUi(myId)
            }
            return result
        }

        trySend(snapshot())

        // Channel + contact events both affect the list (membership, info, add/remove).
        val refresh: () -> Unit = { launch { trySend(snapshot()) } }
        val channelListener = refreshingChannelListener(refresh)
        val contactListener = object : ContactListener {
            override fun onContactAdded(contact: Contact) = refresh()
            override fun onContactsUpdated(contacts: List<Contact>) = refresh()
            override fun onContactsRemoved(contactIds: List<Id>) = refresh()
            override fun onContactsCleared() = refresh()
        }
        client.addChannelListener(channelListener)
        client.addContactListener(contactListener)
        awaitClose {
            client.removeChannelListener(channelListener)
            client.removeContactListener(contactListener)
        }
    }

    override fun channelDetail(channelId: String): Flow<UiChannelDetail> = callbackFlow {
        val client = client()
        val myId = client.userId
        val id = parseId(channelId)

        suspend fun snapshot(): UiChannelDetail? {
            val channel = client.getContact(id).awaitResult().orElse(null) as? Channel ?: return null
            channel.loadMembers().awaitResult()
            return UiChannelDetail(
                channel = channel.toUi(myId),
                members = channel.members.map { it.toUi(myId) },
            )
        }

        snapshot()?.let { trySend(it) }

        val refresh: () -> Unit = { launch { snapshot()?.let { trySend(it) } } }
        val listener = refreshingChannelListener(refresh)
        client.addChannelListener(listener)
        awaitClose { client.removeChannelListener(listener) }
    }

    override suspend fun createChannel(
        name: String,
        notice: String?,
        permission: UiChannelPermission,
        announce: Boolean,
    ): Result<String> = runCatching {
        client().createChannel(permission.toBoson(), name, notice, announce).awaitResult().id.toString()
    }

    override suspend fun invite(channelId: String, inviteeId: String?): Result<String> = runCatching {
        val invitee = inviteeId?.let { parseId(it) }
        client().createInviteTicket(parseId(channelId), invitee).awaitResult().toString()
    }

    override suspend fun joinChannel(ticket: String): Result<String> = runCatching {
        val parsed = try {
            InviteTicket.fromString(ticket.trim())
        } catch (e: Exception) {
            throw AppError.InvalidInput("Invalid invite ticket", e)
        }
        client().joinChannel(parsed).awaitResult().id.toString()
    }

    override suspend fun leave(channelId: String): Result<Unit> = runCatching {
        client().leaveChannel(parseId(channelId)).awaitResult()
        Unit
    }

    override suspend fun remove(channelId: String): Result<Unit> = runCatching {
        client().removeChannel(parseId(channelId)).awaitResult()
        Unit
    }

    override suspend fun setRole(channelId: String, memberId: String, role: UiChannelRole): Result<Unit> = runCatching {
        client().setChannelMembersRole(parseId(channelId), listOf(parseId(memberId)), role.toBoson()).awaitResult()
        Unit
    }

    override suspend fun ban(channelId: String, memberId: String): Result<Unit> = runCatching {
        client().banChannelMembers(parseId(channelId), listOf(parseId(memberId))).awaitResult()
        Unit
    }

    override suspend fun unban(channelId: String, memberId: String): Result<Unit> = runCatching {
        client().unbanChannelMembers(parseId(channelId), listOf(parseId(memberId))).awaitResult()
        Unit
    }

    override suspend fun kick(channelId: String, memberId: String): Result<Unit> = runCatching {
        client().removeChannelMembers(parseId(channelId), listOf(parseId(memberId))).awaitResult()
        Unit
    }

    override suspend fun transferOwnership(channelId: String, newOwnerId: String): Result<Unit> = runCatching {
        client().transferChannelOwnership(parseId(channelId), parseId(newOwnerId)).awaitResult()
        Unit
    }

    override suspend fun updateInfo(channelId: String, name: String?, notice: String?): Result<Unit> = runCatching {
        val client = client()
        val channel = client.getContact(parseId(channelId)).awaitResult().orElse(null) as? Channel
            ?: throw AppError.NotFound("Channel not found")
        var editor = channel.editChannel()
        if (name != null) editor = editor.setName(name)
        if (notice != null) editor = editor.setNotice(notice)
        client.updateChannelInfo(editor.build()).awaitResult()
        Unit
    }

    override suspend fun rotateSessionKey(channelId: String): Result<Unit> = runCatching {
        client().rotateChannelSessionKey(parseId(channelId)).awaitResult()
        Unit
    }

    /** A [ChannelListener] that calls [refresh] on every relevant event. */
    private fun refreshingChannelListener(refresh: () -> Unit) = object : ChannelListener {
        override fun onChannelCreated(channel: Channel) = refresh()
        override fun onChannelDeleted(channel: Channel) = refresh()
        override fun onJoinedChannel(channel: Channel) = refresh()
        override fun onLeftChannel(channel: Channel) = refresh()
        override fun onChannelOwnershipTransferred(channel: Channel, oldOwner: Id, newOwner: Id) = refresh()
        override fun onChannelSessionKeyRotated(channel: Channel) = refresh()
        override fun onChannelUpdated(channel: Channel) = refresh()
        override fun onChannelMemberJoined(channel: Channel, member: Channel.Member) = refresh()
        override fun onChannelMemberLeft(channel: Channel, member: Channel.Member) = refresh()
        override fun onChannelMembersRemoved(channel: Channel, members: List<Channel.Member>) = refresh()
        override fun onChannelMembersBanned(channel: Channel, banned: List<Channel.Member>) = refresh()
        override fun onChannelMembersUnbanned(channel: Channel, unbanned: List<Channel.Member>) = refresh()
        override fun onChannelMembersRoleChanged(channel: Channel, changed: List<Channel.Member>, role: Channel.Role) = refresh()
    }

    private fun parseId(text: String): Id =
        try {
            Id.of(text.trim())
        } catch (e: Exception) {
            throw AppError.InvalidInput("Invalid Boson ID", e)
        }
}
