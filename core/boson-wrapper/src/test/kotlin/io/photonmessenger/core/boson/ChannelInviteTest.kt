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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelInviteTest {
    @Test
    fun `round-trips through json`() {
        val invite = ChannelInvite(
            ticket = "{\"c\":\"abc\",\"sig\":\"xyz\"}",
            channelId = "channel123",
            channelName = "Design Team",
            inviter = "alice456",
            expiresAt = 1_750_000_000_000L,
        )

        val decoded = ChannelInvite.fromJson(invite.toJson())

        assertEquals(invite, decoded)
    }

    @Test
    fun `rejects a body missing the ticket`() {
        val json = "{\"channelId\":\"c\",\"inviter\":\"a\",\"channelName\":\"X\"}"
        assertNull(ChannelInvite.fromJson(json))
    }

    @Test
    fun `rejects a non-json body`() {
        assertNull(ChannelInvite.fromJson("not json at all"))
    }

    @Test
    fun `tolerates a missing channel name and expiry`() {
        val json = "{\"ticket\":\"t\",\"channelId\":\"c\",\"inviter\":\"a\"}"
        val decoded = ChannelInvite.fromJson(json)
        assertEquals("", decoded?.channelName)
        assertEquals(0L, decoded?.expiresAt)
    }
}
