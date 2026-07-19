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
import retrofit2.HttpException

/**
 * Maps a Director REST failure to the unified [AppError] model (design spec 1.6). Retrofit surfaces
 * a non-2xx status as [HttpException]; the passphrase-gated operations additionally use 428
 * (a passphrase is configured but was not supplied) and 403 (the supplied passphrase is wrong), per
 * the Director ClientAPIs. Transport failures arrive as [IOException]. Anything already an [AppError]
 * (client-side validation) passes through unchanged.
 */
fun Throwable.toDirectorError(): AppError = when (this) {
    is AppError -> this
    is HttpException -> when (code()) {
        401 -> AppError.Unauthorized("Session expired; please sign in again", this)
        403 -> AppError.Forbidden("Wrong passphrase", this)
        404 -> AppError.NotFound(message, this)
        408 -> AppError.Timeout("The request timed out", this)
        409, 412 -> AppError.Conflict(message, this)
        428 -> AppError.PassphraseRequired("Passphrase required", this)
        429 -> AppError.RateLimited("The server is busy; try again in a moment", this)
        else -> AppError.Unknown("Request failed (HTTP ${code()})", this)
    }
    is IOException -> AppError.Network("Can't reach the server", this)
    else -> AppError.Unknown(message, this)
}
