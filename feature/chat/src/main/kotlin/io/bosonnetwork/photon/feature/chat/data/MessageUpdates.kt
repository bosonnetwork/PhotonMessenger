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

package io.bosonnetwork.photon.feature.chat.data

import io.bosonnetwork.photon.feature.chat.model.UiConversation
import io.bosonnetwork.photon.feature.chat.model.UiDeliveryReceipt
import io.bosonnetwork.photon.feature.chat.model.UiMessage
import io.bosonnetwork.photonmessaging.MessageDeliveryReceipt

private const val MAX_PENDING_RECEIPTS = 512

/** History and callbacks enter the same coroutine before changing the displayed messages. */
sealed interface MessageUpdate {
    data class Initial(val messages: List<UiMessage>) : MessageUpdate
    data class Received(val message: UiMessage) : MessageUpdate
    data class Receipt(val receipt: MessageDeliveryReceipt) : MessageUpdate
    data class Older(val messages: List<UiMessage>) : MessageUpdate
}

/** Accessed only by the message-stream collector, including when a page finishes loading. */
internal class MessageTimeline {
    private val messages = LinkedHashMap<String, UiMessage>()
    private val pendingReceipts = PendingDeliveryReceipts()

    fun apply(update: MessageUpdate): List<UiMessage> {
        when (update) {
            is MessageUpdate.Initial -> {
                messages.clear()
                pendingReceipts.clear()
                update.messages.forEach(::accept)
            }
            is MessageUpdate.Received -> accept(update.message)
            is MessageUpdate.Older -> update.messages.forEach(::accept)
            is MessageUpdate.Receipt -> {
                val id = update.receipt.messageId.toString()
                val message = messages[id]
                if (message == null) pendingReceipts.add(update.receipt)
                else if (message.fromMe) {
                    messages[id] = message.copy(deliveryReceipt = update.receipt.toUi().preserving(message.deliveryReceipt))
                }
            }
        }
        return messages.values.sortedBy { it.createdAt }
    }

    private fun accept(message: UiMessage) {
        val previous = messages[message.id]
        val pending = pendingReceipts.take(message.id)
        val receipt = if (message.fromMe) message.deliveryReceipt
            .preserving(previous?.deliveryReceipt).preserving(pending) else null
        messages[message.id] = message.copy(deliveryReceipt = receipt)
    }
}

/** Keeps observed callbacks visible when a later database refresh has older persisted state. */
internal class ConversationTimeline {
    private var conversations = emptyList<UiConversation>()
    private val pendingReceipts = PendingDeliveryReceipts()

    fun refresh(snapshot: List<UiConversation>): List<UiConversation> {
        val previous = conversations.associateBy { it.id }
        conversations = snapshot.map { conversation ->
            val old = previous[conversation.id]?.takeIf { it.lastMessageId == conversation.lastMessageId }
            val pending = conversation.lastMessageId?.let(pendingReceipts::take)
            val receipt = if (conversation.lastMessageFromMe) conversation.deliveryReceipt
                .preserving(old?.deliveryReceipt).preserving(pending) else null
            conversation.copy(deliveryReceipt = receipt)
        }
        return conversations
    }

    fun apply(receipt: MessageDeliveryReceipt): List<UiConversation> {
        val id = receipt.messageId.toString()
        val index = conversations.indexOfFirst { it.lastMessageId == id }
        if (index < 0) pendingReceipts.add(receipt)
        else if (conversations[index].lastMessageFromMe) {
            conversations = conversations.toMutableList().apply {
                val old = get(index)
                set(index, old.copy(deliveryReceipt = receipt.toUi().preserving(old.deliveryReceipt)))
            }
        }
        return conversations
    }
}

/**
 * Only bridges callbacks to an in-flight UI snapshot, echo or older page. Known outcomes live on
 * the displayed rows. Unmatched callbacks are bounded and discarded with this collection; this is
 * not a persistent receipt journal or a replacement for the messaging client's early-receipt cache.
 */
private class PendingDeliveryReceipts {
    private val receipts = LinkedHashMap<String, UiDeliveryReceipt>()

    fun add(receipt: MessageDeliveryReceipt) {
        val id = receipt.messageId.toString()
        receipts[id] = receipt.toUi().preserving(receipts[id])!!
        if (receipts.size > MAX_PENDING_RECEIPTS) receipts.remove(receipts.keys.first())
    }

    fun take(id: String): UiDeliveryReceipt? = receipts.remove(id)
    fun clear() = receipts.clear()
}

private fun MessageDeliveryReceipt.toUi() = UiDeliveryReceipt(status, reason, timestamp)

private fun UiDeliveryReceipt?.preserving(previous: UiDeliveryReceipt?): UiDeliveryReceipt? =
    if (previous != null && (this == null || previous.timestamp >= timestamp)) previous else this
