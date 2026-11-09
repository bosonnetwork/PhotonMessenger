/*
 * Copyright (c) 2026 -      bosonnetwork.io
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

package io.bosonnetwork.photon.feature.chat

import app.cash.turbine.test
import io.bosonnetwork.Id
import io.bosonnetwork.photon.core.boson.BosonSessionManager
import io.bosonnetwork.photon.feature.chat.data.ChatRepository
import io.bosonnetwork.photon.feature.chat.data.ChatRepositoryImpl
import io.bosonnetwork.photon.feature.chat.data.MessageTimeline
import io.bosonnetwork.photonmessaging.Conversation
import io.bosonnetwork.photonmessaging.DeliveryStatus
import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageDeliveryReceipt
import io.bosonnetwork.photonmessaging.MessageListener
import io.bosonnetwork.photonmessaging.MessagingClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryReceiptTest {
    private val user = Id.random()
    private val peer = Id.random()
    private val id = Id.random()
    private val client = mockk<MessagingClient>(relaxed = true)
    private val listeners = CopyOnWriteArrayList<MessageListener>()
    private val clients = MutableStateFlow<MessagingClient?>(client)
    private val session = mockk<BosonSessionManager>()
    private val repository = ChatRepositoryImpl(session, mockk(), mockk(), mockk(), mockk())

    init {
        every { session.client } returns clients
        every { client.userId } returns user
        every { client.getContact(any()) } returns CompletableFuture.completedFuture(Optional.empty())
        every { client.addMessageListener(any()) } answers { listeners.add(firstArg()); Unit }
        every { client.removeMessageListener(any()) } answers { listeners.remove(firstArg()); Unit }
    }

    private fun message(): Message = mockk<Message>(relaxed = true) {
        every { getId() } returns this@ChatRepositoryReceiptTest.id
        every { getFrom() } returns Optional.of(user)
        every { getConversationId() } returns Optional.of(peer)
        every { getCreatedAt() } returns 10L
        every { getDeliveryAt() } returns 0L
    }

    private val receipt get() = MessageDeliveryReceipt(id, DeliveryStatus.RECIPIENT_UNKNOWN, "unknown", 123L)

    @Test
    fun `listeners register before loading and replay worker callbacks after the snapshot`() = runTest {
        val snapshot = CompletableFuture<List<Message>>()
        every { client.getMessagesBefore(peer, Long.MAX_VALUE, ChatRepository.PAGE_SIZE, 0) } answers {
            assertEquals(1, listeners.size)
            snapshot
        }
        val timeline = MessageTimeline()
        repository.messages(peer.toString()).map(timeline::apply).distinctUntilChanged().test {
            runCurrent()
            val echo = message()
            withContext(Dispatchers.Default) {
                // Store update failed: the snapshot/echo still has no receipt. No extra read can
                // reconstruct this callback, so it must survive the UI bootstrap itself.
                listeners.single().onDeliveryReceipt(receipt)
                listeners.single().onSent(echo)
            }
            snapshot.complete(emptyList())
            assertTrue(awaitItem().isEmpty())
            val arrived = awaitItem().single()
            assertEquals(id.toString(), arrived.id)
            assertEquals(DeliveryStatus.RECIPIENT_UNKNOWN, arrived.deliveryReceipt?.status)
            listeners.single().onSent(echo)
            listeners.single().onDeliveryReceipt(receipt)
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(listeners.isEmpty())
        verify(exactly = 1) { client.getMessagesBefore(any(), any(), any(), any()) }
    }

    @Test
    fun `receipts during conversation refresh survive stale snapshots and remain idempotent`() = runTest {
        val first = CompletableFuture<List<Conversation>>()
        val refresh = CompletableFuture<List<Conversation>>()
        every { client.getConversations() } returnsMany listOf(first, refresh)
        val outgoing = message()
        val conversation = mockk<Conversation>(relaxed = true) {
            every { getId() } returns peer
            every { getContact().getName() } returns Optional.empty()
            every { getContact().getRemark() } returns Optional.empty()
            every { getLastMessage() } returns Optional.of(outgoing)
            every { getPreview() } returns Optional.of("original preview")
            every { getUpdatedAt() } returns 10L
        }
        repository.conversations().test {
            runCurrent()
            assertEquals(1, listeners.size)
            listeners.single().onDeliveryReceipt(receipt)
            first.complete(listOf(conversation))
            assertEquals(null, awaitItem().single().deliveryReceipt)
            val failed = awaitItem().single()
            assertEquals(DeliveryStatus.RECIPIENT_UNKNOWN, failed.deliveryReceipt?.status)
            assertEquals("original preview", failed.preview)
            assertEquals(10L, failed.updatedAt)
            listeners.single().onSent(outgoing)
            runCurrent()
            listeners.single().onDeliveryReceipt(receipt)
            refresh.complete(listOf(conversation))
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(listeners.isEmpty())
    }

    @Test
    fun `snapshot failure removes the registered listener`() = runTest {
        every { client.getMessagesBefore(any(), any(), any(), any()) } returns
            CompletableFuture.failedFuture(IllegalStateException("store unavailable"))
        repository.messages(peer.toString()).test {
            assertEquals("store unavailable", awaitError().message)
        }
        assertTrue(listeners.isEmpty())
    }
}
