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

package io.photonmessenger.feature.chat.model

import io.photonmessenger.core.model.shortId
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.ContentDisposition
import io.bosonnetwork.photonmessaging.ContentType
import io.bosonnetwork.photonmessaging.Conversation
import io.bosonnetwork.photonmessaging.Message

/** UI projection of a conversation row. */
data class UiConversation(
    val id: String,
    val title: String,
    val preview: String,
    val isChannel: Boolean,
    val updatedAt: Long,
    /** Fetchable avatar URL for the peer (DMs), null for channels or when unresolvable. */
    val avatarUrl: String? = null,
    /** DM peer's library profile name (null for channels / when unknown); lets the ViewModel decide
     *  whether the title needs a Director-resolved name (see name preference in the ViewModel). */
    val peerName: String? = null,
    /** DM peer's local alias/remark (null for channels / when unset). */
    val remark: String? = null,
    /** Unread inbound message count for the conversation badge (M4). */
    val unreadCount: Int = 0,
)

/**
 * A destination the user can forward a message to: an existing DM/channel conversation or any
 * contact. [recent] marks targets that already have a conversation, so the picker can surface them
 * first; [peerName]/[remark] let the ViewModel upgrade a short-id title the same way the chat list
 * does.
 */
data class UiForwardTarget(
    val id: String,
    val title: String,
    val isChannel: Boolean,
    val recent: Boolean,
    val updatedAt: Long = 0L,
    val avatarUrl: String? = null,
    val peerName: String? = null,
    val remark: String? = null,
)

/**
 * Header shown at the top of a chat (M4-3). For a channel [isChannel] is true and [subtitle] carries
 * the member count so the same chat screen serves DMs and channels; the title links to the channel
 * detail/roster (channels) or the contact profile (DMs).
 */
data class ChatHeader(
    val title: String,
    val subtitle: String? = null,
    val isChannel: Boolean = false,
    /** Fetchable avatar URL for the DM peer, null for channels or when unresolvable. */
    val avatarUrl: String? = null,
)

/** Delivery state of an outgoing bubble. Incoming and confirmed messages are always [SENT]. */
enum class MessageStatus { SENDING, SENT, FAILED }

/** UI projection of a single message bubble. */
data class UiMessage(
    val id: String,
    val text: String,
    val fromMe: Boolean,
    val createdAt: Long,
    val attachment: UiAttachment? = null,
    val status: MessageStatus = MessageStatus.SENT,
    /** Sender's user id (base58), when known. */
    val senderId: String? = null,
    /** Sender display name; shown above incoming bubbles in channels (M4). */
    val senderName: String? = null,
    /** Store-assigned numeric id used to remove this message locally; null for optimistic bubbles
     *  (M3-4) that are not yet persisted. */
    val rid: Long? = null,
)

fun Conversation.toUi(avatarUrl: String? = null): UiConversation {
    val channel = isChannel()
    val contact = getContact()
    // The library no longer derives a display title/name (that policy moved to the app). Build the
    // base title from what the library still owns - the local remark and, for channels, the channel
    // name - falling back to a short id. DMs are then upgraded to a resolved name in the ViewModel.
    val remark = contact.getRemark().orElse(null)?.takeIf { it.isNotBlank() }
    val name = contact.getName().orElse(null)?.takeIf { it.isNotBlank() }
    return UiConversation(
        id = getId().toString(),
        title = remark ?: name ?: shortId(getId().toString()),
        preview = getPreview().orElse(""),
        isChannel = channel,
        updatedAt = getUpdatedAt(),
        avatarUrl = avatarUrl,
        peerName = if (channel) null else name,
        remark = if (channel) null else remark,
    )
}

fun Message.toUi(myUserId: Id?, resolveSenderName: ((Id) -> String?)? = null): UiMessage {
    val from = getFrom().orElse(null)
    val attachment = runCatching { extractAttachment() }.getOrNull()
    val fromMe = from != null && from == myUserId
    return UiMessage(
        id = getId().toString(),
        text = if (attachment != null) "" else runCatching { getPayloadAsContent().asText() }.getOrDefault(""),
        fromMe = fromMe,
        createdAt = getCreatedAt(),
        attachment = attachment,
        senderId = from?.toString(),
        senderName = if (!fromMe && from != null) resolveSenderName?.invoke(from) else null,
        rid = getRid().takeIf { it > 0 },
    )
}

/**
 * Reads an attachment out of a message body, discriminating purely on Content-Disposition (see
 * [AttachmentModels]): attachment -> IonStore ref (CBOR map body); inline (non-text) -> raw bytes.
 * Returns null for ordinary text messages.
 */
private fun Message.extractAttachment(): UiAttachment? {
    val content = getPayloadAsContent()
    val disposition = content.contentDisposition.orElse(null) ?: return null
    val mime = content.contentType
    return when {
        disposition.isAttachment -> {
            val map = content.asMap(String::class.java, Any::class.java) ?: return null
            remoteAttachmentFromMap(map)
        }
        disposition.isInline && mime != ContentType.TEXT -> {
            val bytes = content.asBinary() ?: return null
            UiAttachment(
                kind = kindOf(mime),
                mime = mime,
                name = disposition.filename ?: "attachment",
                size = bytes.size.toLong(),
                source = AttachmentSource.Inline(bytes),
            )
        }
        else -> null
    }
}
