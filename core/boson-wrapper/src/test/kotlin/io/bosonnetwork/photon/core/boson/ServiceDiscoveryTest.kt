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
import io.bosonnetwork.json.Json
import io.bosonnetwork.photon.core.model.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServiceDiscoveryTest {

    private val nodeId = Id.random()
    private val messagingPeer = Id.random()
    private val ionStorePeer = Id.random()

    private val messaging =
        """{"serviceId":"${NodeStatus.Service.PHOTON_MESSAGING}","serviceName":"messaging",""" +
            """"peerId":"$messagingPeer","endpoint":"mqtts://host:8883"}"""
    private val ionStore =
        """{"serviceId":"${NodeStatus.Service.ION_STORE}","serviceName":"ion-store",""" +
            """"peerId":"$ionStorePeer","endpoint":"https://host:9443"}"""

    // Parsed as the Director client parses the node's answer.
    private fun status(vararg services: String): NodeStatus = Json.parse(
        """{"nodeId":"$nodeId","running":true,"startedAt":1,"services":[${services.joinToString(",")}]}""",
        NodeStatus::class.java,
    )

    @Test
    fun `maps both services into coords`() {
        val coords = ServiceDiscovery.toServiceCoords(status(messaging, ionStore))

        assertEquals(nodeId.toString(), coords.directorNodeId)
        assertEquals(messagingPeer.toString(), coords.messagingPeerId)
        assertEquals("mqtts://host:8883", coords.messagingEndpoint)
        assertEquals(ionStorePeer.toString(), coords.ionStorePeerId)
        assertEquals("https://host:9443", coords.ionStoreUrl)
    }

    @Test
    fun `throws when messaging service absent`() {
        assertThrows(AppError.NotFound::class.java) {
            ServiceDiscovery.toServiceCoords(status(ionStore))
        }
    }

    @Test
    fun `throws when ion-store service absent`() {
        assertThrows(AppError.NotFound::class.java) {
            ServiceDiscovery.toServiceCoords(status(messaging))
        }
    }

    @Test
    fun `throws when a service has no endpoint`() {
        val noEndpoint = """{"serviceId":"${NodeStatus.Service.ION_STORE}","peerId":"$ionStorePeer"}"""
        assertThrows(AppError.NotFound::class.java) {
            ServiceDiscovery.toServiceCoords(status(messaging, noEndpoint))
        }
    }
}
