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

package io.photonmessenger.core.boson

import io.bosonnetwork.json.Json

/**
 * The payload of a channel-invite chat message. A named invite ticket is delivered to its invitee as
 * an ordinary message whose body is this JSON object, tagged with content type [INVITE_CONTENT_TYPE]
 * and the [INVITE_HEADER]=[INVITE_TYPE] header so the recipient's chat can recognise it and render an
 * actionable invitation instead of an opaque ticket string.
 *
 * [ticket] is the serialized invite ticket used to join; the remaining fields let the recipient render
 * the card (and a future richer invite surface) without parsing the ticket or performing a lookup.
 */
data class ChannelInvite(
    /** The serialized invite ticket string (as produced by the messaging client) used to join. */
    val ticket: String,
    val channelId: String,
    val channelName: String,
    /** The inviter's user id (base58). */
    val inviter: String,
    /** Ticket expiration time in epoch milliseconds; drives the "expired" state on the card. */
    val expiresAt: Long,
) {
    /** Serializes this invite to the JSON string carried in the message body. */
    fun toJson(): String = Json.toString(
        linkedMapOf(
            KEY_TICKET to ticket,
            KEY_CHANNEL_ID to channelId,
            KEY_CHANNEL_NAME to channelName,
            KEY_INVITER to inviter,
            KEY_EXPIRES_AT to expiresAt,
        )
    )

    companion object {
        /** Content type marking a message body as a channel invite (see also [INVITE_HEADER]). */
        const val INVITE_CONTENT_TYPE: String = "application/json"

        /** Message header key whose value identifies the message type. */
        const val INVITE_HEADER: String = "mt"

        /** [INVITE_HEADER] value that identifies a channel-invite message. */
        const val INVITE_TYPE: String = "channel-invite"

        private const val KEY_TICKET = "ticket"
        private const val KEY_CHANNEL_ID = "channelId"
        private const val KEY_CHANNEL_NAME = "channelName"
        private const val KEY_INVITER = "inviter"
        private const val KEY_EXPIRES_AT = "expiresAt"

        /**
         * Parses a channel-invite JSON body, or returns null if it is missing required fields (a
         * malformed or partial payload is treated as "not an invite" rather than crashing the chat).
         */
        fun fromJson(json: String): ChannelInvite? {
            val map = runCatching { Json.parse(json) }.getOrNull() ?: return null
            val ticket = (map[KEY_TICKET] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val channelId = (map[KEY_CHANNEL_ID] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val inviter = (map[KEY_INVITER] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val channelName = (map[KEY_CHANNEL_NAME] as? String).orEmpty()
            val expiresAt = (map[KEY_EXPIRES_AT] as? Number)?.toLong() ?: 0L
            return ChannelInvite(
                ticket = ticket,
                channelId = channelId,
                channelName = channelName,
                inviter = inviter,
                expiresAt = expiresAt,
            )
        }
    }
}
