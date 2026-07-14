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
 * Director base-URL configuration. All client routes are mounted under "/api/v1/client/..." and
 * auth routes under "/api/v1/auth/..." (design spec sections 1.7, 2.1). The base URL is
 * user-configurable (advanced override) and persisted in DataStore; this holder is filled in M1.
 */
data class DirectorConfig(
    val baseUrl: String,
    /**
     * SHA-256 SPKI pins ("sha256/<base64>") for the Director host, applied by [DirectorApiFactory]
     * when [baseUrl] is HTTPS. Empty means no pinning (the dev default, which is cleartext anyway).
     * Populated from [KnownDirectorPins] when the config is built. X-S4 / M1-4.
     */
    val certificatePins: List<String> = emptyList(),
    /**
     * The Director's Boson node id (base58). When set and [baseUrl] is HTTPS, [DirectorApiFactory]
     * pins the connection to this Boson identity via a [DirectorTrustManagerProvider] - the same
     * identity-pinning model the messaging and ion-store clients use - so the Director's self-signed
     * ECDSA certificate (Ed25519 identity binding) is trusted without a public CA. Null falls back to
     * default system-CA trust (e.g. a Director fronted by a real CA certificate). X-S4.
     */
    val nodeId: String? = null,
) {
    val apiPrefix: String get() = "$baseUrl/api/v1"
    val clientPrefix: String get() = "$apiPrefix/client"
    val authPrefix: String get() = "$apiPrefix/auth"
}
