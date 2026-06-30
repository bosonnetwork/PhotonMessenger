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

package io.photonmessenger.feature.contacts.model

import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.Channel

/** Who may join a channel (mirrors [Channel.Permission]). */
enum class UiChannelPermission { PUBLIC, MEMBER_INVITE, MODERATOR_INVITE, OWNER_INVITE }

/** A member's standing in a channel (mirrors [Channel.Role]). */
enum class UiChannelRole { OWNER, MODERATOR, MEMBER, BANNED }

/** UI projection of a channel and the current user's standing in it. */
data class UiChannel(
    val id: String,
    val name: String,
    val notice: String?,
    val permission: UiChannelPermission,
    val myRole: UiChannelRole,
    val memberCount: Int,
    val muted: Boolean,
) {
    val isOwner: Boolean get() = myRole == UiChannelRole.OWNER
    /** Owners and moderators may moderate members. */
    val canModerate: Boolean get() = myRole == UiChannelRole.OWNER || myRole == UiChannelRole.MODERATOR
}

/** UI projection of a single channel member. */
data class UiChannelMember(
    val id: String,
    val displayName: String,
    val role: UiChannelRole,
)

/** A channel together with its member roster, for the detail screen. */
data class UiChannelDetail(
    val channel: UiChannel,
    val members: List<UiChannelMember>,
)

fun Channel.Permission.toUi(): UiChannelPermission = when (this) {
    Channel.Permission.PUBLIC -> UiChannelPermission.PUBLIC
    Channel.Permission.MEMBER_INVITE -> UiChannelPermission.MEMBER_INVITE
    Channel.Permission.MODERATOR_INVITE -> UiChannelPermission.MODERATOR_INVITE
    Channel.Permission.OWNER_INVITE -> UiChannelPermission.OWNER_INVITE
}

fun UiChannelPermission.toBoson(): Channel.Permission = when (this) {
    UiChannelPermission.PUBLIC -> Channel.Permission.PUBLIC
    UiChannelPermission.MEMBER_INVITE -> Channel.Permission.MEMBER_INVITE
    UiChannelPermission.MODERATOR_INVITE -> Channel.Permission.MODERATOR_INVITE
    UiChannelPermission.OWNER_INVITE -> Channel.Permission.OWNER_INVITE
}

fun Channel.Role.toUi(): UiChannelRole = when (this) {
    Channel.Role.OWNER -> UiChannelRole.OWNER
    Channel.Role.MODERATOR -> UiChannelRole.MODERATOR
    Channel.Role.MEMBER -> UiChannelRole.MEMBER
    Channel.Role.BANNED -> UiChannelRole.BANNED
}

fun UiChannelRole.toBoson(): Channel.Role = when (this) {
    UiChannelRole.OWNER -> Channel.Role.OWNER
    UiChannelRole.MODERATOR -> Channel.Role.MODERATOR
    UiChannelRole.MEMBER -> Channel.Role.MEMBER
    UiChannelRole.BANNED -> Channel.Role.BANNED
}

private fun shortId(id: String): String =
    if (id.length <= 12) id else id.take(6) + "..." + id.takeLast(4)

/** Maps a channel (with its members already loaded) to a UI model for the given current user. */
fun Channel.toUi(myId: Id): UiChannel {
    val id = getId().toString()
    val name = getRemark().orElse(null) ?: getName().orElse(null) ?: shortId(id)
    val role = if (getOwnerId() == myId) {
        UiChannelRole.OWNER
    } else {
        members.firstOrNull { it.id == myId }?.role?.toUi() ?: UiChannelRole.MEMBER
    }
    return UiChannel(
        id = id,
        name = name,
        notice = getNotice().orElse(null),
        permission = getPermission().toUi(),
        myRole = role,
        memberCount = members.size,
        muted = isMuted(),
    )
}

fun Channel.Member.toUi(): UiChannelMember =
    UiChannelMember(id = id.toString(), displayName = displayName, role = role.toUi())
