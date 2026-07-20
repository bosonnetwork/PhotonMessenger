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

package io.bosonnetwork.photon.core.security

import io.bosonnetwork.photon.core.model.AuthTokenStore

/**
 * [AuthTokenStore] backed by the encrypted [SecretStore]. Keeps an in-memory copy so the OkHttp
 * interceptor can read the token synchronously without touching disk on every request (spec 4.8).
 */
class EncryptedAuthTokenStore(
    private val secrets: SecretStore,
) : AuthTokenStore {

    @Volatile
    private var cached: String? = secrets.getString(KEY_ACCESS_TOKEN)

    override fun currentToken(): String? = cached

    override suspend fun setToken(token: String?) {
        cached = token
        secrets.putString(KEY_ACCESS_TOKEN, token)
    }

    override suspend fun clear() {
        cached = null
        secrets.remove(KEY_ACCESS_TOKEN)
    }

    /** In-memory only: the interceptor sees [token] via [currentToken], but nothing is written to disk. */
    override fun setSessionOnly(token: String?) {
        cached = token
    }

    /** Reverts [currentToken] to the persisted value, discarding any in-memory-only session token. */
    override fun clearSessionOnly() {
        cached = secrets.getString(KEY_ACCESS_TOKEN)
    }

    /**
     * Durably writes [token] straight to disk (synchronous commit). Used to seed a DIFFERENT profile's
     * token store just before an app relaunch, where an async apply() could be lost when the process
     * exits. Not for the normal request path - use [setToken] there.
     */
    fun seedDurably(token: String) {
        cached = token
        secrets.putString(KEY_ACCESS_TOKEN, token, commit = true)
    }

    /** Durably clears the token (synchronous commit); see [seedDurably] for why the commit matters. */
    fun clearDurably() {
        cached = null
        secrets.remove(KEY_ACCESS_TOKEN, commit = true)
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "director_access_token"
    }
}
