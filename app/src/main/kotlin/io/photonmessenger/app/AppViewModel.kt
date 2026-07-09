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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.photonmessenger.app.navigation.Routes
import io.photonmessenger.app.navigation.TopLevelDestination
import io.photonmessenger.app.session.SessionController
import io.photonmessenger.app.session.SessionStatus
import io.photonmessenger.core.boson.BosonSessionManager
import io.photonmessenger.core.boson.UnreadTracker
import io.photonmessenger.core.model.ConnectionState
import io.photonmessenger.feature.contacts.data.ContactRepository
import io.photonmessenger.feature.onboarding.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * App-root state (F1 / M1-16): picks the start destination from the persisted session and drives
 * the messaging bring-up. Exposes the [SessionController] status so the shell can show a connection
 * banner. A returning (signed-in) user auto-connects on launch; onboarding completion and sign-out
 * flip the session on/off.
 */
/** Unread/notification counts for the bottom-navigation tabs. */
data class TabBadges(val chats: Int = 0, val contacts: Int = 0)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val sessionController: SessionController,
    private val sessionManager: BosonSessionManager,
    private val unreadTracker: UnreadTracker,
    private val contactRepository: ContactRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    /**
     * Home only when this device is fully ready (token AND local user key); otherwise onboarding.
     * A token without a key (returning user on a fresh device) must acquire its key first, so it goes
     * to onboarding rather than Home where bring-up would fail with "user key missing" (O2).
     */
    val startDestination: String =
        if (authRepository.isReady()) TopLevelDestination.HOME.route else Routes.ONBOARDING

    val sessionStatus: StateFlow<SessionStatus> = sessionController.status

    // Re-subscribe to the pending-request stream whenever the transport connects: the flow reads the
    // client once at collection time, so it must be (re)started after the client exists.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pendingRequestCount: Flow<Int> =
        sessionManager.connectionState.flatMapLatest { state ->
            if (state == ConnectionState.CONNECTED || state == ConnectionState.READY)
                contactRepository.friendRequests().map { it.size }.catch { emit(0) }
            else
                flowOf(0)
        }

    /** Bottom-tab badges: total unread messages (Chats) and pending friend requests (Contacts). */
    val badges: StateFlow<TabBadges> =
        combine(unreadTracker.totalUnread, pendingRequestCount) { unread, requests ->
            TabBadges(chats = unread, contacts = requests)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TabBadges())

    init {
        if (authRepository.isReady()) sessionController.ensureConnected()
    }

    /** Called after onboarding completes (sign-in or identity bind) to bring the session up. */
    fun onSignedIn() = sessionController.ensureConnected()

    /** Called after sign-out to tear the session + foreground service down. */
    fun onSignedOut() = sessionController.disconnect()

    fun retryConnection() = sessionController.retry()
}
