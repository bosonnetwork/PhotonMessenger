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

package io.bosonnetwork.photon.core.boson

import io.bosonnetwork.Id
import io.bosonnetwork.director.client.NodeStatus
import io.bosonnetwork.director.client.Profile
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardContentTest {
    private val userId = Id.random()
    private val nodeId = Id.random()

    private fun profile(name: String?, bio: String?, avatar: String?): Profile = mockk {
        every { id } returns userId
        every { this@mockk.name } returns Optional.ofNullable(name)
        every { this@mockk.bio } returns Optional.ofNullable(bio)
        every { this@mockk.avatar } returns Optional.ofNullable(avatar)
    }

    private fun status(messagingPeer: Id? = null): NodeStatus = mockk {
        every { this@mockk.nodeId } returns this@CardContentTest.nodeId
        every { getService(NodeStatus.Service.PHOTON_MESSAGING) } returns
            Optional.ofNullable(messagingPeer?.let { peer -> mockk<NodeStatus.Service> { every { peerId } returns peer } })
    }

    @Test
    fun `a card names the home node, and the avatar where the node serves it`() {
        // Named as Boson Identity names it, whatever URI the node's profile carries, so the apps agree.
        val content = CardContent.of(profile("Alice", "Hi", "bnr://elsewhere/avatar"), status())
        assertEquals("Alice", content.name)
        assertEquals("Hi", content.bio)
        assertEquals("bnr://$nodeId/api/v1/client/avatar/$userId", content.avatar)
        assertEquals(nodeId.toString(), content.homeNode)
        assertNull(content.messagingHomePeer)
    }

    @Test
    fun `blank fields and a missing avatar are left out`() {
        val content = CardContent.of(profile(" ", null, ""), status())
        assertNull(content.name)
        assertNull(content.bio)
        assertNull(content.avatar)
    }

    @Test
    fun `a node with messaging puts its messaging peer on the card`() {
        val peer = Id.random()
        assertEquals(peer.toString(), CardContent.of(profile("Alice", null, null), status(peer)).messagingHomePeer)
    }
}
