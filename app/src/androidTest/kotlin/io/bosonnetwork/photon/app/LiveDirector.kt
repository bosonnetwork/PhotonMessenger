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

package io.bosonnetwork.photon.app

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DirectorGuest
import io.bosonnetwork.director.client.DirectorClient
import io.vertx.core.Vertx

/**
 * Director clients for the LIVE integration tests, configured as the app configures them
 * (DirectorClients): the dev node's URL, and its node id when [TestSuperNode] sets one, which pins a
 * self-signed certificate to that identity from the very first request - no trust-on-first-use.
 */
object LiveDirector {
    /** A client acting as the user of [userKey], with [deviceKey] as its device when given. */
    fun client(vertx: Vertx, userKey: Signature.KeyPair, deviceKey: Signature.KeyPair? = null): DirectorClient =
        DirectorClient.builder().vertx(vertx).directorUrl(TestSuperNode.directorUrl).apply {
            TestSuperNode.directorNodeId?.let { nodeId(Id.of(it)) }
            userKey(userKey)
            deviceKey?.let { deviceKey(it) }
        }.build()

    /** A client acting as [userId] through one of its registered devices. */
    fun deviceClient(vertx: Vertx, userId: Id, deviceKey: Signature.KeyPair): DirectorClient =
        DirectorClient.builder().vertx(vertx).directorUrl(TestSuperNode.directorUrl).apply {
            TestSuperNode.directorNodeId?.let { nodeId(Id.of(it)) }
            userId(userId)
            deviceKey(deviceKey)
        }.build()

    /** The client that needs no identity: node information, and a new device joining an account. */
    fun guest(vertx: Vertx): DirectorGuest =
        DirectorGuest.builder().vertx(vertx).directorUrl(TestSuperNode.directorUrl).apply {
            TestSuperNode.directorNodeId?.let { nodeId(Id.of(it)) }
        }.build()
}
