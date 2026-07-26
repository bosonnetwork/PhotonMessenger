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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.bosonnetwork.photon.core.boson.BosonClientFactory
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.FriendRequestListener
import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageListener
import io.bosonnetwork.photonmessaging.MessagingClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LIVE integration tests (X-T3). Require the dev super node running and reachable at
 * [TestSuperNode.directorUrl] (Director) plus its discovered mqtts endpoint. They self-register users via
 * the non-OAuth API and exercise the real Android stack (REST + keys + Room persistence + mqtts TLS)
 * end to end against a live node. Will fail-fast where the node is down.
 *
 * Phase 1 ([registersAndConnectsToLiveNode]): a single client reaches READY.
 * Phase 2 ([friendRequestAndDirectMessageRoundTrip]): two clients complete a friend-request handshake
 * and a direct-message round-trip.
 */
@RunWith(AndroidJUnit4::class)
class LiveConnectIntegrationTest {

    private val timeout = 30L

    /** A registered + connected client and the Boson identity it owns. */
    private class Peer(val client: MessagingClient, val userId: Id, val name: String)

    /**
     * Registers a fresh user + device with the live Director and connects a MessagingClient (in-memory
     * Room store) to READY. Registration + discovery + connection all go through [LiveTestHarness], so
     * this drives the same production pinned-TLS path as the other live suites and follows the node's
     * registration policy (proof-of-work where the node requires it).
     */
    private fun registerAndConnect(harness: LiveTestHarness, name: String): Peer {
        val account = harness.register(name)
        return Peer(harness.connect(account), account.userId, name)
    }

    @Test
    fun registersAndConnectsToLiveNode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vertx = BosonClientFactory.newVertx()
        val harness = LiveTestHarness(context, vertx)
        try {
            val peer = registerAndConnect(harness, "Solo")
            assertTrue("client not connected", peer.client.isConnected)
        } finally {
            harness.close()
            vertx.close()
        }
    }

    @Test
    fun friendRequestAndDirectMessageRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vertx = BosonClientFactory.newVertx()
        val harness = LiveTestHarness(context, vertx)
        try {
            val alice = registerAndConnect(harness, "Alice")
            val bob = registerAndConnect(harness, "Bob")

            // Bob waits for Alice's friend request; Alice waits for Bob's acceptance.
            // (Plain vars are safe here: CountDownLatch establishes happens-before between threads.)
            val bobGotRequest = CountDownLatch(1)
            var requestHello: String? = null
            bob.client.addFriendRequestListener(object : FriendRequestListener {
                override fun onFriendRequest(userId: Id, hello: String) {
                    if (userId == alice.userId) { requestHello = hello; bobGotRequest.countDown() }
                }
                override fun onFriendRequestAccepted(userId: Id) {}
            })
            val aliceAccepted = CountDownLatch(1)
            alice.client.addFriendRequestListener(object : FriendRequestListener {
                override fun onFriendRequest(userId: Id, hello: String) {}
                override fun onFriendRequestAccepted(userId: Id) {
                    if (userId == bob.userId) aliceAccepted.countDown()
                }
            })

            alice.client.friendRequest(bob.userId, "hi from alice").get(timeout, TimeUnit.SECONDS)
            assertTrue("bob did not receive the friend request", bobGotRequest.await(timeout, TimeUnit.SECONDS))
            assertEquals("hi from alice", requestHello)

            bob.client.acceptFriendRequest(alice.userId).get(timeout, TimeUnit.SECONDS)
            assertTrue("alice did not see the acceptance", aliceAccepted.await(timeout, TimeUnit.SECONDS))

            // Direct-message round-trip: Alice -> Bob.
            val bobGotMessage = CountDownLatch(1)
            var receivedText: String? = null
            bob.client.addMessageListener(object : MessageListener {
                override fun onMessage(message: Message) {
                    if (message.from.orElse(null) == alice.userId) {
                        receivedText = message.payloadAsContent.getBody<String>()
                        bobGotMessage.countDown()
                    }
                }
                override fun onSent(message: Message) {}
            })

            alice.client.message(bob.userId).contentText("hello bob").send().get(timeout, TimeUnit.SECONDS)
            assertTrue("bob did not receive the direct message", bobGotMessage.await(timeout, TimeUnit.SECONDS))
            assertEquals("hello bob", receivedText)
        } finally {
            harness.close()
            vertx.close()
        }
    }
}
