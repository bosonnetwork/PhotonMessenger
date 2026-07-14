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

package io.bosonnetwork.photon.core.network

import javax.net.ssl.X509TrustManager

/**
 * Supplies the [X509TrustManager] that [DirectorApiFactory] installs on the Director HTTPS client.
 *
 * This is a Boson-free seam so `:core:network` need not depend on the Boson stack: the identity
 * pinning itself (Boson [io.bosonnetwork.crypto.HybridTrustManager] over the Director's node id) is
 * implemented in `:core:boson-wrapper`, which has both this interface and the Boson types on its
 * classpath.
 */
fun interface DirectorTrustManagerProvider {
    /**
     * Returns an identity-pinning trust manager for [config] (pinned to [DirectorConfig.nodeId]), or
     * `null` to fall back to default system-CA trust - e.g. when no node id is configured, or the
     * Director is fronted by a real CA certificate.
     */
    fun trustManagerFor(config: DirectorConfig): X509TrustManager?
}
