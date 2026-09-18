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

import io.bosonnetwork.photon.core.model.SessionStore

/**
 * [SessionStore] backed by the encrypted [SecretStore], with an in-memory copy so [currentSession] never
 * touches disk.
 */
class EncryptedSessionStore(
    private val secrets: SecretStore,
) : SessionStore {

    @Volatile
    private var cached: String? = secrets.getString(KEY_SESSION)

    override fun currentSession(): String? = cached

    override suspend fun setSession(userId: String?) {
        cached = userId
        secrets.putString(KEY_SESSION, userId)
    }

    override suspend fun clear() {
        cached = null
        secrets.remove(KEY_SESSION)
    }

    /**
     * Durably records [userId] as signed in (synchronous commit). Used to seed a DIFFERENT profile just
     * before an app relaunch, where an async apply() could be lost when the process exits. Not for the
     * normal path - use [setSession] there.
     */
    fun seedDurably(userId: String) {
        cached = userId
        secrets.putString(KEY_SESSION, userId, commit = true)
    }

    private companion object {
        // Named for what it held before the Director client issued its own tokens: the Director session
        // token. Kept so that a profile signed in then, holding a token here, is still signed in.
        const val KEY_SESSION = "director_access_token"
    }
}
