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

package io.photonmessenger.core.network

import io.photonmessenger.core.model.AppError
import io.photonmessenger.core.network.model.NodeStatusDto
import io.photonmessenger.core.network.model.ServiceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServiceDiscoveryTest {

    private fun status(services: List<ServiceDto>) = NodeStatusDto(nodeId = "node-1", services = services)

    private val messaging = ServiceDto(
        serviceId = ServiceDiscovery.MESSAGING_SERVICE_ID,
        serviceName = "messaging",
        peerId = "peerMsg",
        endpoint = "mqtts://host:8883",
    )
    private val ionStore = ServiceDto(
        serviceId = ServiceDiscovery.ION_STORE_SERVICE_ID,
        serviceName = "ion-store",
        peerId = "peerIon",
        endpoint = "https://host:9443",
    )

    @Test
    fun `maps both services into coords`() {
        val coords = ServiceDiscovery.toServiceCoords(status(listOf(messaging, ionStore)))

        assertEquals("node-1", coords.directorNodeId)
        assertEquals("peerMsg", coords.messagingPeerId)
        assertEquals("mqtts://host:8883", coords.messagingEndpoint)
        assertEquals("peerIon", coords.ionStorePeerId)
        assertEquals("https://host:9443", coords.ionStoreUrl)
    }

    @Test
    fun `throws when messaging service absent`() {
        assertThrows(AppError.NotFound::class.java) {
            ServiceDiscovery.toServiceCoords(status(listOf(ionStore)))
        }
    }

    @Test
    fun `throws when ion-store service absent`() {
        assertThrows(AppError.NotFound::class.java) {
            ServiceDiscovery.toServiceCoords(status(listOf(messaging)))
        }
    }
}
