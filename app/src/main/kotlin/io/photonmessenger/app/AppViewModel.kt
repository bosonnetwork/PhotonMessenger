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
import io.photonmessenger.app.navigation.Routes
import io.photonmessenger.app.navigation.TopLevelDestination
import io.photonmessenger.app.session.SessionController
import io.photonmessenger.app.session.SessionStatus
import io.photonmessenger.feature.onboarding.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * App-root state (F1 / M1-16): picks the start destination from the persisted session and drives
 * the messaging bring-up. Exposes the [SessionController] status so the shell can show a connection
 * banner. A returning (signed-in) user auto-connects on launch; onboarding completion and sign-out
 * flip the session on/off.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val sessionController: SessionController,
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

    init {
        if (authRepository.isReady()) sessionController.ensureConnected()
    }

    /** Called after onboarding completes (sign-in or identity bind) to bring the session up. */
    fun onSignedIn() = sessionController.ensureConnected()

    /** Called after sign-out to tear the session + foreground service down. */
    fun onSignedOut() = sessionController.disconnect()

    fun retryConnection() = sessionController.retry()
}
