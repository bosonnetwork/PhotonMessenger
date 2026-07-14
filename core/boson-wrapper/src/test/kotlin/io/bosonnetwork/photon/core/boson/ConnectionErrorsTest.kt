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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the terminal-connection-error mapping: an unrecoverable [ConnectionException] from the
 * messaging client maps to a typed [AppError] with user-facing guidance, and non-connection failures
 * fall through (null) so callers use their generic mapping.
 */
class ConnectionErrorsTest {

    @Test
    fun `session limit maps to SessionLimitExceeded`() {
        val ce = ConnectionException(
            ConnectionException.Reason.SESSION_LIMIT_EXCEEDED, "session limit exceeded", null,
        )
        val error = ce.toConnectionError()
        assertTrue(error is AppError.SessionLimitExceeded)
        // The message must actually guide the user toward the fix, not echo the wire text.
        assertTrue(error!!.message!!.contains("another device", ignoreCase = true))
        assertSame(ce, error.cause)
    }

    @Test
    fun `unacceptable protocol maps to ConnectionRejected with update guidance`() {
        val ce = ConnectionException(
            ConnectionException.Reason.UNACCEPTABLE_PROTOCOL_VERSION, "bad protocol", null,
        )
        val error = ce.toConnectionError()
        assertTrue(error is AppError.ConnectionRejected)
        assertTrue(error!!.message!!.contains("update", ignoreCase = true))
    }

    @Test
    fun `invalid credentials and not authorized map to ConnectionRejected`() {
        assertTrue(
            ConnectionException(ConnectionException.Reason.INVALID_CREDENTIALS, "bad creds", null)
                .toConnectionError() is AppError.ConnectionRejected,
        )
        assertTrue(
            ConnectionException(ConnectionException.Reason.NOT_AUTHORIZED, "nope", null)
                .toConnectionError() is AppError.ConnectionRejected,
        )
    }

    @Test
    fun `unknown reason still maps to a terminal ConnectionRejected`() {
        val error = ConnectionException(ConnectionException.Reason.UNKNOWN, "refused", null)
            .toConnectionError()
        assertTrue(error is AppError.ConnectionRejected)
    }

    @Test
    fun `connection exception is found when wrapped in a cause chain`() {
        val ce = ConnectionException(
            ConnectionException.Reason.SESSION_LIMIT_EXCEEDED, "session limit exceeded", null,
        )
        val wrapped = RuntimeException("deploy failed", IllegalStateException("start failed", ce))
        assertTrue(wrapped.toConnectionError() is AppError.SessionLimitExceeded)
    }

    @Test
    fun `non-connection failure falls through to null`() {
        assertNull(IllegalStateException("something else").toConnectionError())
    }
}
