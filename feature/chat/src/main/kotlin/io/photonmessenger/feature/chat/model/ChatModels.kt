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
)

/** UI projection of a single message bubble. */
data class UiMessage(
    val id: String,
    val text: String,
    val fromMe: Boolean,
    val createdAt: Long,
    val attachment: UiAttachment? = null,
)

fun Conversation.toUi(): UiConversation = UiConversation(
    id = getId().toString(),
    title = getTitle(),
    preview = getPreview().orElse(""),
    isChannel = isChannel(),
    updatedAt = getUpdatedAt(),
)

fun Message.toUi(myUserId: Id?): UiMessage {
    val from = getFrom().orElse(null)
    val attachment = runCatching { extractAttachment() }.getOrNull()
    return UiMessage(
        id = getId().toString(),
        text = if (attachment != null) "" else runCatching { getPayloadAsContent().asText() }.getOrDefault(""),
        fromMe = from != null && from == myUserId,
        createdAt = getCreatedAt(),
        attachment = attachment,
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
