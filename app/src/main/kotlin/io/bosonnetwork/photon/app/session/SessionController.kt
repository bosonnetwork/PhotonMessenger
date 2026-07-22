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

package io.bosonnetwork.photon.app.session

import android.content.Context
import io.bosonnetwork.photon.app.R
import io.bosonnetwork.photon.app.notification.FriendRequestNotifier
import io.bosonnetwork.photon.app.notification.MessageNotifier
import io.bosonnetwork.photon.app.service.MessagingForegroundService
import io.bosonnetwork.photon.core.boson.BosonSessionManager
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.ConnectionState
import io.bosonnetwork.photon.core.network.DirectorApi
import io.bosonnetwork.photon.core.network.DirectorApiFactory
import io.bosonnetwork.photon.core.network.DirectorConfig
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.network.ServiceDiscovery
import io.bosonnetwork.photon.core.network.toDirectorError
import io.bosonnetwork.photon.feature.onboarding.data.AuthRepository
import io.bosonnetwork.photon.feature.onboarding.data.NodeMigration
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
    private val friendRequestNotifier: FriendRequestNotifier,
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

    // True when the last bring-up failed with an unrecoverable rejection (session limit, bad
    // protocol/credentials, unauthorized device). Retrying as-is won't help, so an automatic
    // network-regain retry is suppressed; only an explicit user Retry clears it.
    @Volatile
    private var terminalFailure = false

    // A detected home super node migration awaiting the user's confirmation, and a one-shot flag set
    // once they confirm so the next bring-up performs the re-registration instead of re-prompting.
    private val _migrationRequired = MutableStateFlow<NodeMigration?>(null)
    val migrationRequired: StateFlow<NodeMigration?> = _migrationRequired

    @Volatile
    private var migrationConfirmed = false

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
            terminalFailure = false
            // Gate a deliberate home-node change behind an explicit confirmation (the local profile is
            // kept either way). Only fires when this identity is already registered on a DIFFERENT node;
            // a first registration or an unchanged node skips straight through to the normal bring-up.
            if (!migrationConfirmed) {
                val migration = runCatching { authRepository.checkNodeMigration() }.getOrNull()
                if (migration != null) {
                    _migrationRequired.value = migration
                    own.value = SessionStatus(SessionPhase.IDLE)
                    return@launch
                }
            }
            runCatching {
                // Register this device first: the messaging node's authenticateDevice rejects any device
                // not in the user's device table, so an unregistered device connects but never reaches
                // READY. Idempotent (409 = already registered).
                authRepository.ensureDeviceRegistered()
                val coords = ServiceDiscovery.toServiceCoords(api().getNodeStatus())
                sessionManager.connect(coords)
            }.onSuccess {
                // Hold the hosting process up with the foreground service only once a session is
                // actually live. Starting it up-front and tearing it down on every failed or retried
                // bring-up thrashed the service and could pull the app back to the foreground while
                // the server was unreachable. A background retry that succeeds may not be permitted to
                // start a foreground service (Android 12+ background-start limits), so guard the start;
                // the connection is up regardless.
                runCatching { MessagingForegroundService.start(context) }
                sessionManager.messagingClient?.let {
                    messageNotifier.attach(it)
                    friendRequestNotifier.attach(it)
                }
                active.value = true
                migrationConfirmed = false
                _migrationRequired.value = null
            }.onFailure { e ->
                active.value = false
                migrationConfirmed = false
                // BosonSessionManager already maps an unrecoverable rejection to a terminal AppError;
                // toDirectorError() passes those through unchanged. Flag terminal failures so a
                // network-regain doesn't silently retry a hard rejection behind the user's back.
                val error = e.toDirectorError()
                terminalFailure = error is AppError.SessionLimitExceeded || error is AppError.ConnectionRejected
                own.value = SessionStatus(
                    SessionPhase.FAILED,
                    error.message ?: context.getString(R.string.app_session_status_could_not_connect),
                )
                // No foreground service is started until a connection succeeds, so a failed bring-up
                // (including every background retry) leaves nothing to tear down and never touches the
                // service - that is what keeps a retry loop from resurfacing the app.
            }
        }
    }

    /** Retries a failed bring-up when the network comes back (M1-19), if the user wants to be online. */
    fun onNetworkAvailable() {
        // A terminal rejection (e.g. session limit) won't clear just because the network came back,
        // so don't auto-retry it - the user must take action and tap Retry.
        if (wantConnected && !active.value && !terminalFailure) ensureConnected()
    }

    /** Retries a failed bring-up. Explicit user action, so it also clears a terminal failure. */
    fun retry() {
        terminalFailure = false
        own.value = SessionStatus(SessionPhase.IDLE)
        ensureConnected()
    }

    /** User confirmed the home node migration: re-register with the new node and connect. */
    fun confirmMigration() {
        migrationConfirmed = true
        _migrationRequired.value = null
        ensureConnected()
    }

    /** User declined the migration: stay disconnected (they can change the server back or retry). */
    fun dismissMigration() {
        _migrationRequired.value = null
        own.value = SessionStatus(SessionPhase.IDLE)
    }

    /** Tears down the live session and stops the foreground service (sign-out). */
    fun disconnect() {
        wantConnected = false
        terminalFailure = false
        scope.launch {
            active.value = false
            own.value = SessionStatus(SessionPhase.IDLE)
            cachedApi = null
            messageNotifier.detach()
            friendRequestNotifier.detach()
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
