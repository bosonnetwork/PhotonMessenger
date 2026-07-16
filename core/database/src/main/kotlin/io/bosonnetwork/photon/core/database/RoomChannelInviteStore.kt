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

import io.bosonnetwork.photon.core.database.entity.ChannelInviteEntity
import io.bosonnetwork.photon.core.model.ChannelInviteStore
import io.bosonnetwork.photon.core.model.InviteAction
import io.bosonnetwork.Id
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [ChannelInviteStore]. The recipient's per-invite action is a row keyed by the invite
 * message id; joining also records the resolved channel id/name so the state is queryable, and a
 * JOINED row is terminal (a later ignore never overrides it - see [MessagingDao.setChannelInviteAction]).
 * Boson `Id`s are stored as their raw bytes, matching the messaging entities.
 */
class RoomChannelInviteStore(
    db: PhotonDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChannelInviteStore {

    private val dao: MessagingDao = db.messagingDao()

    override fun actions(): Flow<Map<String, InviteAction>> =
        dao.channelInvites().map { rows ->
            rows.associate { Id.of(it.messageId).toString() to it.action.toInviteAction() }
        }

    override suspend fun setAction(
        messageId: String,
        action: InviteAction,
        channelId: String?,
        channelName: String?,
    ) {
        withContext(ioDispatcher) {
            dao.setChannelInviteAction(
                messageId = Id.of(messageId).bytesUnsafe(),
                channelId = channelId?.let { Id.of(it).bytesUnsafe() },
                channelName = channelName,
                action = action.toActionInt(),
                now = System.currentTimeMillis(),
            )
        }
    }

    private fun InviteAction.toActionInt(): Int = when (this) {
        InviteAction.JOINED -> ChannelInviteEntity.ACTION_JOINED
        InviteAction.IGNORED -> ChannelInviteEntity.ACTION_IGNORED
    }

    private fun Int.toInviteAction(): InviteAction =
        if (this == ChannelInviteEntity.ACTION_JOINED) InviteAction.JOINED else InviteAction.IGNORED
}
