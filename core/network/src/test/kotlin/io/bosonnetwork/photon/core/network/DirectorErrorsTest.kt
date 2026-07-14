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

import io.bosonnetwork.photon.core.model.AppError
import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class DirectorErrorsTest {

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    @Test
    fun `maps each HTTP status to the matching AppError`() {
        assertTrue(httpError(401).toDirectorError() is AppError.Unauthorized)
        assertTrue(httpError(403).toDirectorError() is AppError.Forbidden)
        assertTrue(httpError(404).toDirectorError() is AppError.NotFound)
        assertTrue(httpError(408).toDirectorError() is AppError.Timeout)
        assertTrue(httpError(409).toDirectorError() is AppError.Conflict)
        assertTrue(httpError(412).toDirectorError() is AppError.Conflict)
        assertTrue(httpError(428).toDirectorError() is AppError.PassphraseRequired)
    }

    @Test
    fun `unmapped HTTP status falls back to Unknown with the code in the message`() {
        val error = httpError(500).toDirectorError()
        assertTrue(error is AppError.Unknown)
        assertTrue(error.message!!.contains("500"))
    }

    @Test
    fun `transport failures map to Network`() {
        assertTrue(IOException("boom").toDirectorError() is AppError.Network)
        // Subtypes of IOException (e.g. socket timeout) still classify as Network, not Timeout.
        assertTrue(SocketTimeoutException().toDirectorError() is AppError.Network)
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
    fun `the HttpException is preserved as the cause`() {
        val http = httpError(409)
        assertSame(http, http.toDirectorError().cause)
    }
}
