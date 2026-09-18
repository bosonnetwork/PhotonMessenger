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

/**
 * The Director this profile talks to: its base URL (scheme, host, port and any path prefix, without
 * `/api/v1`), user-configurable and persisted in DataStore (design spec sections 1.7, 2.1).
 */
data class DirectorConfig(
    val baseUrl: String,
    /**
     * The Director's Boson node id (base58). When set and [baseUrl] is HTTPS, the Director client pins
     * the connection to this Boson identity - the same identity-pinning model the messaging and ion-store
     * clients use - so the Director's self-signed ECDSA certificate (Ed25519 identity binding) is trusted
     * without a public CA; a CA-signed certificate is still accepted. It also binds the client's access
     * tokens to this node. Null leaves default system-CA trust (e.g. a Director fronted by a real CA
     * certificate), with tokens bound to the node id the Director reports. X-S4.
     */
    val nodeId: String? = null,
)
