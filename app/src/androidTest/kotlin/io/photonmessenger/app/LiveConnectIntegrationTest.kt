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

package io.photonmessenger.app

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.photonmessenger.core.boson.BosonClientFactory
import io.photonmessenger.core.boson.BosonCrypto
import io.photonmessenger.core.database.MessagingStoreFactory
import io.photonmessenger.core.model.AuthTokenStore
import io.photonmessenger.core.network.ServiceDiscovery
import io.photonmessenger.core.network.model.SelfRegisterRequest
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.ConnectionListener
import io.bosonnetwork.photonmessaging.FriendRequestListener
import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageListener
import io.bosonnetwork.photonmessaging.MessagingClient
import io.vertx.core.Vertx
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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

    private class MutableTokenStore : AuthTokenStore {
        @Volatile private var token: String? = null
        override fun currentToken(): String? = token
        override suspend fun setToken(token: String?) { this.token = token }
        override suspend fun clear() { token = null }
    }

    /** A registered + connected client and the Boson identity it owns. */
    private class Peer(val client: MessagingClient, val userId: Id, val name: String)

    // Director's Jackson endpoints decode byte[] with Base64Variants.MODIFIED_FOR_URL (base64url, no pad).
    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /**
     * Registers a fresh user + device with the live Director, builds a MessagingClient backed by an
     * in-memory Room store, starts it, and waits for READY.
     */
    private fun registerAndConnect(context: Context, vertx: Vertx, name: String): Peer {
        val userKp = BosonCrypto.generateKeyPair()
        val deviceKp = BosonCrypto.generateKeyPair()
        val userId = BosonCrypto.idOf(userKp)
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val request = SelfRegisterRequest(
            userId = userId.toString(),
            // No passphrase: the app onboards OAuth-style, so gated ops stay passphrase-free (M6).
            passphrase = null,
            userName = name,
            deviceId = BosonCrypto.idOf(deviceKp).toString(),
            deviceName = "emulator",
            appName = "PhotonMessenger-IT",
            nonce = b64(nonce),
            userSig = b64(BosonCrypto.sign(userKp, nonce)),
            deviceSig = b64(BosonCrypto.sign(deviceKp, nonce)),
        )

        val tokenStore = MutableTokenStore()
        // Register + discover over the production identity-pinned Director client (pinned to the dev
        // node id from the first request, see LiveDirectorTls), so this exercises the real pinned TLS
        // handshake. The messaging client below likewise pins its own service by peerId.
        val api = LiveDirectorTls.pinnedApiFactory(tokenStore).create(TestSuperNode.directorConfig)
        val coords = runBlocking {
            tokenStore.setToken(api.register(request).token)
            ServiceDiscovery.toServiceCoords(api.getNodeStatus())
        }

        val store = MessagingStoreFactory.createInMemory(context, vertx)
        val factory = BosonClientFactory(vertx)
        val config = factory.buildConfiguration(
            coords = coords,
            userKey64 = BosonCrypto.privateKeyBytes64(userKp),
            deviceKey64 = BosonCrypto.privateKeyBytes64(deviceKp),
            dataDir = File(context.cacheDir, "it-$name-${System.nanoTime()}").apply { mkdirs() }.toPath(),
            store = store,
        )
        val mc = factory.createMessagingClient(config)

        val ready = CountDownLatch(1)
        mc.addConnectionListener(object : ConnectionListener {
            override fun onReady() = ready.countDown()
        })
        mc.start().get(timeout, TimeUnit.SECONDS)
        assertTrue("$name did not reach READY", ready.await(timeout, TimeUnit.SECONDS))
        return Peer(mc, userId, name)
    }

    @Test
    fun registersAndConnectsToLiveNode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vertx = BosonClientFactory.newVertx()
        var peer: Peer? = null
        try {
            peer = registerAndConnect(context, vertx, "Solo")
            assertTrue("client not connected", peer.client.isConnected)
        } finally {
            runCatching { peer?.client?.stop()?.get(10, TimeUnit.SECONDS) }
            vertx.close()
        }
    }

    @Test
    fun friendRequestAndDirectMessageRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vertx = BosonClientFactory.newVertx()
        val peers = mutableListOf<Peer>()
        try {
            val alice = registerAndConnect(context, vertx, "Alice").also { peers += it }
            val bob = registerAndConnect(context, vertx, "Bob").also { peers += it }

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
            peers.forEach { runCatching { it.client.stop().get(10, TimeUnit.SECONDS) } }
            vertx.close()
        }
    }
}
