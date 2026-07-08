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

/** UI projection of an incoming friend request. [name]/[avatarUrl] are Director-resolved. */
data class UiFriendRequest(
    val userId: String,
    val hello: String,
    val name: String? = null,
    val avatarUrl: String? = null,
)

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
    UiFriendRequest(userId = getUserId().toString(), hello = getHello() ?: "")

private fun shortId(id: String): String =
    if (id.length <= 12) id else id.take(6) + "..." + id.takeLast(4)
