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

package io.bosonnetwork.photon.feature.contacts.model

import io.bosonnetwork.photon.core.model.shortId
import io.bosonnetwork.photonmessaging.Contact
import io.bosonnetwork.photonmessaging.FriendRequest

/** UI projection of a contact (friend or channel). */
data class UiContact(
    val id: String,
    val displayName: String,
    val isChannel: Boolean,
    val muted: Boolean,
    val blocked: Boolean,
    /** The local alias/remark, if the user set one (M2-6). */
    val remark: String? = null,
    /** The profile name (without any local alias applied), when known. */
    val name: String? = null,
    /** Fetchable avatar URL, null for channels or when unresolvable. */
    val avatarUrl: String? = null,
)

/** Where a friend request stands. Accepted and expired are final. */
enum class FriendRequestStatus { PENDING, ACCEPTED, EXPIRED }

/** What the user can do with a friend request. */
enum class FriendRequestAction {
    /** Accept an incoming request (it is kept, marked accepted). */
    ACCEPT,

    /** Leave an incoming request as it is: nothing is sent and the record is not touched. */
    IGNORE,

    /** Send an outgoing request again (pending or expired), possibly with a new hello; it replaces the old one. */
    RESEND,

    /** Delete the request record; the only way a request leaves the list. */
    REMOVE,
}

/**
 * The actions a friend request offers, derived from its direction and status alone:
 * an incoming pending one can be accepted or ignored; an outgoing one that is pending or expired can be
 * resent (a new request replaces it); an accepted one, and an incoming expired one, can only be viewed.
 * Every request can be removed.
 */
fun friendRequestActions(outgoing: Boolean, status: FriendRequestStatus): List<FriendRequestAction> =
    when (status) {
        FriendRequestStatus.ACCEPTED -> listOf(FriendRequestAction.REMOVE)
        FriendRequestStatus.EXPIRED ->
            if (outgoing) listOf(FriendRequestAction.RESEND, FriendRequestAction.REMOVE)
            else listOf(FriendRequestAction.REMOVE)
        FriendRequestStatus.PENDING ->
            if (outgoing) listOf(FriendRequestAction.RESEND, FriendRequestAction.REMOVE)
            else listOf(FriendRequestAction.ACCEPT, FriendRequestAction.IGNORE, FriendRequestAction.REMOVE)
    }

/**
 * UI projection of a friend request record, incoming or outgoing, in any state. [userId] is always the
 * other user; [outgoing] says who sent it. [name]/[avatarUrl] are Director-resolved.
 */
data class UiFriendRequest(
    val userId: String,
    val hello: String,
    val outgoing: Boolean = false,
    val status: FriendRequestStatus = FriendRequestStatus.PENDING,
    /** Last change (sent, replaced or accepted), in epoch millis; the list is ordered by it. */
    val updatedAt: Long = 0,
    val name: String? = null,
    val avatarUrl: String? = null,
) {
    /** An incoming request still waiting for this user's answer: the only kind that needs attention. */
    val awaitingAnswer: Boolean
        get() = !outgoing && status == FriendRequestStatus.PENDING

    val actions: List<FriendRequestAction>
        get() = friendRequestActions(outgoing, status)
}

fun Contact.toUi(avatarUrl: String? = null): UiContact {
    val id = getId().toString()
    val remark = getRemark().orElse(null)?.takeIf { it.isNotBlank() }
    val profileName = getName().orElse(null)?.takeIf { it.isNotBlank() }
    val name = remark ?: profileName ?: shortId(id)
    return UiContact(
        id = id,
        displayName = name,
        isChannel = getType() == Contact.Type.CHANNEL,
        muted = isMuted(),
        blocked = isBlocked(),
        remark = remark,
        name = profileName,
        avatarUrl = avatarUrl,
    )
}

fun FriendRequest.toUi(): UiFriendRequest =
    UiFriendRequest(
        userId = userId.toString(),
        hello = hello ?: "",
        outgoing = isOutgoing,
        status = when {
            isAccepted -> FriendRequestStatus.ACCEPTED
            isExpired -> FriendRequestStatus.EXPIRED
            else -> FriendRequestStatus.PENDING
        },
        updatedAt = updatedAt,
    )
