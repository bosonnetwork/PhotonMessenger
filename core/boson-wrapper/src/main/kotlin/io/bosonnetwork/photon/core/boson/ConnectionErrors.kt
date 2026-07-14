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

package io.bosonnetwork.photon.core.boson

import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photonmessaging.exceptions.ConnectionException

/**
 * Maps a terminal [ConnectionException] from the messaging client to a typed [AppError] carrying a
 * user-facing message and recovery guidance.
 *
 * The client only fails start()/connect() terminally for unrecoverable rejections (see
 * `PhotonMessagingClient.terminalConnectionFailure`); recoverable errors are retried inside the
 * client and never surface here. So any [ConnectionException] that reaches the app is terminal and
 * must NOT be auto-retried by the app - it needs a user-facing explanation and an explicit recovery
 * action instead.
 *
 * Returns null when neither [this] nor anything in its cause chain is a [ConnectionException], so
 * callers can fall back to their generic error mapping (e.g. `toDirectorError`).
 */
fun Throwable.toConnectionError(): AppError? {
    val ce = findConnectionException() ?: return null
    return when (ce.reason()) {
        ConnectionException.Reason.SESSION_LIMIT_EXCEEDED -> AppError.SessionLimitExceeded(
            "Session limit reached. You're signed in on too many devices at once. " +
                "Sign out of Photon on another device, then tap Retry.",
            ce,
        )
        ConnectionException.Reason.UNACCEPTABLE_PROTOCOL_VERSION -> AppError.ConnectionRejected(
            "This version of Photon is out of date. Please update the app to reconnect.",
            ce,
        )
        ConnectionException.Reason.INVALID_CREDENTIALS -> AppError.ConnectionRejected(
            "Your session is no longer valid. Please sign out and sign in again.",
            ce,
        )
        ConnectionException.Reason.NOT_AUTHORIZED -> AppError.ConnectionRejected(
            "This device isn't authorized to connect. Please sign out and sign in again.",
            ce,
        )
        ConnectionException.Reason.UNKNOWN -> AppError.ConnectionRejected(
            "Couldn't connect to the messaging server. Please try again later.",
            ce,
        )
    }
}

/** Walks the cause chain (guarding against a self-referential cause) for a [ConnectionException]. */
private fun Throwable.findConnectionException(): ConnectionException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is ConnectionException) return current
        val next = current.cause
        if (next === current) break
        current = next
    }
    return null
}
