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

import io.bosonnetwork.photon.core.boson.BosonDirectorTrustManagerProvider
import io.bosonnetwork.photon.core.model.AuthTokenStore
import io.bosonnetwork.photon.core.network.DirectorApiFactory

/**
 * Director HTTPS trust helper for the LIVE integration tests.
 *
 * Mirrors production exactly: builds the app's identity-pinning Director client
 * ([BosonDirectorTrustManagerProvider]), which pins the self-signed ECDSA certificate to
 * `DirectorConfig.nodeId`. Tests supply the node id via [TestSuperNode], so the pin holds from the
 * very first request (registration) - no trust-on-first-use.
 */
object LiveDirectorTls {
    /** The production identity-pinning factory: pins the Director cert to `DirectorConfig.nodeId`. */
    fun pinnedApiFactory(tokenStore: AuthTokenStore): DirectorApiFactory =
        DirectorApiFactory(tokenStore, BosonDirectorTrustManagerProvider())
}
