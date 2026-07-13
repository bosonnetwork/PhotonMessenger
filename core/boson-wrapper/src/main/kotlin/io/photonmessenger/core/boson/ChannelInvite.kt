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
import io.bosonnetwork.photonmessaging.InviteTicket

/**
 * The payload of a channel-invite chat message. A named invite ticket is delivered to its invitee as
 * a message whose {@code Content-Type} is [InviteTicket.CONTENT_TYPE]; the dedicated content type lets
 * the recipient recognize it (and preview it) without a custom header. The body is this payload,
 * CBOR-encoded ([toBytes]).
 *
 * [ticket] is the serialized invite ticket (its own CBOR form, [InviteTicket.toBytes]) kept opaque
 * here and parsed only when joining. [channelName] and [expiresAt] are carried alongside so the
 * recipient's card renders (and shows its expiry) without parsing the ticket.
 */
data class ChannelInvite(
    /** The serialized invite ticket ([InviteTicket.toBytes]); opaque here, parsed only to join. */
    val ticket: ByteArray,
    val channelName: String,
    /** Ticket expiration time in epoch milliseconds; drives the "expired" state on the card. */
    val expiresAt: Long,
) {
    /** CBOR-encodes this invite for the message body. */
    fun toBytes(): ByteArray = Json.toBytes(
        linkedMapOf(
            KEY_TICKET to ticket,
            KEY_CHANNEL_NAME to channelName,
            KEY_EXPIRES_AT to expiresAt,
        )
    )

    // ByteArray fields are excluded from the generated equals/hashCode (reference-based). Equality is
    // not relied upon in production; tests compare fields explicitly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelInvite) return false
        return ticket.contentEquals(other.ticket) &&
            channelName == other.channelName &&
            expiresAt == other.expiresAt
    }

    override fun hashCode(): Int {
        var result = ticket.contentHashCode()
        result = 31 * result + channelName.hashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }

    companion object {
        /** Content type marking a message body as a channel invite. */
        const val CONTENT_TYPE: String = InviteTicket.CONTENT_TYPE

        private const val KEY_TICKET = "t"
        private const val KEY_CHANNEL_NAME = "n"
        private const val KEY_EXPIRES_AT = "e"

        /**
         * Parses a CBOR invite body, or returns null if it is missing the ticket (a malformed or
         * partial payload is treated as "not an invite" rather than crashing the chat).
         */
        fun fromBytes(bytes: ByteArray): ChannelInvite? {
            val map = runCatching { Json.parse(bytes) }.getOrNull() ?: return null
            val ticket = map[KEY_TICKET] as? ByteArray ?: return null
            val channelName = (map[KEY_CHANNEL_NAME] as? String).orEmpty()
            val expiresAt = (map[KEY_EXPIRES_AT] as? Number)?.toLong() ?: 0L
            return ChannelInvite(ticket = ticket, channelName = channelName, expiresAt = expiresAt)
        }
    }
}
