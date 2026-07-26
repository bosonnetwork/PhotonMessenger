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
import io.bosonnetwork.photon.core.boson.BosonClientFactory
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.awaitResult
import io.bosonnetwork.photon.core.database.MessagingStoreFactory
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.AuthTokenStore
import io.bosonnetwork.photon.core.model.ServiceCoords
import io.bosonnetwork.photon.core.network.DirectorApi
import io.bosonnetwork.photon.core.network.ServiceDiscovery
import io.bosonnetwork.photon.core.network.toDirectorError
import io.bosonnetwork.photon.core.network.model.SelfRegisterRequest
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.crypto.pow.RegistrationPowClient
import io.bosonnetwork.ionstore.IonStore
import io.bosonnetwork.photonmessaging.ConnectionListener
import io.bosonnetwork.photonmessaging.MessagingClient
import io.bosonnetwork.photonmessaging.MessagingStore
import io.vertx.core.Vertx
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue

/**
 * Shared harness for the LIVE integration tests (X-T3). Requires the dev super node running and
 * reachable at [TestSuperNode] (Director) / the discovered mqtts + ion-store endpoints. Centralizes
 * self-registration (non-OAuth), service discovery, client/IonStore construction, and keys so the
 * per-phase test classes stay focused on the behavior under test.
 *
 * Construct one per test with a fresh [Vertx]; call [close] in a finally block to stop clients +
 * close Vertx.
 */
class LiveTestHarness(
    private val context: Context,
    private val vertx: Vertx,
) {
    /** A registered Boson identity plus the discovery + REST handles it owns (not yet connected). */
    class Account(
        val name: String,
        val userKey: Signature.KeyPair,
        val deviceKey: Signature.KeyPair,
        val tokenStore: AuthTokenStore,
        val api: DirectorApi,
        val coords: ServiceCoords,
    ) {
        val userId: Id get() = BosonCrypto.idOf(userKey)
        val deviceId: Id get() = BosonCrypto.idOf(deviceKey)
    }

    private val startedClients = mutableListOf<MessagingClient>()
    private val openStores = mutableListOf<IonStore>()

    private class MutableTokenStore : AuthTokenStore {
        @Volatile private var token: String? = null
        override fun currentToken(): String? = token
        override suspend fun setToken(token: String?) { this.token = token }
        override suspend fun clear() { token = null }
    }

    /** Director Jackson endpoints decode byte[] with base64url, no pad (see reference_director_wire_encoding). */
    fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun newNonce(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

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
        val tokenStore = MutableTokenStore()
        // Identity-pinned from the first request: the dev node id is a stable known value, so register
        // + discovery + every later call all drive the production pinned trust path (see LiveDirectorTls).
        val api = LiveDirectorTls.pinnedApiFactory(tokenStore).create(TestSuperNode.directorConfig)
        val coords = runBlocking {
            tokenStore.setToken(selfRegister(api, name, userKp, deviceKp))
            ServiceDiscovery.toServiceCoords(api.getNodeStatus())
        }
        return Account(name, userKp, deviceKp, tokenStore, api, coords)
    }

    /**
     * Submits the registration, retrying with a fresh challenge when the proof of work does not land.
     *
     * Two failure modes are retried, both of which an emulator hits for real. A 403 means the challenge
     * expired between solve and submit. A solve that overruns [POW_SOLVE_TIMEOUT_MS] is abandoned: the
     * search is memory-hard with high run-to-run variance by design, so an unlucky one can run for many
     * minutes - long enough to look like a hung test run rather than a slow one. Either way the retry
     * needs a fresh challenge AND a fresh solve (the two are bound), so the whole request is rebuilt on
     * a new challenge, which also re-rolls the search.
     *
     * The abandoned solve cannot be interrupted (it is a tight CPU/memory loop with no cancellation
     * points), so its thread runs to completion in the background; that costs some CPU but keeps a bad
     * roll from stalling the suite.
     */
    private suspend fun selfRegister(
        api: DirectorApi,
        name: String,
        userKp: Signature.KeyPair,
        deviceKp: Signature.KeyPair,
    ): String {
        var lastFailure: Throwable? = null
        repeat(POW_ATTEMPTS) {
            val request = withTimeoutOrNull(POW_SOLVE_TIMEOUT_MS) {
                selfRegisterRequest(api, name, userKp, deviceKp)
            }
            if (request == null) {
                lastFailure = AssertionError("proof-of-work solve exceeded ${POW_SOLVE_TIMEOUT_MS}ms")
                return@repeat
            }
            try {
                return api.register(request).token
            } catch (e: Exception) {
                if (e.toDirectorError() !is AppError.Forbidden) throw e
                lastFailure = e
            }
        }
        throw AssertionError(
            "registering $name did not complete its proof of work in $POW_ATTEMPTS attempts " +
                "(expired challenge or a solve slower than ${POW_SOLVE_TIMEOUT_MS}ms)",
            lastFailure,
        )
    }

    /**
     * Builds the registration request the node's policy actually accepts: a proof-of-work registration
     * when the node offers a challenge (the `pow`/`either` policy, which is what the dev node runs -
     * a legacy nonce request is rejected there with 400), or the legacy nonce form when the challenge
     * endpoint 404s (an `open`/OAuth-only node). Mirrors AuthRepository.createAccountWithPow;
     * `(n, k)`/effort always come from the challenge.
     */
    private suspend fun selfRegisterRequest(
        api: DirectorApi,
        name: String,
        userKp: Signature.KeyPair,
        deviceKp: Signature.KeyPair,
    ): SelfRegisterRequest {
        val userId = BosonCrypto.idOf(userKp).toString()
        val deviceId = BosonCrypto.idOf(deviceKp).toString()
        // Retrofit's HttpException is internal to :core:network, so branch on the mapped domain error -
        // exactly as AuthRepository.powAvailable does.
        val challenge = try {
            api.getRegistrationChallenge()
        } catch (e: Exception) {
            if (e.toDirectorError() is AppError.NotFound) null else throw e
        }

        if (challenge == null) {
            val nonce = newNonce()
            return SelfRegisterRequest(
                userId = userId,
                // No passphrase: the app onboards OAuth-style, so gated ops stay passphrase-free (M6).
                passphrase = null,
                userName = name,
                deviceId = deviceId,
                deviceName = "emulator",
                appName = APP_NAME,
                nonce = b64(nonce),
                userSig = b64(BosonCrypto.sign(userKp, nonce)),
                deviceSig = b64(BosonCrypto.sign(deviceKp, nonce)),
            )
        }

        val superNodeId = Id.of(api.getNodeId().id).bytesUnsafe()
        val challengeNonce = Base64.decode(challenge.nonce, Base64.URL_SAFE)
        val solved = withContext(Dispatchers.Default) {
            RegistrationPowClient.solve(
                superNodeId, userKp, challenge.n, challenge.k, challenge.effort,
                challengeNonce, MAX_POW_NONCES,
            )
        }
        val deviceSig = RegistrationPowClient.sign(
            superNodeId, deviceKp, challengeNonce, solved.powNonce, challenge.effort,
        )
        return SelfRegisterRequest(
            userId = userId,
            passphrase = null,
            userName = name,
            deviceId = deviceId,
            deviceName = "emulator",
            appName = APP_NAME,
            userSig = b64(solved.signature),
            deviceSig = b64(deviceSig),
            challenge = challenge.challenge,
            challengeSig = challenge.challengeSig,
            powNonce = b64(solved.powNonce),
            solution = solved.solution.toList(),
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

        private const val APP_NAME = "PhotonMessenger-IT"

        /** Equihash nonce search budget, as in AuthRepository (the solve is memory-hard by design). */
        private const val MAX_POW_NONCES = 1_000_000L

        /** Fresh challenge + solve attempts before giving up on registration (see selfRegister). */
        private const val POW_ATTEMPTS = 3

        /** How long one solve may run before it is abandoned for a fresh challenge (see selfRegister). */
        private const val POW_SOLVE_TIMEOUT_MS = 90_000L
    }
}
