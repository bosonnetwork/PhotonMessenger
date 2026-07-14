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
import io.bosonnetwork.photon.core.database.MessagingStoreFactory
import io.bosonnetwork.photon.core.model.AuthTokenStore
import io.bosonnetwork.photon.core.model.ServiceCoords
import io.bosonnetwork.photon.core.network.DirectorApi
import io.bosonnetwork.photon.core.network.ServiceDiscovery
import io.bosonnetwork.photon.core.network.model.SelfRegisterRequest
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
import kotlinx.coroutines.runBlocking
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

    /** Self-registers a fresh user + device with the live Director and resolves service coordinates. */
    fun register(name: String): Account {
        val userKp = BosonCrypto.generateKeyPair()
        val deviceKp = BosonCrypto.generateKeyPair()
        val nonce = newNonce()
        val request = SelfRegisterRequest(
            userId = BosonCrypto.idOf(userKp).toString(),
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
        // Identity-pinned from the first request: the dev node id is a stable known value, so register
        // + discovery + every later call all drive the production pinned trust path (see LiveDirectorTls).
        val api = LiveDirectorTls.pinnedApiFactory(tokenStore).create(TestSuperNode.directorConfig)
        val coords = runBlocking {
            tokenStore.setToken(api.register(request).token)
            ServiceDiscovery.toServiceCoords(api.getNodeStatus())
        }
        return Account(name, userKp, deviceKp, tokenStore, api, coords)
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

    fun ionStore(account: Account): IonStore =
        BosonClientFactory(vertx).buildIonStore(
            coords = account.coords,
            userId = account.userId.toString(),
            deviceKey64 = BosonCrypto.privateKeyBytes64(account.deviceKey),
        )

    fun close() {
        startedClients.forEach { runCatching { it.stop().get(10, TimeUnit.SECONDS) } }
        startedClients.clear()
    }

    companion object {
        const val TIMEOUT = 30L
    }
}
