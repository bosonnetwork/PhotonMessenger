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
)

/** UI projection of an incoming friend request. */
data class UiFriendRequest(
    val userId: String,
    val hello: String,
)

fun Contact.toUi(): UiContact {
    val id = getId().toString()
    val name = getRemark().orElse(null) ?: getName().orElse(null) ?: shortId(id)
    return UiContact(
        id = id,
        displayName = name,
        isChannel = getType() == Contact.Type.CHANNEL,
        muted = isMuted(),
        blocked = isBlocked(),
    )
}

fun FriendRequest.toUi(): UiFriendRequest =
    UiFriendRequest(userId = getUserId().toString(), hello = getHello() ?: "")

private fun shortId(id: String): String =
    if (id.length <= 12) id else id.take(6) + "…" + id.takeLast(4)
