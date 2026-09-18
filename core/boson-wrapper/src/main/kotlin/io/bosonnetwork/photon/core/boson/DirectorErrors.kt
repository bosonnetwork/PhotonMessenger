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
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Maps a Director failure to the unified [AppError] model (design spec 1.6). The Director client fails a
 * call it got an answer to with a [DirectorException] carrying the HTTP status; the passphrase-gated
 * operations additionally use 428 (a passphrase is configured but was not supplied) and 403 (the supplied
 * passphrase is wrong), and device pairing 408 (expired) and 412 (denied). A call that got no answer
 * carries [DirectorException.NO_HTTP_STATUS]. Anything already an [AppError] (client-side validation)
 * passes through unchanged.
 */
fun Throwable.toDirectorError(): AppError = when (val e = unwrapped()) {
    is AppError -> e
    is DirectorException -> when (e.status) {
        DirectorException.NO_HTTP_STATUS -> AppError.Network("Can't reach the server", e)
        401 -> AppError.Unauthorized("Session expired; please sign in again", e)
        403 -> AppError.Forbidden("Wrong passphrase", e)
        404 -> AppError.NotFound(e.message, e)
        408 -> AppError.Timeout("The request timed out", e)
        409, 412 -> AppError.Conflict(e.message, e)
        428 -> AppError.PassphraseRequired("Passphrase required", e)
        429 -> AppError.RateLimited("The server is busy; try again in a moment", e)
        else -> AppError.Unknown("Request failed (HTTP ${e.status})", e)
    }
    is IOException -> AppError.Network("Can't reach the server", e)
    else -> AppError.Unknown(e.message, e)
}

// A future's failure may arrive wrapped, depending on how it was awaited.
private fun Throwable.unwrapped(): Throwable {
    var e = this
    while ((e is CompletionException || e is ExecutionException) && e.cause != null) e = e.cause!!
    return e
}
