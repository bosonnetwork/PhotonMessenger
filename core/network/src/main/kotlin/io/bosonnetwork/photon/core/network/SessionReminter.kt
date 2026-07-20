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
 * Mints a fresh Director session CWT from the device's Boson identity (via `client/auth`), used by
 * [TokenAuthenticator] to renew an expired session on a 401. This replaces `auth/refresh` for
 * Boson-identity sessions: a clientAuth CWT carries no OAuth `sid`, so the Director cannot refresh it -
 * but the device can always re-prove key possession and mint a new one. Called synchronously on an
 * OkHttp thread; implementations must be blocking-safe. The concrete signer lives in the app layer
 * (it needs the key store), so this stays a plain interface here.
 */
fun interface SessionReminter {
    /** Returns a freshly minted CWT, or null when no local identity is available or minting failed. */
    fun remint(): String?
}
