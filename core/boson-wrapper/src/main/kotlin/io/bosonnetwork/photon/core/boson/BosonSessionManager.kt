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

import io.bosonnetwork.photon.core.model.ConnectionState
import io.bosonnetwork.photon.core.model.ServiceCoords
import io.bosonnetwork.ionstore.IonStore
import io.bosonnetwork.photonmessaging.ConnectionListener
import io.bosonnetwork.photonmessaging.MessagingClient
import io.bosonnetwork.photonmessaging.MessagingStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the live MessagingClient + IonStore for the signed-in session (spec 1.7, 4.4, M1-14/15/16).
 * Built lazily once keys + discovered coordinates exist; created with node == null (no embedded DHT).
 * Exposes connection state and a simple reconnect-with-backoff loop (M1-19).
 *
 * NOTE: runtime depends on the persistence backend (D-5); against the current `jdbc:sqlite:` config
 * `start()` will fail on-device until that is resolved. The construction/lifecycle wiring is complete.
 */
class BosonSessionManager(
    private val factory: BosonClientFactory,
    private val keyManager: KeyManager,
    private val store: MessagingStore,
    private val filesDir: File,
) {
    private val scope = CoroutineScope(SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    var messagingClient: MessagingClient? = null
        private set

    private val _client = MutableStateFlow<MessagingClient?>(null)

    /**
     * The live messaging client, published once it has started and nulled on disconnect. Repository
     * flows key off this so they (re)subscribe when the session comes up: a cold start composes the
     * UI before `connect()` finishes, and a flow that only read [messagingClient] once at collection
     * time would sit on an empty list until the user navigated away and back.
     */
    val client: StateFlow<MessagingClient?> = _client.asStateFlow()

    @Volatile
    var ionStore: IonStore? = null
        private set

    @Volatile
    private var shouldStayConnected = false

    // The session is fully READY only once BOTH the transport is connected (onConnected) and the
    // startup contact-sync has completed (onContactSynced). These two callbacks are NOT ordered: on a
    // warm reconnect the messaging server resumes a persistent session whose subscriptions are already
    // restored, so it delivers the contact-sync as soon as the CONNACK is sent - that can arrive
    // BEFORE the client's own SUBACK (which drives onConnected). Surfacing READY on the sync alone
    // would then let the trailing onConnected() knock the state back to CONNECTED, leaving the UI
    // stuck on "Securing connection..." forever. Gating on both, in either order, avoids that.
    @Volatile
    private var connectedFired = false

    @Volatile
    private var contactSyncedFired = false

    private val connectionListener = object : ConnectionListener {
        override fun onConnecting() {
            connectedFired = false
            contactSyncedFired = false
            _connectionState.value = ConnectionState.CONNECTING
        }
        override fun onConnected() {
            connectedFired = true
            publishConnectedOrReady()
        }
        override fun onContactSynced() {
            contactSyncedFired = true
            publishConnectedOrReady()
        }
        override fun onDisconnected() {
            connectedFired = false
            contactSyncedFired = false
            _connectionState.value = ConnectionState.DISCONNECTED
            if (shouldStayConnected) scheduleReconnect()
        }
    }

    /** READY only when connected AND contact-synced; otherwise CONNECTED (still "securing"). */
    private fun publishConnectedOrReady() {
        _connectionState.value =
            if (connectedFired && contactSyncedFired) ConnectionState.READY else ConnectionState.CONNECTED
    }

    /** Builds the clients from discovered coordinates and starts the messaging connection. */
    suspend fun connect(coords: ServiceCoords) {
        val userKey = requireNotNull(keyManager.userKeyPair()) {
            "User key missing; identity binding must complete before connecting"
        }
        val deviceKey = keyManager.ensureDeviceKey()
        val deviceKey64 = BosonCrypto.privateKeyBytes64(deviceKey)

        val dataDir = File(filesDir, "boson").apply { mkdirs() }.toPath()

        val config = factory.buildConfiguration(
            coords = coords,
            userKey64 = BosonCrypto.privateKeyBytes64(userKey),
            deviceKey64 = deviceKey64,
            dataDir = dataDir,
            store = store,
        )

        val client = factory.createMessagingClient(config).also { messagingClient = it }
        ionStore = factory.buildIonStore(
            coords = coords,
            userId = BosonCrypto.idOf(userKey).toString(),
            deviceKey64 = deviceKey64,
        )

        client.addConnectionListener(connectionListener)
        shouldStayConnected = true
        _connectionState.value = ConnectionState.CONNECTING
        try {
            client.start().awaitResult()
        } catch (e: Throwable) {
            // The client fails start() terminally only for unrecoverable rejections (bad protocol,
            // credentials, unauthorized device, or the per-user session limit); recoverable errors
            // are retried inside the client and never throw here. Surface the terminal case as a
            // typed AppError so the UI can explain it and offer the right recovery action, and drop
            // the intent to stay connected so nothing auto-reconnects behind a hard rejection.
            val terminal = e.toConnectionError()
            if (terminal != null) {
                shouldStayConnected = false
                _connectionState.value = ConnectionState.DISCONNECTED
                throw terminal
            }
            throw e
        }
        // Publish only after start() so subscribers never query a half-initialised client. The
        // reconnect loop restarts this same instance, so the value doesn't churn on a flaky link.
        _client.value = client
    }

    suspend fun disconnect() {
        shouldStayConnected = false
        messagingClient?.let { client ->
            client.removeConnectionListener(connectionListener)
            runCatching { client.stop().awaitResult() }
        }
        messagingClient = null
        _client.value = null
        ionStore = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun scheduleReconnect() {
        scope.launch {
            var attempt = 0
            while (shouldStayConnected && messagingClient?.isConnected != true) {
                val backoff = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl attempt.coerceAtMost(MAX_SHIFT))
                delay(backoff)
                if (!shouldStayConnected) return@launch
                val ok = runCatching { messagingClient?.start()?.awaitResult() }.isSuccess
                if (ok) return@launch
                attempt++
            }
        }
    }

    private companion object {
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_SHIFT = 6
    }
}
