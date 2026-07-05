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

package io.photonmessenger.app.session

import android.content.Context
import io.photonmessenger.app.notification.MessageNotifier
import io.photonmessenger.app.service.MessagingForegroundService
import io.photonmessenger.core.boson.BosonSessionManager
import io.photonmessenger.core.model.ConnectionState
import io.photonmessenger.core.network.DirectorApi
import io.photonmessenger.core.network.DirectorApiFactory
import io.photonmessenger.core.network.DirectorConfig
import io.photonmessenger.core.network.DirectorConfigStore
import io.photonmessenger.core.network.ServiceDiscovery
import io.photonmessenger.core.network.toDirectorError
import io.photonmessenger.feature.onboarding.data.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coarse phase of the post-login session bring-up, surfaced to the UI (M1-16). */
enum class SessionPhase { IDLE, DISCOVERING, CONNECTING, CONNECTED, READY, DISCONNECTED, FAILED }

/** UI-facing bring-up status: a [phase] plus an optional error [message] when [phase] is FAILED. */
data class SessionStatus(val phase: SessionPhase, val message: String? = null)

/**
 * Single entry point for post-login session bring-up (F1 / M1-16, M1-17). On [ensureConnected] it
 * starts the foreground service, discovers messaging + ion-store coordinates from the Director
 * (`GET /api/v1/client/node`), and hands them to [BosonSessionManager.connect]. Once connected it
 * mirrors the live transport [ConnectionState]; [disconnect] tears the session down and stops the
 * service. Idempotent: concurrent/duplicate calls collapse via a mutex + phase guard.
 */
@Singleton
class SessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiFactory: DirectorApiFactory,
    private val configStore: DirectorConfigStore,
    private val sessionManager: BosonSessionManager,
    private val messageNotifier: MessageNotifier,
    private val authRepository: AuthRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** Controller-owned phase; when [active] the exposed [status] follows the transport state. */
    private val active = MutableStateFlow(false)
    private val own = MutableStateFlow(SessionStatus(SessionPhase.IDLE))

    val status: StateFlow<SessionStatus> =
        combine(own, active, sessionManager.connectionState) { own, active, transport ->
            if (active) SessionStatus(transport.toPhase()) else own
        }.stateIn(scope, SharingStarted.Eagerly, SessionStatus(SessionPhase.IDLE))

    @Volatile
    private var cachedApi: Pair<DirectorConfig, DirectorApi>? = null

    // True between ensureConnected() and disconnect(): the user intends to be online, so a
    // network-regain should retry a bring-up that failed while offline.
    @Volatile
    private var wantConnected = false

    private suspend fun api(): DirectorApi {
        val cfg: DirectorConfig = configStore.config.first()
        cachedApi?.let { (cached, api) -> if (cached == cfg) return api }
        return apiFactory.create(cfg).also { cachedApi = cfg to it }
    }

    /** Discovers coordinates and connects the messaging client. Safe to call repeatedly. */
    fun ensureConnected() {
        wantConnected = true
        scope.launch {
            mutex.withLock {
                if (active.value || own.value.phase == SessionPhase.DISCOVERING) return@launch
                own.value = SessionStatus(SessionPhase.DISCOVERING)
            }
            MessagingForegroundService.start(context)
            runCatching {
                // Register this device first: the messaging node's authenticateDevice rejects any device
                // not in the user's device table, so an unregistered device connects but never reaches
                // READY. Idempotent (409 = already registered).
                authRepository.ensureDeviceRegistered()
                val coords = ServiceDiscovery.toServiceCoords(api().getNodeStatus())
                sessionManager.connect(coords)
            }.onSuccess {
                sessionManager.messagingClient?.let { messageNotifier.attach(it) }
                active.value = true
            }.onFailure { e ->
                active.value = false
                own.value = SessionStatus(SessionPhase.FAILED, e.toDirectorError().message ?: "Couldn't connect")
                MessagingForegroundService.stop(context)
            }
        }
    }

    /** Retries a failed bring-up when the network comes back (M1-19), if the user wants to be online. */
    fun onNetworkAvailable() {
        if (wantConnected && !active.value) ensureConnected()
    }

    /** Retries a failed bring-up. */
    fun retry() {
        own.value = SessionStatus(SessionPhase.IDLE)
        ensureConnected()
    }

    /** Tears down the live session and stops the foreground service (sign-out). */
    fun disconnect() {
        wantConnected = false
        scope.launch {
            active.value = false
            own.value = SessionStatus(SessionPhase.IDLE)
            cachedApi = null
            messageNotifier.detach()
            runCatching { sessionManager.disconnect() }
            MessagingForegroundService.stop(context)
        }
    }

    private fun ConnectionState.toPhase(): SessionPhase = when (this) {
        ConnectionState.CONNECTING -> SessionPhase.CONNECTING
        ConnectionState.CONNECTED -> SessionPhase.CONNECTED
        ConnectionState.READY -> SessionPhase.READY
        ConnectionState.DISCONNECTED -> SessionPhase.DISCONNECTED
    }
}
