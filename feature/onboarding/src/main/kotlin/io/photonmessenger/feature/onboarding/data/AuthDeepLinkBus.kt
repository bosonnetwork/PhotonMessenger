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

package io.photonmessenger.feature.onboarding.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Result delivered from the OAuth Custom Tab back to the app via the `photonmessenger://auth` deep link. */
sealed interface AuthCallback {
    data class Token(val token: String) : AuthCallback
    data class Error(val message: String) : AuthCallback
}

/**
 * Bridges the deep-link callback captured by the Activity to the onboarding ViewModel (M1-8).
 * The Activity posts; the ViewModel collects.
 */
@Singleton
class AuthDeepLinkBus @Inject constructor() {
    private val _events = MutableSharedFlow<AuthCallback>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthCallback> = _events.asSharedFlow()

    fun post(callback: AuthCallback) {
        _events.tryEmit(callback)
    }
}
