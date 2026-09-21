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

package io.bosonnetwork.photon.feature.contacts

import io.bosonnetwork.Id
import io.bosonnetwork.photon.feature.contacts.model.FriendRequestAction.ACCEPT
import io.bosonnetwork.photon.feature.contacts.model.FriendRequestAction.IGNORE
import io.bosonnetwork.photon.feature.contacts.model.FriendRequestAction.REMOVE
import io.bosonnetwork.photon.feature.contacts.model.FriendRequestAction.RESEND
import io.bosonnetwork.photon.feature.contacts.model.FriendRequestStatus
import io.bosonnetwork.photon.feature.contacts.model.friendRequestActions
import io.bosonnetwork.photon.feature.contacts.model.toUi
import io.bosonnetwork.photonmessaging.FriendRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A stored friend request as the messaging client reports it; [userId] is always the other side. */
internal class TestFriendRequest(
    private val userId: Id,
    private val initiatorId: Id,
    private val hello: String? = "hi",
    private val accepted: Boolean = false,
    private val expired: Boolean = false,
    private val updatedAt: Long = 1_000,
) : FriendRequest {
    override fun getUserId() = userId
    override fun getInitiatorId() = initiatorId
    override fun getHello() = hello
    override fun isAccepted() = accepted
    override fun isExpired() = expired
    override fun getCreatedAt() = updatedAt
    override fun getAcceptedAt() = if (accepted) updatedAt else 0
    override fun getUpdatedAt() = updatedAt
}

class FriendRequestModelsTest {

    @Test
    fun `actions follow direction and state`() {
        assertEquals(listOf(ACCEPT, IGNORE, REMOVE), friendRequestActions(outgoing = false, FriendRequestStatus.PENDING))
        assertEquals(listOf(RESEND, REMOVE), friendRequestActions(outgoing = true, FriendRequestStatus.PENDING))
        // Accepted is final whichever side sent it: view and remove only.
        for (outgoing in listOf(true, false)) {
            assertEquals(listOf(REMOVE), friendRequestActions(outgoing, FriendRequestStatus.ACCEPTED))
        }
        // Expired cannot be accepted; the sender can still try again with a new request.
        assertEquals(listOf(REMOVE), friendRequestActions(outgoing = false, FriendRequestStatus.EXPIRED))
        assertEquals(listOf(RESEND, REMOVE), friendRequestActions(outgoing = true, FriendRequestStatus.EXPIRED))
    }

    @Test
    fun `every request can be removed and nothing else removes one`() {
        for (outgoing in listOf(true, false)) {
            for (status in FriendRequestStatus.entries) {
                assertTrue(REMOVE in friendRequestActions(outgoing, status))
            }
        }
    }

    @Test
    fun `direction comes from the initiator`() {
        val other = Id.random()
        val self = Id.random()

        val incoming = TestFriendRequest(userId = other, initiatorId = other).toUi()
        assertFalse(incoming.outgoing)
        assertEquals(other.toString(), incoming.userId)

        val outgoing = TestFriendRequest(userId = other, initiatorId = self).toUi()
        assertTrue(outgoing.outgoing)
        assertEquals(other.toString(), outgoing.userId)
    }

    @Test
    fun `state maps to status, accepted before expired`() {
        val other = Id.random()
        assertEquals(FriendRequestStatus.PENDING, TestFriendRequest(other, other).toUi().status)
        assertEquals(FriendRequestStatus.ACCEPTED, TestFriendRequest(other, other, accepted = true).toUi().status)
        assertEquals(FriendRequestStatus.EXPIRED, TestFriendRequest(other, other, expired = true).toUi().status)
    }

    @Test
    fun `only an incoming pending request awaits an answer`() {
        val other = Id.random()
        val self = Id.random()
        assertTrue(TestFriendRequest(other, other).toUi().awaitingAnswer)
        assertFalse(TestFriendRequest(other, self).toUi().awaitingAnswer)
        assertFalse(TestFriendRequest(other, other, accepted = true).toUi().awaitingAnswer)
        assertFalse(TestFriendRequest(other, other, expired = true).toUi().awaitingAnswer)
    }

    @Test
    fun `a missing hello maps to empty`() {
        val other = Id.random()
        assertEquals("", TestFriendRequest(other, other, hello = null).toUi().hello)
    }
}
