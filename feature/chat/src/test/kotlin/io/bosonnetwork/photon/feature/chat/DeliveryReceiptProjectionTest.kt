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

import io.bosonnetwork.Id
import io.bosonnetwork.photon.feature.chat.data.ConversationTimeline
import io.bosonnetwork.photon.feature.chat.data.MessageTimeline
import io.bosonnetwork.photon.feature.chat.data.MessageUpdate
import io.bosonnetwork.photon.feature.chat.model.MessageStatus
import io.bosonnetwork.photon.feature.chat.model.UiConversation
import io.bosonnetwork.photon.feature.chat.model.UiDeliveryReceipt
import io.bosonnetwork.photon.feature.chat.model.UiMessage
import io.bosonnetwork.photon.feature.chat.model.toUi
import io.bosonnetwork.photonmessaging.Conversation
import io.bosonnetwork.photonmessaging.DeliveryStatus
import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageDeliveryReceipt
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryReceiptProjectionTest {
    private val user = Id.random()
    private val id = Id.random()
    private val receipt = MessageDeliveryReceipt(id, DeliveryStatus.EXPIRED, "explanation", 123L)
    private val message = UiMessage(id.toString(), "hello", true, 1L)

    private fun storedMessage(status: DeliveryStatus, date: Long, outgoing: Boolean = true): Message =
        mockk<Message>(relaxed = true) {
            every { getId() } returns this@DeliveryReceiptProjectionTest.id
            every { getFrom() } returns Optional.of(if (outgoing) user else Id.random())
            every { getDeliveryStatus() } returns status
            every { getDeliveryReason() } returns "explanation"
            every { getDeliveryAt() } returns date
        }

    @Test
    fun `every recorded service outcome remains separate from local submission status`() {
        for (status in DeliveryStatus.entries) {
            val ui = storedMessage(status, 123L).toUi(user)
            assertEquals(MessageStatus.SENT, ui.status)
            assertEquals(UiDeliveryReceipt(status, "explanation", 123L), ui.deliveryReceipt)
        }
        assertNull(storedMessage(DeliveryStatus.UNDELIVERED, 0L).toUi(user).deliveryReceipt)
        assertNull(storedMessage(DeliveryStatus.EXPIRED, 123L, outgoing = false).toUi(user).deliveryReceipt)
    }

    @Test
    fun `conversation projects only its latest outgoing message outcome`() {
        val conversation = mockk<Conversation>(relaxed = true) {
            every { getId() } returns Id.random()
            every { getContact().getRemark() } returns Optional.empty()
            every { getContact().getName() } returns Optional.empty()
            every { getPreview() } returns Optional.of("preview")
        }
        every { conversation.lastMessage } returns Optional.of(storedMessage(DeliveryStatus.EXPIRED, 123L))
        val outgoing = conversation.toUi(user)
        assertEquals(id.toString(), outgoing.lastMessageId)
        assertEquals(DeliveryStatus.EXPIRED, outgoing.deliveryReceipt?.status)
        assertEquals("preview", outgoing.preview)
        every { conversation.lastMessage } returns Optional.of(storedMessage(DeliveryStatus.EXPIRED, 123L, false))
        assertFalse(conversation.toUi(user).lastMessageFromMe)
        assertNull(conversation.toUi(user).deliveryReceipt)
        every { conversation.lastMessage } returns Optional.empty()
        assertNull(conversation.toUi(user).lastMessageId)
    }

    @Test
    fun `receipt before a message or page never creates a phantom message`() {
        val timeline = MessageTimeline()
        timeline.apply(MessageUpdate.Initial(emptyList()))
        assertEquals(emptyList<UiMessage>(), timeline.apply(MessageUpdate.Receipt(receipt)))
        val paged = timeline.apply(MessageUpdate.Older(listOf(message)))
        assertEquals(DeliveryStatus.EXPIRED, paged.single().deliveryReceipt?.status)
        assertEquals(MessageStatus.SENT, paged.single().status)
    }

    @Test
    fun `duplicates late echoes and older pages preserve an observed receipt`() {
        val timeline = MessageTimeline()
        timeline.apply(MessageUpdate.Initial(listOf(message)))
        val failed = timeline.apply(MessageUpdate.Receipt(receipt))
        assertEquals(failed, timeline.apply(MessageUpdate.Receipt(receipt)))
        assertEquals(failed, timeline.apply(MessageUpdate.Received(message)))
        assertEquals(failed, timeline.apply(MessageUpdate.Older(listOf(message))))
        val stale = MessageDeliveryReceipt(id, DeliveryStatus.DELIVERED, null, 100L)
        assertEquals(failed, timeline.apply(MessageUpdate.Receipt(stale)))
        val incoming = message.copy(fromMe = false)
        timeline.apply(MessageUpdate.Initial(listOf(incoming)))
        assertNull(timeline.apply(MessageUpdate.Receipt(receipt)).single().deliveryReceipt)
    }

    @Test
    fun `conversation refresh preserves callbacks despite failed persistence without changing preview or order`() {
        val conversation = UiConversation("peer", "Alice", "preview", false, 10L,
            lastMessageId = id.toString(), lastMessageFromMe = true)
        val timeline = ConversationTimeline()
        timeline.refresh(listOf(conversation))
        val failed = timeline.apply(receipt).single()
        assertEquals(conversation.copy(deliveryReceipt = UiDeliveryReceipt(DeliveryStatus.EXPIRED, "explanation", 123L)), failed)
        assertEquals(listOf(failed), timeline.refresh(listOf(conversation)))
        assertEquals(listOf(failed), timeline.apply(receipt))
        val newer = conversation.copy(lastMessageId = Id.random().toString(), preview = "newer", updatedAt = 20L)
        assertNull(timeline.refresh(listOf(newer)).single().deliveryReceipt)
        assertEquals(listOf(newer), timeline.apply(receipt))
        assertEquals(listOf(newer), timeline.refresh(listOf(newer)))
        val incoming = conversation.copy(lastMessageFromMe = false)
        timeline.refresh(listOf(incoming))
        assertNull(timeline.apply(receipt).single().deliveryReceipt)
    }

    @Test
    fun `pending projection callbacks are bounded and cleared when the client is replaced`() {
        val timeline = MessageTimeline()
        timeline.apply(MessageUpdate.Initial(emptyList()))
        timeline.apply(MessageUpdate.Receipt(receipt))
        repeat(512) {
            timeline.apply(MessageUpdate.Receipt(MessageDeliveryReceipt(Id.random(), DeliveryStatus.DELIVERED, null, 456L)))
        }
        assertNull(timeline.apply(MessageUpdate.Received(message)).single().deliveryReceipt)
        timeline.apply(MessageUpdate.Receipt(receipt))
        timeline.apply(MessageUpdate.Initial(emptyList()))
        assertNull(timeline.apply(MessageUpdate.Received(message)).single().deliveryReceipt)
    }
}
