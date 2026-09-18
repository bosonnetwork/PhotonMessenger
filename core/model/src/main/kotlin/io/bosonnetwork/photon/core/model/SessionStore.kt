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
 * Whether this profile is signed in, and as whom. Signing out ends the session only: the identity keys
 * and local data stay, so signing back in needs no import (see ProfileManager).
 *
 * The session carries no credential. Director calls authenticate with tokens the Director client issues
 * itself from the device's keys, so the session only records that the user chose to be signed in here.
 * [currentSession] is synchronous so startup can pick its first screen without suspending.
 */
interface SessionStore {
    /** The id (base58) of the signed-in user, or null when signed out. */
    fun currentSession(): String?

    /** Records [userId] as signed in (or signs out, when null). */
    suspend fun setSession(userId: String?)

    /** Signs out. */
    suspend fun clear()
}
