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
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.bosonnetwork.photon.app.LiveTestHarness.Companion.TIMEOUT
import io.bosonnetwork.photon.core.boson.BosonClientFactory
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.DevicePairing
import io.bosonnetwork.photon.core.boson.PairingPayload
import io.bosonnetwork.photon.core.boson.awaitResult
import io.bosonnetwork.photon.core.database.MessagingStoreFactory
import io.bosonnetwork.photon.core.model.AuthTokenStore
import io.bosonnetwork.photon.core.network.DirectorApi
import io.bosonnetwork.photon.core.network.model.ClientAuthRequest
import io.bosonnetwork.photon.core.network.model.FinishRegistrationRequest
import io.bosonnetwork.photon.core.network.model.RegisterDeviceRequest
import io.bosonnetwork.photon.core.network.model.ReplyRegistrationRequest
import io.bosonnetwork.Id
import io.bosonnetwork.ionstore.PutOptions
import io.bosonnetwork.photonmessaging.Channel
import io.bosonnetwork.photonmessaging.ChannelListener
import io.bosonnetwork.photonmessaging.FriendRequestListener
import io.bosonnetwork.photonmessaging.InviteTicket
import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageListener
import io.bosonnetwork.photonmessaging.MessagingClient
import io.vertx.core.Vertx
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LIVE integration tests, Phase 3 (X-T3). Exercise the flows that Phases 1-2 left unverified against
 * the dev super node: multi-device pairing (M6-4), media over IonStore with cross-peer integrity (M5),
 * channels (M4), and history persistence across a client restart (M3 / Option-A Room store).
 *
 * Require the dev super node reachable at [TestSuperNode]; fail-fast where it is down.
 */
@RunWith(AndroidJUnit4::class)
class LivePhase3IntegrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private object NoToken : AuthTokenStore {
        override fun currentToken(): String? = null
        override suspend fun setToken(token: String?) {}
        override suspend fun clear() {}
    }

    // Pre-auth pairing calls use the same production identity-pinned Director client as the harness
    // account APIs (pinned to the dev node id from the first request, see LiveDirectorTls).
    private fun authlessApi(): DirectorApi =
        LiveDirectorTls.pinnedApiFactory(NoToken).create(TestSuperNode.directorConfig)

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun b64Decode(text: String): ByteArray = Base64.decode(text, Base64.URL_SAFE)

    /**
     * M6-4: a new device registers its key, the existing (signed-in) device approves by sealing the
     * 64-byte user key to the new device's ephemeral Curve25519 key, the Director relays the opaque
     * blob, and the new device opens it + signs in. Asserts the recovered key matches and is zero-
     * knowledge to the server (only the sealed blob crosses the wire).
     */
    @Test
    fun multiDevicePairingRoundTrip() {
        val vertx = BosonClientFactory.newVertx()
        val harness = LiveTestHarness(context, vertx)
        try {
            val alice = harness.register("AlicePair")

            // New device B: register its device key (auth-less, signed nonce).
            val bDeviceKey = BosonCrypto.generateKeyPair()
            val bDeviceId = BosonCrypto.idOf(bDeviceKey)
            val newDeviceApi = authlessApi()
            val regNonce = harness.newNonce()
            val registrationId = runBlocking {
                newDeviceApi.registerDevice(
                    RegisterDeviceRequest(
                        deviceId = bDeviceId.toString(),
                        deviceName = "New device B",
                        appName = "PhotonMessenger-IT",
                        nonce = b64(regNonce),
                        sig = b64(BosonCrypto.sign(bDeviceKey, regNonce)),
                    ),
                ).registrationId
            }

            // B's ephemeral pairing key (the QR payload).
            val ephemeral = DevicePairing.generateEphemeralKeyPair()
            val payload = PairingPayload(registrationId, DevicePairing.publicKeyBytes(ephemeral))
            // Round-trip the QR string the way the camera would.
            val scanned = PairingPayload.decode(payload.encode())!!

            // Alice approves: seal her user key to B's ephemeral key, relay via PATCH.
            val aliceUserKey64 = BosonCrypto.privateKeyBytes64(alice.userKey)
            val sealed = DevicePairing.sealUserKey(aliceUserKey64, scanned.ephemeralPublicKey)
            runBlocking {
                alice.api.replyRegistration(
                    registrationId,
                    ReplyRegistrationRequest(approved = true, userPrivateKey = b64(sealed)),
                )
            }

            // B finishes: the Director returns the relayed blob; B opens it with its ephemeral key.
            val finishNonce = harness.newNonce()
            val finish = runBlocking {
                newDeviceApi.finishRegistration(
                    registrationId,
                    FinishRegistrationRequest(
                        deviceId = bDeviceId.toString(),
                        nonce = b64(finishNonce),
                        sig = b64(BosonCrypto.sign(bDeviceKey, finishNonce)),
                    ),
                )
            }
            assertEquals(alice.userId.toString(), finish.userId)

            val recovered = DevicePairing.openUserKey(b64Decode(finish.userPrivateKey), ephemeral)
            assertArrayEquals("recovered user key must match the original", aliceUserKey64, recovered)

            // B signs in with its own device key now that it holds the user identity.
            val authNonce = harness.newNonce()
            val token = runBlocking {
                newDeviceApi.clientAuth(
                    ClientAuthRequest(
                        userId = finish.userId,
                        deviceId = bDeviceId.toString(),
                        nonce = b64(authNonce),
                        deviceSig = b64(BosonCrypto.sign(bDeviceKey, authNonce)),
                    ),
                ).token
            }
            assertTrue("device sign-in must return a CWT", token.isNotBlank())
        } finally {
            harness.close()
            vertx.close()
        }
    }

    /**
     * M5: upload bytes to IonStore as Alice, then fetch by object id as Bob (same dev ion-store
     * service) and assert the bytes survive intact - the library verifies sha-256 vs Ion-Content-Id on
     * download, so a successful, byte-identical get proves the integrity path.
     */
    @Test
    fun mediaUploadDownloadRoundTrip() {
        val vertx = BosonClientFactory.newVertx()
        val harness = LiveTestHarness(context, vertx)
        try {
            val alice = harness.register("AliceMedia")
            val bob = harness.register("BobMedia")
            val aStore = harness.ionStore(alice)
            val bStore = harness.ionStore(bob)

            val data = ByteArray(64 * 1024).also { SecureRandom().nextBytes(it) }
            val obj = runBlocking {
                aStore.put(
                    data,
                    PutOptions.builder().name("blob.bin").contentType("application/octet-stream").build(),
                ).awaitResult()
            }

            val part = File(context.cacheDir, "it-dl-${System.nanoTime()}.bin")
            val meta = runBlocking { bStore.get(obj.id, part.toPath()).awaitResult() }
            assertTrue("download must resolve the object", meta.isPresent)
            assertArrayEquals("downloaded bytes must match the upload", data, part.readBytes())
            part.delete()
        } finally {
            harness.close()
            vertx.close()
        }
    }

    /**
     * M4 DoD: owner (Alice) creates a channel and mints a bearer invite ticket; the ticket is passed
     * as a shareable string (InviteTicket.toString -> fromString) to a second client (Bob), who joins
     * the channel. Both clients then see Bob as a member. Exercises the InviteTicket string codec
     * end-to-end across two clients (M4-2).
     */
    @Test
    fun channelCreateAndJoinAcrossTwoClients() {
        val vertx = BosonClientFactory.newVertx()
        val harness = LiveTestHarness(context, vertx)
        try {
            val alice = harness.register("AliceChannel")
            val bob = harness.register("BobChannel")
            val aliceClient = harness.connect(alice)
            val bobClient = harness.connect(bob)

            val channel = aliceClient.createChannel(Channel.Permission.PUBLIC, "Live Test Channel", "notice", false)
                .get(TIMEOUT, TimeUnit.SECONDS)
            assertTrue("channel id must be assigned", channel.id.toString().isNotBlank())

            channel.loadMembers().get(TIMEOUT, TimeUnit.SECONDS)
            assertTrue("owner must be a member", channel.members.any { it.id == alice.userId })

            // Mint a bearer ticket and round-trip it through the string codec, as sharing would.
            val ticket = aliceClient.createInviteTicket(channel.id, null).get(TIMEOUT, TimeUnit.SECONDS)
            val shared = ticket.toString()
            assertTrue("ticket string must be non-empty", shared.isNotBlank())
            val parsed = InviteTicket.fromString(shared)
            assertEquals("codec must preserve the channel id", ticket.channelId, parsed.channelId)
            assertTrue("round-tripped ticket must be genuine", parsed.isGenuine())

            // Alice observes Bob joining.
            val bobJoined = CountDownLatch(1)
            aliceClient.addChannelListener(object : ChannelListener {
                override fun onChannelMemberJoined(ch: Channel, member: Channel.Member) {
                    if (ch.id == channel.id && member.id == bob.userId) bobJoined.countDown()
                }
                override fun onChannelCreated(channel: Channel) {}
                override fun onChannelDeleted(channel: Channel) {}
                override fun onJoinedChannel(channel: Channel) {}
                override fun onLeftChannel(channel: Channel) {}
                override fun onChannelOwnershipTransferred(channel: Channel, oldOwner: Id, newOwner: Id) {}
                override fun onChannelSessionKeyRotated(channel: Channel) {}
                override fun onChannelUpdated(channel: Channel) {}
                override fun onChannelMemberLeft(channel: Channel, member: Channel.Member) {}
                override fun onChannelMembersRemoved(channel: Channel, members: List<Channel.Member>) {}
                override fun onChannelMembersBanned(channel: Channel, banned: List<Channel.Member>) {}
                override fun onChannelMembersUnbanned(channel: Channel, unbanned: List<Channel.Member>) {}
                override fun onChannelMembersRoleChanged(channel: Channel, changed: List<Channel.Member>, role: Channel.Role) {}
            })

            // Bob joins from the shared ticket string.
            val joined = bobClient.joinChannel(parsed).get(TIMEOUT, TimeUnit.SECONDS)
            assertEquals("Bob must join the same channel", channel.id, joined.id)

            joined.loadMembers().get(TIMEOUT, TimeUnit.SECONDS)
            assertTrue("Bob must see himself as a member", joined.members.any { it.id == bob.userId })
            assertTrue("Alice must observe Bob's join", bobJoined.await(TIMEOUT, TimeUnit.SECONDS))
        } finally {
            harness.close()
            vertx.close()
        }
    }

    /**
     * M3 / Option-A persistence: Bob receives a DM, his client is stopped, then rebuilt against the
     * same named Room store + data dir + keys. The conversation and message must survive the restart.
     */
    @Test
    fun historyPersistsAcrossRestart() {
        val vertx = BosonClientFactory.newVertx()
        val harness = LiveTestHarness(context, vertx)
        val dbName = "it-persist-${System.nanoTime()}.db"
        val bobDataDir = File(context.cacheDir, "it-persist-dd-${System.nanoTime()}")
        try {
            val alice = harness.register("AlicePersistA")
            val bob = harness.register("BobPersistB")
            val aliceClient = harness.connect(alice)
            val bobStore1 = MessagingStoreFactory.create(context, vertx, dbName)
            val bobClient1 = harness.connect(bob, bobStore1, bobDataDir)

            befriend(aliceClient, alice.userId, bobClient1, bob.userId)

            val bobGotMessage = CountDownLatch(1)
            bobClient1.addMessageListener(object : MessageListener {
                override fun onMessage(message: Message) {
                    if (message.from.orElse(null) == alice.userId) bobGotMessage.countDown()
                }
                override fun onSent(message: Message) {}
            })
            aliceClient.message(bob.userId).contentText("persist me").send().get(TIMEOUT, TimeUnit.SECONDS)
            assertTrue("bob must receive the message", bobGotMessage.await(TIMEOUT, TimeUnit.SECONDS))

            // Restart Bob against the same on-disk store.
            bobClient1.stop().get(10, TimeUnit.SECONDS)
            val bobStore2 = MessagingStoreFactory.create(context, vertx, dbName)
            val bobClient2 = harness.connect(bob, bobStore2, bobDataDir)

            val conversations = runBlocking { bobClient2.getConversations().awaitResult() }
            assertTrue("conversation must survive restart", conversations.isNotEmpty())
            val convoId = conversations.first().id
            val messages = runBlocking {
                bobClient2.getMessagesBefore(convoId, Long.MAX_VALUE, 50, 0).awaitResult()
            }
            assertTrue(
                "message body must survive restart",
                messages.any { it.payloadAsContent.getBody<String>() == "persist me" },
            )
        } finally {
            harness.close()
            vertx.close()
            context.deleteDatabase(dbName)
        }
    }

    /** Minimal friend handshake so two connected peers can exchange direct messages. */
    private fun befriend(a: MessagingClient, aId: Id, b: MessagingClient, bId: Id) {
        val bGotRequest = CountDownLatch(1)
        b.addFriendRequestListener(object : FriendRequestListener {
            override fun onFriendRequest(userId: Id, hello: String) {
                if (userId == aId) bGotRequest.countDown()
            }
            override fun onFriendRequestAccepted(userId: Id) {}
        })
        val aAccepted = CountDownLatch(1)
        a.addFriendRequestListener(object : FriendRequestListener {
            override fun onFriendRequest(userId: Id, hello: String) {}
            override fun onFriendRequestAccepted(userId: Id) {
                if (userId == bId) aAccepted.countDown()
            }
        })
        a.friendRequest(bId, "hi").get(TIMEOUT, TimeUnit.SECONDS)
        assertTrue("friend request must arrive", bGotRequest.await(TIMEOUT, TimeUnit.SECONDS))
        b.acceptFriendRequest(aId).get(TIMEOUT, TimeUnit.SECONDS)
        assertTrue("acceptance must arrive", aAccepted.await(TIMEOUT, TimeUnit.SECONDS))
    }
}
