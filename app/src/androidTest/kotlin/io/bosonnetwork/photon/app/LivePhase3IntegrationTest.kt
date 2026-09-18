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
import io.bosonnetwork.photon.app.LiveTestHarness.Companion.TIMEOUT
import io.bosonnetwork.photon.core.boson.BosonClientFactory
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.DevicePairing
import io.bosonnetwork.photon.core.boson.PairingPayload
import io.bosonnetwork.photon.core.boson.awaitResult
import io.bosonnetwork.photon.core.database.MessagingStoreFactory
import io.bosonnetwork.photon.feature.chat.model.AttachmentSource
import io.bosonnetwork.photon.feature.chat.model.newAttachmentKey
import io.bosonnetwork.photon.feature.chat.model.remoteAttachmentToMap
import io.bosonnetwork.photon.feature.chat.model.toUi
import io.bosonnetwork.Id
import io.bosonnetwork.ionstore.exceptions.DecryptionException
import io.bosonnetwork.photonmessaging.Channel
import io.bosonnetwork.photonmessaging.ContentDisposition
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
import kotlinx.coroutines.future.await
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

    /**
     * M6-4: a new device registers its key, the existing (signed-in) device approves by sealing the
     * 64-byte user key to the new device's ephemeral Curve25519 key, the Director relays the opaque
     * blob, and the new device opens it and acts as one of the user's devices. Asserts the recovered key
     * matches and is zero-knowledge to the server (only the sealed blob crosses the wire).
     */
    @Test
    fun multiDevicePairingRoundTrip() {
        val vertx = BosonClientFactory.newVertx()
        val harness = LiveTestHarness(context, vertx)
        try {
            // Deliberately NOT a shared identity: this test pairs an additional device onto the account
            // it uses, and those devices persist server-side, so a pooled identity would accumulate a
            // device on every run of this suite forever.
            val alice = harness.register("AlicePair")

            // New device B: asks to join, signing with its device key (no account yet).
            val bDeviceKey = BosonCrypto.generateKeyPair()
            val newDevice = harness.directorAuth()
            val registrationId = runBlocking {
                newDevice.requestDeviceRegistration(bDeviceKey, "New device B", LiveTestHarness.APP_NAME).await()
            }

            // B's ephemeral pairing key (the QR payload).
            val ephemeral = DevicePairing.generateEphemeralKeyPair()
            val payload = PairingPayload(registrationId, DevicePairing.publicKeyBytes(ephemeral))
            // Round-trip the QR string the way the camera would.
            val scanned = PairingPayload.decode(payload.encode())!!

            // Alice approves: seal her user key to B's ephemeral key, relay it through the Director.
            val aliceUserKey64 = BosonCrypto.privateKeyBytes64(alice.userKey)
            val sealed = DevicePairing.sealUserKey(aliceUserKey64, scanned.ephemeralPublicKey)
            runBlocking {
                harness.director(alice).approveDeviceRegistration(registrationId, sealed, null).await()
            }

            // B finishes: the Director returns the relayed blob; B opens it with its ephemeral key.
            val approval = runBlocking { newDevice.finishDeviceRegistration(bDeviceKey, registrationId).await() }
            assertEquals(alice.userId, approval.userId)

            val recovered = DevicePairing.openUserKey(approval.userKey, ephemeral)
            assertArrayEquals("recovered user key must match the original", aliceUserKey64, recovered)

            // B is now one of Alice's devices, and acts as one with its own device key.
            val profile = runBlocking { harness.deviceDirector(approval.userId, bDeviceKey).profile.await() }
            assertEquals(alice.userId, profile.id)
        } finally {
            harness.close()
            vertx.close()
        }
    }

    /**
     * M5: upload bytes to IonStore as Alice, then fetch by object id as Bob (same dev ion-store
     * service) and assert the bytes survive intact - the library verifies sha-256 vs Ion-Content-Id on
     * download, so a successful, byte-identical get proves the integrity path.
     *
     * Unencrypted, which is what a ref written before attachment encryption existed points at; the
     * encrypted path the app actually sends today is [encryptedMediaUploadDownloadRoundTrip].
     */
    @Test
    fun mediaUploadDownloadRoundTrip() {
        val vertx = BosonClientFactory.newVertx()
        val harness = LiveTestHarness(context, vertx)
        try {
            val alice = harness.sharedAccount(LiveTestHarness.SHARED_ALICE)
            val bob = harness.sharedAccount(LiveTestHarness.SHARED_BOB)
            val aStore = harness.ionStore(alice)
            val bStore = harness.ionStore(bob)

            val data = ByteArray(64 * 1024).also { SecureRandom().nextBytes(it) }
            val obj = runBlocking {
                aStore.put()
                    .name("blob.bin")
                    .contentType("application/octet-stream")
                    .content(data)
                    .send()
                    .awaitResult()
            }

            val part = File(context.cacheDir, "it-dl-${System.nanoTime()}.bin")
            val meta = runBlocking { bStore.get(obj.id).toFile(part.toPath()).awaitResult() }
            assertTrue("download must resolve the object", meta.isPresent)
            assertArrayEquals("downloaded bytes must match the upload", data, part.readBytes())
            part.delete()
        } finally {
            harness.close()
            vertx.close()
        }
    }

    /**
     * M5 encryption: the IonStore attachment path as ChatRepository sends it - a one-time
     * [newAttachmentKey] encrypts the payload on the way up, and the recipient (who in production reads
     * the key out of the encrypted message ref) decrypts it on the way down.
     *
     * Also pins the two properties the design leans on: the stored object is ciphertext (a keyless get
     * fails rather than handing out readable bytes), and the payload is more than one 32 KB stream
     * chunk, so multi-chunk framing is exercised on a real device.
     */
    @Test
    fun encryptedMediaUploadDownloadRoundTrip() {
        val vertx = BosonClientFactory.newVertx()
        val harness = LiveTestHarness(context, vertx)
        try {
            val alice = harness.sharedAccount(LiveTestHarness.SHARED_ALICE)
            val bob = harness.sharedAccount(LiveTestHarness.SHARED_BOB)
            val aStore = harness.ionStore(alice)
            val bStore = harness.ionStore(bob)

            val data = ByteArray(200 * 1024).also { SecureRandom().nextBytes(it) }
            val key = newAttachmentKey()
            val obj = runBlocking {
                aStore.put()
                    .name("secret.bin")
                    .contentType("application/octet-stream")
                    .encrypt(key)
                    .content(data)
                    .send()
                    .awaitResult()
            }
            assertTrue("stored object must be flagged encrypted", obj.isEncrypted)

            val part = File(context.cacheDir, "it-dl-${System.nanoTime()}.bin")
            val meta = runBlocking { bStore.get(obj.id).decrypt(key).toFile(part.toPath()).awaitResult() }
            assertTrue("download must resolve the object", meta.isPresent)
            assertArrayEquals("decrypted bytes must match the plaintext", data, part.readBytes())
            part.delete()

            // Without the key the object is unreadable: the store refuses rather than yielding ciphertext.
            val keyless = File(context.cacheDir, "it-dl-${System.nanoTime()}.bin")
            try {
                runBlocking { bStore.get(obj.id).toFile(keyless.toPath()).awaitResult() }
                throw AssertionError("a keyless get of an encrypted object must fail")
            } catch (expected: DecryptionException) {
                // expected: encrypted object, no key supplied
            } finally {
                keyless.delete()
            }
        } finally {
            harness.close()
            vertx.close()
        }
    }

    /**
     * M5 encryption, end to end over a real message: Alice uploads an encrypted attachment, sends the
     * ref (key included) as a CONTENT_MESSAGE, and Bob decodes it through the app's own receive path
     * ([toUi] -> extractAttachment -> remoteAttachmentFromMap) and downloads it with the key that
     * arrived in the ref.
     *
     * This is the leg the IonStore-only tests cannot cover: that the 32-byte key survives the messaging
     * wire (CBOR object body, E2E-encrypted, decoded on another device) and still opens the object.
     */
    @Test
    fun encryptedAttachmentRefRoundTripOverMessage() {
        val vertx = BosonClientFactory.newVertx()
        val harness = LiveTestHarness(context, vertx)
        try {
            val alice = harness.sharedAccount(LiveTestHarness.SHARED_ALICE)
            val bob = harness.sharedAccount(LiveTestHarness.SHARED_BOB)
            val aClient = harness.connect(alice)
            val bClient = harness.connect(bob)
            befriend(aClient, alice.userId, bClient, bob.userId)

            // Alice: encrypt + upload, then send the ref exactly as ChatRepository.sendAttachment does.
            val data = ByteArray(120 * 1024).also { SecureRandom().nextBytes(it) }
            val key = newAttachmentKey()
            val aStore = harness.ionStore(alice)
            val obj = runBlocking {
                aStore.put()
                    .name("photo.bin").contentType("image/jpeg")
                    .encrypt(key)
                    .content(data)
                    .send()
                    .awaitResult()
            }
            val ref = remoteAttachmentToMap(
                uri = obj.uri ?: "ions://${aStore.servicePeerId}/${obj.id}",
                contentId = obj.contentId.toString(),
                mime = "image/jpeg",
                size = data.size.toLong(),
                name = "photo.bin",
                width = null,
                height = null,
                key = key,
            )

            val received = java.util.concurrent.atomic.AtomicReference<Message>()
            val arrived = CountDownLatch(1)
            bClient.addMessageListener(object : MessageListener {
                override fun onMessage(message: Message) {
                    if (message.conversationId.orElse(null) == alice.userId) {
                        received.set(message)
                        arrived.countDown()
                    }
                }

                override fun onSent(message: Message) {}
            })
            runBlocking {
                aClient.message(bob.userId)
                    .contentObject(ref)
                    .contentType("image/jpeg")
                    .contentDisposition(ContentDisposition.attachment("photo.bin"))
                    .send().awaitResult()
            }
            assertTrue("attachment message must arrive", arrived.await(TIMEOUT, TimeUnit.SECONDS))

            // Bob: decode through the production receive path and download with the key from the ref.
            val attachment = received.get().toUi(bob.userId).attachment
            assertTrue("ref must decode to a remote attachment", attachment?.source is AttachmentSource.Remote)
            val source = attachment!!.source as AttachmentSource.Remote
            assertArrayEquals("the key must survive the messaging wire", key, source.key)

            val rest = source.uri.removePrefix("ions://")
            val peerId = Id.of(rest.substringBefore('/'))
            val refId = Id.of(rest.substringAfter('/'))
            val bStore = harness.ionStore(bob)
            val part = File(context.cacheDir, "it-ref-${System.nanoTime()}.bin")
            try {
                val request = if (peerId == bStore.servicePeerId) bStore.get(refId) else bStore.get(peerId, refId)
                source.key?.let { request.decrypt(it) }
                val meta = runBlocking { request.toFile(part.toPath()).awaitResult() }
                assertTrue("download must resolve the object", meta.isPresent)
                assertEquals("content id must match the sender's ref", obj.contentId, meta.get().contentId)
                assertArrayEquals("Bob must recover Alice's plaintext", data, part.readBytes())
            } finally {
                part.delete()
            }
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
            val alice = harness.sharedAccount(LiveTestHarness.SHARED_ALICE)
            val bob = harness.sharedAccount(LiveTestHarness.SHARED_BOB)
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
            val alice = harness.sharedAccount(LiveTestHarness.SHARED_ALICE)
            val bob = harness.sharedAccount(LiveTestHarness.SHARED_BOB)
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

    /**
     * Ensures two connected peers are contacts, so they can exchange direct messages.
     *
     * The identities come from the shared pool (see [LiveTestHarness.sharedAccount]), so they are very
     * likely already friends from an earlier test in this run - and re-sending a friend request to an
     * existing contact would never produce the request/acceptance callbacks this waits on. So check
     * first and only run the handshake for a pair that has not been introduced yet. The handshake
     * itself is covered on genuinely fresh accounts by LiveConnectIntegrationTest.
     */
    private fun befriend(a: MessagingClient, aId: Id, b: MessagingClient, bId: Id) {
        val alreadyFriends = a.getContact(bId).get(TIMEOUT, TimeUnit.SECONDS).isPresent &&
            b.getContact(aId).get(TIMEOUT, TimeUnit.SECONDS).isPresent
        if (alreadyFriends)
            return

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
