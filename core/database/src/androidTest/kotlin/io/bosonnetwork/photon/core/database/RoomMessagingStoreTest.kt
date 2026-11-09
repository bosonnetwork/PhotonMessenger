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
import io.bosonnetwork.photonmessaging.DeliveryStatus
import io.bosonnetwork.photonmessaging.MessageDeliveryReceipt
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
        // Room tests need no classpath file resolution; Android's default JVM tmpdir may point
        // at the instrumentation package rather than the target application's writable cache.
        vertx = Vertx.vertx(io.vertx.core.VertxOptions().setFileSystemOptions(
            io.vertx.core.file.FileSystemOptions().setClassPathResolvingEnabled(false),
        ))
        db = Room.inMemoryDatabaseBuilder(context, PhotonDatabase::class.java).build()
        store = RoomMessagingStore(vertx, db)
        store.open().await()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::vertx.isInitialized) vertx.close().await()
    }

    private fun friend(id: Id, name: String, revision: Int = 1) = StoredContact(
        id, friendType, ByteArray(32) { 1 }, name, null, null, false, false, revision,
        1_000L, 2_000L, null,
    )

    private fun message(
        conversationId: Id,
        createdAt: Long,
        text: String,
        status: DeliveryStatus = DeliveryStatus.UNDELIVERED,
        reason: String? = null,
        deliveryAt: Long = 0,
        recipient: Id = conversationId,
    ) = StoredMessage(
        0, Id.random(), conversationId, 1, recipient, 1, null, createdAt,
        "text/plain", null, text.toByteArray(), 0, createdAt, status, reason, deliveryAt,
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
    fun deliveryOutcomesRoundTripThroughAllMessageReads() {
        val conversation = Id.random()
        store.putContactLocally(friend(conversation, "Bob")).await()
        DeliveryStatus.values().forEach { status ->
            val createdAt = 100L + status.value()
            val reason = if (status.value() > 1) "delivery reason" else null
            val deliveryAt = if (status == DeliveryStatus.UNDELIVERED) 0L else 456L
            val original = message(conversation, createdAt, "hello", status, reason, deliveryAt)
            assertFalse(store.existsMessage(original.id()).await())
            store.putMessage(original).await()
            assertTrue(store.existsMessage(original.id()).await())

            val reads = listOf(
                store.getMessage(original.id()).await()!!,
                store.getMessagesBefore(conversation, Long.MAX_VALUE, 1, 0).await().single(),
                store.getMessagesInRange(conversation, createdAt, createdAt + 1).await().single(),
                store.getConversation(conversation).await()!!.lastMessage()!!,
                store.getAllConversations().await().single().lastMessage()!!,
            )
            reads.forEach { saved ->
                assertEquals(original.id(), saved.id())
                assertEquals(status, saved.deliveryStatus())
                assertEquals(reason, saved.deliveryReason())
                assertEquals(deliveryAt, saved.deliveryAt())
            }
        }
    }

    @Test
    fun deliveryUpdateReturnsConversationAndPreservesReceiptOnSentTimeUpdate() {
        val conversation = Id.random()
        val original = message(conversation, 123L, "hello", recipient = Id.random())
        assertNull(store.updateMessageDeliveryStatus(original.id(), DeliveryStatus.EXPIRED, "expired", 456L).await())
        val rid = store.putMessage(original).await()
        repeat(2) {
            assertEquals(conversation,
                store.updateMessageDeliveryStatus(original.id(), DeliveryStatus.EXPIRED, "expired", 456L).await())
        }
        store.updateMessageSentTime(original.id(), 789L).await()
        val saved = store.getMessage(original.id()).await()!!
        assertEquals(789L, saved.sentAt())
        assertEquals(DeliveryStatus.EXPIRED, saved.deliveryStatus())
        assertEquals("expired", saved.deliveryReason())
        assertEquals(456L, saved.deliveryAt())

        assertTrue(store.removeMessage(rid).await())
        assertFalse(store.existsMessage(original.id()).await())
        assertNull(store.getMessage(original.id()).await())
        assertNull(store.updateMessageDeliveryStatus(original.id(), DeliveryStatus.DELIVERED, null, 900L).await())
    }

    @Test
    fun receiptSurvivesDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "room-receipt-reopen-test.db"
        context.deleteDatabase(name)
        val original = message(Id.random(), 123L, "preserved")
        val first = PhotonDatabase.create(context, name)
        try {
            val firstStore = RoomMessagingStore(vertx, first)
            firstStore.putMessage(original).await()
            firstStore.updateMessageDeliveryStatus(original.id(), DeliveryStatus.DELIVERED, null, 456L).await()
        } finally {
            first.close()
        }
        val reopened = PhotonDatabase.create(context, name)
        try {
            val restored = RoomMessagingStore(vertx, reopened).getMessage(original.id()).await()!!
            assertEquals(DeliveryStatus.DELIVERED, restored.deliveryStatus())
            assertNull(restored.deliveryReason())
            assertEquals(456L, restored.deliveryAt())
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
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
        val original = message(convo, 100, "x", DeliveryStatus.RECIPIENT_UNKNOWN, "unknown", 456L)
        store.putMessage(original).await()
        assertTrue(store.removeMessagesByConversation(convo).await())
        assertTrue(store.getMessagesBefore(convo, Long.MAX_VALUE, 10, 0).await().isEmpty())
        assertNull(store.getConversation(convo).await())
        assertFalse(store.existsMessage(original.id()).await())
        assertFalse(store.removeMessagesByConversation(convo).await())
    }

    @Test
    fun clearingMessagesRemovesTheirReceipts() {
        val first = message(Id.random(), 100L, "first", DeliveryStatus.DELIVERED, null, 200L)
        val second = message(Id.random(), 101L, "second", DeliveryStatus.EXPIRED, "expired", 300L)
        store.putMessage(first).await()
        store.putMessage(second).await()
        store.clearMessages().await()
        assertFalse(store.existsMessage(first.id()).await())
        assertFalse(store.existsMessage(second.id()).await())
        assertNull(store.updateMessageDeliveryStatus(first.id(), DeliveryStatus.DELIVERED, null, 400L).await())
    }

    @Test
    fun deliveryReceiptNotificationUsesPlainAndroidWireClass() {
        val messageId = Id.random()
        val bytes = io.bosonnetwork.json.Json.toBytes(mapOf(
            "id" to Id.random(), "s" to Id.random(), "t" to 123L, "e" to "dr",
            "b" to mapOf("id" to messageId, "s" to DeliveryStatus.EXPIRED.value(), "r" to "expired", "t" to 456L),
        ))
        val notification = io.bosonnetwork.photonmessaging.impl.Notification.parse(bytes)
        val body: MessageDeliveryReceipt = notification.getBody()
        assertEquals(messageId, body.messageId)
        assertEquals(DeliveryStatus.EXPIRED, body.status)
        assertEquals("expired", body.reason)
        assertEquals(456L, body.timestamp)
        assertEquals("java.lang.Object", body.javaClass.superclass!!.name)
    }
}
