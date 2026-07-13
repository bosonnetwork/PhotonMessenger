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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelInviteTest {
    @Test
    fun `round-trips the ticket bytes and metadata through cbor`() {
        val ticketBytes = byteArrayOf(1, 2, 3, 4, 5, 0, -7, 42)
        val invite = ChannelInvite(
            ticket = ticketBytes,
            channelName = "Design Team",
            expiresAt = 1_750_000_000_000L,
        )

        val decoded = ChannelInvite.fromBytes(invite.toBytes())!!

        // The ticket must survive as raw bytes (a CBOR byte string), not base64 text.
        assertArrayEquals(ticketBytes, decoded.ticket)
        assertEquals("Design Team", decoded.channelName)
        assertEquals(1_750_000_000_000L, decoded.expiresAt)
        assertEquals(invite, decoded)
    }

    @Test
    fun `rejects a body missing the ticket`() {
        val bytes = Json.toBytes(linkedMapOf("n" to "X", "e" to 1L))
        assertNull(ChannelInvite.fromBytes(bytes))
    }

    @Test
    fun `rejects a non-cbor body`() {
        assertNull(ChannelInvite.fromBytes(byteArrayOf(0x7b, 0x7d)))
    }

    @Test
    fun `tolerates a missing channel name and expiry`() {
        val bytes = Json.toBytes(linkedMapOf("t" to byteArrayOf(9, 9)))
        val decoded = ChannelInvite.fromBytes(bytes)!!
        assertTrue(decoded.ticket.isNotEmpty())
        assertEquals("", decoded.channelName)
        assertEquals(0L, decoded.expiresAt)
    }
}
