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
import io.bosonnetwork.director.client.DirectorAuth
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.UserRegistration
import io.bosonnetwork.photon.core.boson.BosonClientFactory
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.ServiceDiscovery
import io.bosonnetwork.photon.core.boson.awaitResult
import io.bosonnetwork.photon.core.boson.toDirectorError
import io.bosonnetwork.photon.core.database.MessagingStoreFactory
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.ServiceCoords
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.ionstore.IonStore
import io.bosonnetwork.photonmessaging.ConnectionListener
import io.bosonnetwork.photonmessaging.MessagingClient
import io.bosonnetwork.photonmessaging.MessagingStore
import io.vertx.core.Vertx
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue

/**
 * Shared harness for the LIVE integration tests (X-T3). Requires the dev super node running and
 * reachable at [TestSuperNode] (Director) / the discovered mqtts + ion-store endpoints. Centralizes
 * self-registration (non-OAuth), service discovery, Director/messaging/IonStore client construction, and
 * keys so the per-phase test classes stay focused on the behavior under test.
 *
 * Construct one per test with a fresh [Vertx]; call [close] in a finally block to stop clients +
 * close Vertx.
 */
class LiveTestHarness(
    private val context: Context,
    private val vertx: Vertx,
) {
    /**
     * A registered Boson identity plus its discovered services (not yet connected). It holds no Director
     * client: a pooled account outlives the harness - and the Vert.x - that registered it; see [director].
     */
    class Account(
        val name: String,
        val userKey: Signature.KeyPair,
        val deviceKey: Signature.KeyPair,
        val coords: ServiceCoords,
    ) {
        val userId: Id get() = BosonCrypto.idOf(userKey)
        val deviceId: Id get() = BosonCrypto.idOf(deviceKey)
    }

    private val startedClients = mutableListOf<MessagingClient>()
    private val openStores = mutableListOf<IonStore>()
    private val openDirectors = mutableListOf<DirectorClient>()
    private val openAuths = mutableListOf<DirectorAuth>()

    fun newNonce(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** A Director client acting as [account]'s user, on this harness's Vert.x; closed by [close]. */
    fun director(account: Account): DirectorClient =
        LiveDirector.client(vertx, account.userKey).also { openDirectors += it }

    /** A Director client acting as [userId] through its device [deviceKey]; closed by [close]. */
    fun deviceDirector(userId: Id, deviceKey: Signature.KeyPair): DirectorClient =
        LiveDirector.deviceClient(vertx, userId, deviceKey).also { openDirectors += it }

    /** The Director client for sign-in and pairing, on this harness's Vert.x; closed by [close]. */
    fun directorAuth(): DirectorAuth = LiveDirector.auth(vertx).also { openAuths += it }

    /**
     * An identity from the shared pool, registered on first use and reused by every later request for
     * the same [name] in this test process.
     *
     * Registration is by far the most expensive thing a live test does, and it is not free to repeat:
     * the node's proof-of-work difficulty **ramps with registration volume** (effort climbed from 1 to 9
     * during one full-suite run), so a suite that registers a fresh pair per test drives its own solves
     * until they time out. Tests that merely need "some identity" should share one; only a test whose
     * subject is registration, pairing or the friend handshake needs [register].
     *
     * The pool lives for the process, not the harness, so accounts carry server-side state (contacts,
     * conversations, channels, devices) between tests. A shared identity therefore must not be assumed
     * pristine - see how LivePhase3IntegrationTest.befriend tolerates an existing contact.
     */
    fun sharedAccount(name: String): Account = sharedAccounts.getOrPut(name) { register(name) }

    /** Self-registers a fresh user + device with the live Director and resolves service coordinates. */
    fun register(name: String): Account {
        val userKp = BosonCrypto.generateKeyPair()
        val deviceKp = BosonCrypto.generateKeyPair()
        // Identity-pinned from the first request when the dev node id is set: register + discovery all
        // drive the production trust path (see LiveDirector).
        val director = LiveDirector.client(vertx, userKp, deviceKp)
        try {
            val coords = runBlocking {
                selfRegister(director, name)
                ServiceDiscovery.toServiceCoords(director.nodeStatus.await())
            }
            return Account(name, userKp, deviceKp, coords)
        } finally {
            director.close()
        }
    }

    /**
     * Registers the user with its initial device, retrying on a fresh challenge when the proof of work
     * does not land. The Director client fetches the challenge and solves it; `(n, k)`/effort always come
     * from the challenge.
     *
     * Two failure modes are retried, both of which an emulator hits for real. A 403 means the challenge
     * expired between solve and submit. A solve that overruns [POW_SOLVE_TIMEOUT_MS] is abandoned: the
     * search is memory-hard with high run-to-run variance by design, so an unlucky one can run for many
     * minutes - long enough to look like a hung test run rather than a slow one. Either way the retry
     * registers again, which solves a fresh challenge and so also re-rolls the search.
     *
     * The abandoned solve cannot be interrupted (it is a tight CPU/memory loop with no cancellation
     * points), so it runs to completion in the background; that costs some CPU but keeps a bad roll from
     * stalling the suite. Should it then land after all, the retry finds the user registered (409),
     * which is the outcome wanted.
     */
    private suspend fun selfRegister(director: DirectorClient, name: String) {
        // No passphrase: the app onboards OAuth-style, so gated ops stay passphrase-free (M6).
        val registration = UserRegistration().name(name).initialDevice("emulator", APP_NAME)
        var lastFailure: Throwable? = null
        repeat(POW_ATTEMPTS) { attempt ->
            try {
                val registered = withTimeoutOrNull(POW_SOLVE_TIMEOUT_MS) {
                    director.registerUser(registration).await()
                    true
                }
                if (registered == true) return
                lastFailure = AssertionError("proof-of-work solve exceeded ${POW_SOLVE_TIMEOUT_MS}ms")
            } catch (e: Exception) {
                when (e.toDirectorError()) {
                    is AppError.Forbidden -> lastFailure = e
                    is AppError.Conflict -> if (attempt > 0) return else throw e
                    else -> throw e
                }
            }
        }
        throw AssertionError(
            "registering $name did not complete its proof of work in $POW_ATTEMPTS attempts " +
                "(expired challenge or a solve slower than ${POW_SOLVE_TIMEOUT_MS}ms)",
            lastFailure,
        )
    }

    /**
     * Builds a MessagingClient for [account] backed by [store] (in-memory by default) and waits for
     * READY. Pass a named persistent store + a stable [dataDir] to exercise restart/persistence.
     */
    fun connect(
        account: Account,
        store: MessagingStore = MessagingStoreFactory.createInMemory(context, vertx),
        dataDir: File = File(context.cacheDir, "it-${account.name}-${System.nanoTime()}"),
    ): MessagingClient {
        val factory = BosonClientFactory(vertx)
        val config = factory.buildConfiguration(
            coords = account.coords,
            userKey64 = BosonCrypto.privateKeyBytes64(account.userKey),
            deviceKey64 = BosonCrypto.privateKeyBytes64(account.deviceKey),
            dataDir = dataDir.apply { mkdirs() }.toPath(),
            store = store,
        )
        val mc = factory.createMessagingClient(config)
        val ready = CountDownLatch(1)
        mc.addConnectionListener(object : ConnectionListener {
            override fun onContactSynced() = ready.countDown()
        })
        mc.start().get(TIMEOUT, TimeUnit.SECONDS)
        assertTrue("${account.name} did not reach READY", ready.await(TIMEOUT, TimeUnit.SECONDS))
        startedClients += mc
        return mc
    }

    /**
     * Builds an IonStore for [account]. The store is tracked and closed by [close]: each one owns an
     * HTTP client, and a full instrumented run creates enough of them that leaking them adds up.
     */
    fun ionStore(account: Account): IonStore =
        BosonClientFactory(vertx).buildIonStore(
            coords = account.coords,
            userId = account.userId.toString(),
            deviceKey64 = BosonCrypto.privateKeyBytes64(account.deviceKey),
        ).also { openStores += it }

    fun close() {
        openDirectors.forEach { runCatching { it.close().get(10, TimeUnit.SECONDS) } }
        openDirectors.clear()
        openAuths.forEach { runCatching { it.close().get(10, TimeUnit.SECONDS) } }
        openAuths.clear()
        startedClients.forEach { runCatching { it.stop().get(10, TimeUnit.SECONDS) } }
        startedClients.clear()
        openStores.forEach { runCatching { runBlocking { it.close().awaitResult() } } }
        openStores.clear()
    }

    companion object {
        const val TIMEOUT = 30L

        /** Pool names for the two identities the tests share; see [sharedAccount]. */
        const val SHARED_ALICE = "ITAlice"
        const val SHARED_BOB = "ITBob"

        // Process-scoped so the pool survives a per-test harness (and spans test classes in one run).
        private val sharedAccounts = mutableMapOf<String, Account>()

        const val APP_NAME = "PhotonMessenger-IT"

        /** Fresh challenge + solve attempts before giving up on registration (see selfRegister). */
        private const val POW_ATTEMPTS = 3

        /** How long one solve may run before it is abandoned for a fresh challenge (see selfRegister). */
        private const val POW_SOLVE_TIMEOUT_MS = 90_000L
    }
}
