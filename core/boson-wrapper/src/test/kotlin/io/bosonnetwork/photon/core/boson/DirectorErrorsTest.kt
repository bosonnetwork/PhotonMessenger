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

import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.photon.core.model.AppError
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorErrorsTest {

    private fun refused(status: Int): DirectorException = DirectorException.fromResponse(status, "refused", null)

    @Test
    fun `maps each HTTP status to the matching AppError`() {
        assertTrue(refused(401).toDirectorError() is AppError.Unauthorized)
        assertTrue(refused(403).toDirectorError() is AppError.Forbidden)
        assertTrue(refused(404).toDirectorError() is AppError.NotFound)
        assertTrue(refused(408).toDirectorError() is AppError.Timeout)
        assertTrue(refused(409).toDirectorError() is AppError.Conflict)
        assertTrue(refused(412).toDirectorError() is AppError.Conflict)
        assertTrue(refused(428).toDirectorError() is AppError.PassphraseRequired)
        assertTrue(refused(429).toDirectorError() is AppError.RateLimited)
    }

    @Test
    fun `unmapped HTTP status falls back to Unknown with the code in the message`() {
        val error = refused(500).toDirectorError()
        assertTrue(error is AppError.Unknown)
        assertTrue(error.message!!.contains("500"))
    }

    @Test
    fun `a call that got no answer maps to Network`() {
        val noAnswer = DirectorException("Director request failed: Connection refused", IOException("refused"))
        assertEquals(DirectorException.NO_HTTP_STATUS, noAnswer.status)
        assertTrue(noAnswer.toDirectorError() is AppError.Network)
    }

    @Test
    fun `transport failures map to Network`() {
        assertTrue(IOException("boom").toDirectorError() is AppError.Network)
        // Subtypes of IOException (e.g. socket timeout) still classify as Network, not Timeout.
        assertTrue(SocketTimeoutException().toDirectorError() is AppError.Network)
    }

    @Test
    fun `a failure wrapped by its future is mapped by its cause`() {
        assertTrue(CompletionException(refused(409)).toDirectorError() is AppError.Conflict)
    }

    @Test
    fun `an existing AppError passes through unchanged`() {
        val original = AppError.InvalidInput("bad input")
        assertSame(original, original.toDirectorError())
    }

    @Test
    fun `anything else maps to Unknown`() {
        val error = IllegalStateException("weird").toDirectorError()
        assertTrue(error is AppError.Unknown)
        assertEquals("weird", error.message)
    }

    @Test
    fun `the Director failure is preserved as the cause`() {
        val refusal = refused(409)
        assertSame(refusal, refusal.toDirectorError().cause)
    }
}
