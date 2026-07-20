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

package io.bosonnetwork.photon.core.model

/**
 * Holds the current Director CWT (`Authorization: Bearer <token>`). The token is a secret and is
 * stored encrypted at rest (impl in :core:security, M1-3). [currentToken] is synchronous so the
 * OkHttp auth interceptor can read it on the network thread.
 */
interface AuthTokenStore {
    /** The current CWT, or null when signed out. Read synchronously by the interceptor. */
    fun currentToken(): String?

    /** Persists (or clears, when null) the CWT. */
    suspend fun setToken(token: String?)

    /** Wipes the token (sign-out). */
    suspend fun clear()

    /**
     * Sets an IN-MEMORY-ONLY session token that is NEVER written to disk - the transient OAuth token (and
     * the freshly minted Boson-identity token) used DURING onboarding, before the identity is committed to
     * a profile. This keeps onboarding atomic: a foreign active profile never gets a stray token on its
     * disk, so an abandoned or killed onboarding leaves no persisted session. Persisting implementations
     * MUST override so [currentToken] reflects it without a disk write; the default is a no-op suitable for
     * in-memory test doubles that set their token field directly.
     */
    fun setSessionOnly(token: String?) {}

    /** Drops any in-memory-only override, reverting [currentToken] to the persisted value. Default no-op. */
    fun clearSessionOnly() {}
}
