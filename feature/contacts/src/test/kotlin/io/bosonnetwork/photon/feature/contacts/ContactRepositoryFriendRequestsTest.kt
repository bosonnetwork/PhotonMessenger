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

import android.content.Context
import app.cash.turbine.test
import io.bosonnetwork.Id
import io.bosonnetwork.photon.core.boson.BosonSessionManager
import io.bosonnetwork.photon.feature.contacts.data.ContactRepositoryImpl
import io.bosonnetwork.photon.feature.contacts.model.FriendRequestAction
import io.bosonnetwork.photon.feature.contacts.model.FriendRequestStatus
import io.bosonnetwork.photonmessaging.Contact
import io.bosonnetwork.photonmessaging.FriendRequest
import io.bosonnetwork.photonmessaging.MessagingClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository over a messaging client that keeps its request records the way the library does
 * (one per user; accepting marks it, only removing deletes it), checking that the app lists every
 * record and that no action but Remove deletes one.
 */
class ContactRepositoryFriendRequestsTest {

    private val me = Id.random()
    private val alice = Id.random()
    private val bob = Id.random()
    private val carol = Id.random()
    private val dave = Id.random()

    /** The client's stored records, by the other user's id. */
    private val records = linkedMapOf<Id, FriendRequest>()

    /** The client's contacts; blocking a user adds a blocked one, as the library does for a stranger. */
    private val storedContacts = mutableListOf<Contact>()

    private fun blockedContact(id: Id): Contact = mockk(relaxed = true) {
        every { this@mockk.id } returns id
        every { type } returns Contact.Type.AUTO
        every { isBlocked } returns true
    }

    private val client = mockk<MessagingClient>(relaxed = true) {
        every { getFriendRequests() } answers { CompletableFuture.completedFuture(records.values.toList()) }
        every { getContacts() } answers { CompletableFuture.completedFuture(storedContacts.toList()) }
        every { blockUser(any()) } answers {
            val contact = blockedContact(firstArg())
            storedContacts += contact
            CompletableFuture.completedFuture(contact)
        }
        every { acceptFriendRequest(any()) } answers {
            val id = firstArg<Id>()
            val fr = records.getValue(id)
            records[id] = TestFriendRequest(id, fr.initiatorId, fr.hello, accepted = true, updatedAt = fr.updatedAt + 1)
            CompletableFuture.completedFuture(null)
        }
        every { removeFriendRequest(any()) } answers {
            CompletableFuture.completedFuture(records.remove(firstArg<Id>()) != null)
        }
        every { friendRequest(any(), any()) } answers {
            val id = firstArg<Id>()
            records[id] = TestFriendRequest(id, me, secondArg(), updatedAt = 10_000)
            CompletableFuture.completedFuture(null)
        }
    }

    private val session = mockk<BosonSessionManager> {
        every { client } returns MutableStateFlow(this@ContactRepositoryFriendRequestsTest.client)
        every { messagingClient } returns this@ContactRepositoryFriendRequestsTest.client
    }

    private val repository = ContactRepositoryImpl(session, mockk<Context>(relaxed = true))

    @Test
    fun `lists every record whatever its direction or state, newest change first`() = runTest {
        records[alice] = TestFriendRequest(alice, alice, updatedAt = 1)
        records[bob] = TestFriendRequest(bob, me, updatedAt = 2)
        records[carol] = TestFriendRequest(carol, carol, accepted = true, updatedAt = 3)
        records[dave] = TestFriendRequest(dave, me, expired = true, updatedAt = 4)

        repository.friendRequests().test {
            val list = awaitItem()
            assertEquals(listOf(dave, carol, bob, alice).map { it.toString() }, list.map { it.userId })
            assertEquals(
                listOf(
                    true to FriendRequestStatus.EXPIRED,
                    false to FriendRequestStatus.ACCEPTED,
                    true to FriendRequestStatus.PENDING,
                    false to FriendRequestStatus.PENDING,
                ),
                list.map { it.outgoing to it.status },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `accepting keeps the request, now accepted`() = runTest {
        records[alice] = TestFriendRequest(alice, alice)

        repository.friendRequests().test {
            assertEquals(FriendRequestStatus.PENDING, awaitItem().single().status)

            assertTrue(repository.acceptFriendRequest(alice.toString()).isSuccess)
            val after = awaitItem().single()
            assertEquals(alice.toString(), after.userId)
            assertEquals(FriendRequestStatus.ACCEPTED, after.status)
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 1) { client.acceptFriendRequest(alice) }
        verify(exactly = 0) { client.removeFriendRequest(any()) }
        verify(exactly = 0) { client.removeFriendRequests(any()) }
    }

    @Test
    fun `removing is what deletes a request`() = runTest {
        records[alice] = TestFriendRequest(alice, alice, accepted = true)
        records[bob] = TestFriendRequest(bob, me, expired = true)

        repository.friendRequests().test {
            assertEquals(2, awaitItem().size)

            assertTrue(repository.removeFriendRequest(bob.toString()).isSuccess)
            assertEquals(listOf(alice.toString()), awaitItem().map { it.userId })
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 1) { client.removeFriendRequest(bob) }
    }

    @Test
    fun `sending lists the new outgoing request, resending replaces it`() = runTest {
        repository.friendRequests().test {
            assertEquals(emptyList<Any>(), awaitItem())

            assertTrue(repository.sendFriendRequest(bob.toString(), "hi").isSuccess)
            val sent = awaitItem().single()
            assertTrue(sent.outgoing)
            assertEquals("hi", sent.hello)

            assertTrue(repository.sendFriendRequest(bob.toString(), "hi again").isSuccess)
            val resent = awaitItem().single()
            assertEquals(bob.toString(), resent.userId)
            assertEquals("hi again", resent.hello)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `resending an expired request makes it pending again`() = runTest {
        records[bob] = TestFriendRequest(bob, me, hello = "hi", expired = true)

        repository.friendRequests().test {
            assertEquals(FriendRequestStatus.EXPIRED, awaitItem().single().status)

            assertTrue(repository.sendFriendRequest(bob.toString(), "hi again").isSuccess)
            val resent = awaitItem().single()
            assertTrue(resent.outgoing)
            assertEquals(FriendRequestStatus.PENDING, resent.status)
            assertEquals("hi again", resent.hello)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `users known only as blocked are not listed as contacts`() = runTest {
        val friend = mockk<Contact>(relaxed = true) {
            every { id } returns alice
            every { type } returns Contact.Type.FRIEND
            every { name } returns Optional.of("Alice")
            every { remark } returns Optional.empty()
        }
        val blockedStranger = mockk<Contact>(relaxed = true) {
            every { id } returns bob
            every { type } returns Contact.Type.AUTO
            every { isBlocked } returns true
            every { name } returns Optional.empty()
            every { remark } returns Optional.empty()
        }
        every { client.getContacts() } returns CompletableFuture.completedFuture(listOf(friend, blockedStranger))

        repository.contacts().test {
            assertEquals(listOf(alice.toString()), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `blocking keeps the request, shown as blocked with only remove left`() = runTest {
        records[alice] = TestFriendRequest(alice, alice)

        repository.friendRequests().test {
            val before = awaitItem().single()
            assertTrue(FriendRequestAction.BLOCK in before.actions)
            assertTrue(before.awaitingAnswer)

            assertTrue(repository.blockUser(alice.toString()).isSuccess)
            val after = awaitItem().single()
            assertEquals(alice.toString(), after.userId)
            assertTrue(after.blocked)
            assertEquals(FriendRequestStatus.PENDING, after.status)
            assertEquals(listOf(FriendRequestAction.REMOVE), after.actions)
            assertEquals(false, after.awaitingAnswer)
            cancelAndIgnoreRemainingEvents()
        }
        verify(exactly = 1) { client.blockUser(alice) }
        verify(exactly = 0) { client.removeFriendRequest(any()) }
    }
}
