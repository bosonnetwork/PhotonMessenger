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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.Channel
import io.bosonnetwork.photonmessaging.Contact
import io.bosonnetwork.photonmessaging.MessagingStore
import io.bosonnetwork.photonmessaging.MessagingStore.StoredChannel
import io.bosonnetwork.photonmessaging.MessagingStore.StoredChannelMember
import io.bosonnetwork.photonmessaging.MessagingStore.StoredContact
import io.bosonnetwork.photonmessaging.MessagingStore.StoredFriendRequest
import io.bosonnetwork.photonmessaging.MessagingStore.StoredMessage
import io.vertx.core.Future
import io.vertx.core.Vertx
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device validation of the native Room [MessagingStore] (Option A). Exercises the contract
 * end-to-end: schema creation, message rid assignment + pagination, conversation MAX(rid) derivation,
 * atomic contacts-revision writes, channel members, and friend requests.
 */
@RunWith(AndroidJUnit4::class)
class RoomMessagingStoreTest {

    private lateinit var vertx: Vertx
    private lateinit var db: PhotonDatabase
    private lateinit var store: MessagingStore

    private fun <T> Future<T>.await(): T =
        toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)

    private val friendType = Contact.Type.FRIEND.value()
    private val channelType = Contact.Type.CHANNEL.value()
    private val memberRole = Channel.Role.MEMBER.value()
    private val ownerRole = Channel.Role.OWNER.value()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        vertx = Vertx.vertx()
        db = Room.inMemoryDatabaseBuilder(context, PhotonDatabase::class.java).build()
        store = RoomMessagingStore(vertx, db)
        store.open().await()
    }

    @After
    fun tearDown() {
        db.close()
        vertx.close()
    }

    private fun friend(id: Id, name: String, revision: Int = 1) = StoredContact(
        id, friendType, ByteArray(32) { 1 }, name, null, null, false, false, revision,
        1_000L, 2_000L, null,
    )

    private fun message(conversationId: Id, createdAt: Long, text: String) = StoredMessage(
        0, Id.random(), conversationId, 1, conversationId, 1, null, createdAt,
        "text/plain", null, text.toByteArray(), 0, createdAt,
    )

    @Test
    fun messageRidIsAssignedAndPaginates() {
        val convo = Id.random()
        val rid1 = store.putMessage(message(convo, 100, "a")).await()
        val rid2 = store.putMessage(message(convo, 200, "b")).await()
        val rid3 = store.putMessage(message(convo, 300, "c")).await()
        assertTrue(rid1 < rid2 && rid2 < rid3)

        val before = store.getMessagesBefore(convo, 300, 2, 0).await()
        assertEquals(2, before.size)
        // newest first
        assertEquals(rid3, before[0].rid())
        assertEquals(rid2, before[1].rid())

        val range = store.getMessagesInRange(convo, 100, 300).await()
        // [begin, end) excludes 300
        assertEquals(2, range.size)
        assertEquals(rid1, range[0].rid())
    }

    @Test
    fun conversationUsesLatestMessage() {
        val a = Id.random()
        val b = Id.random()
        store.putContactLocally(friend(a, "Alice")).await()
        store.putContactLocally(friend(b, "Bob")).await()
        store.putMessage(message(a, 100, "hi a")).await()
        store.putMessage(message(b, 150, "hi b")).await()
        val lastA = store.putMessage(message(a, 500, "newest a")).await()

        val convA = store.getConversation(a).await()
        assertNotNull(convA)
        assertEquals(lastA, convA!!.lastMessage()!!.rid())
        assertEquals("Alice", convA.contact().name())

        val all = store.getAllConversations().await()
        assertEquals(2, all.size)
        // ordered by latest message received_at desc -> Alice (500) before Bob (150)
        assertEquals(a, all[0].contact().id())
        assertEquals(b, all[1].contact().id())
    }

    @Test
    fun contactsRevisionIsAtomicWithWrites() {
        assertEquals(0, store.getContactsRevision().await())
        val a = Id.random()
        store.putContact(5, friend(a, "Alice", revision = 5)).await()
        assertEquals(5, store.getContactsRevision().await())
        assertEquals("Alice", store.getContact(a).await()!!.name())

        store.removeContacts(6, listOf(a)).await()
        assertEquals(6, store.getContactsRevision().await())
        assertNull(store.getContact(a).await())
    }

    @Test
    fun channelRoundTripsWithMembersAndOwnershipTransfer() {
        val channelId = Id.random()
        val owner = Id.random()
        val other = Id.random()
        val channelContact = StoredContact(
            channelId, channelType, ByteArray(32) { 2 }, "Team", null, null, false, false, 1,
            1_000L, 2_000L, StoredChannel(channelId, owner, 0, "notice", false),
        )
        store.putContactLocally(channelContact).await()

        val read = store.getContact(channelId).await()
        assertNotNull(read!!.channel())
        assertEquals(owner, read.channel()!!.owner())

        store.putChannelMembers(channelId, listOf(
            StoredChannelMember(owner, channelId, ownerRole, 10),
            StoredChannelMember(other, channelId, memberRole, 20),
        )).await()
        assertEquals(2, store.getAllChannelMembers(channelId).await().size)

        store.updateChannelOwnership(channelId, owner, other).await()
        assertEquals(memberRole, store.getChannelMember(channelId, owner).await()!!.role())
        assertEquals(ownerRole, store.getChannelMember(channelId, other).await()!!.role())
        assertEquals(other, store.getContact(channelId).await()!!.channel()!!.owner())
    }

    @Test
    fun friendRequestsRoundTrip() {
        val user = Id.random()
        store.putFriendRequest(
            StoredFriendRequest(user, Id.random(), "hello!", 1L, 2L, false, 0L),
        ).await()
        assertEquals("hello!", store.getFriendRequest(user).await()!!.hello())
        assertEquals(1, store.getFriendRequests().await().size)

        assertTrue(store.removeFriendRequest(user).await())
        assertNull(store.getFriendRequest(user).await())
    }

    @Test
    fun removingConversationDeletesMessages() {
        val convo = Id.random()
        store.putMessage(message(convo, 100, "x")).await()
        assertTrue(store.removeMessagesByConversation(convo).await())
        assertTrue(store.getMessagesBefore(convo, Long.MAX_VALUE, 10, 0).await().isEmpty())
        assertNull(store.getConversation(convo).await())
        assertFalse(store.removeMessagesByConversation(convo).await())
    }
}
