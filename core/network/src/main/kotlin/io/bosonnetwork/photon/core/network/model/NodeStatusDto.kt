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

package io.bosonnetwork.photon.core.network.model

import kotlinx.serialization.Serializable

/**
 * GET /api/v1/client/node -> NodeStatus (Director). Only the fields PhotonMessenger needs are
 * modeled; the JSON also carries info/startedAt/running which we ignore. See spec 1.7.
 */
@Serializable
data class NodeStatusDto(
    val nodeId: String? = null,
    val services: List<ServiceDto> = emptyList(),
)

/**
 * A discovered service. `serviceId` is the service type, e.g.
 * "io.bosonnetwork.photonmessaging" (messaging) or "io.bosonnetwork.ionstore" (ion-store).
 * `endpoint` is the mqtts URI (messaging) or https URL (ion-store).
 */
@Serializable
data class ServiceDto(
    val serviceId: String,
    val serviceName: String? = null,
    val peerId: String,
    val endpoint: String,
)
