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

import io.bosonnetwork.director.client.NodeStatus
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.ServiceCoords

/**
 * Maps the Director's [NodeStatus] into the coordinates the messaging/ion-store clients need
 * (spec 1.7, M1-13). Service identifiers are the Boson service type strings.
 */
object ServiceDiscovery {
    /**
     * Extracts messaging + ion-store coordinates from a NodeStatus.
     *
     * @throws AppError.NotFound if either required service is absent from the node, or has no endpoint.
     */
    fun toServiceCoords(status: NodeStatus): ServiceCoords {
        val messaging = status.getService(NodeStatus.Service.PHOTON_MESSAGING).orElse(null)
            ?: throw AppError.NotFound("Director node exposes no messaging service")
        val ionStore = status.getService(NodeStatus.Service.ION_STORE).orElse(null)
            ?: throw AppError.NotFound("Director node exposes no ion-store service")

        return ServiceCoords(
            directorNodeId = status.nodeId.toString(),
            messagingPeerId = messaging.peerId.toString(),
            messagingEndpoint = messaging.endpoint.orElse(null)
                ?: throw AppError.NotFound("The messaging service has no endpoint"),
            ionStorePeerId = ionStore.peerId.toString(),
            ionStoreUrl = ionStore.endpoint.orElse(null)
                ?: throw AppError.NotFound("The ion-store service has no endpoint"),
        )
    }
}
