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
 * Unified domain error model. Every failure surfaced to the UI - whether it originates from the
 * MessagingClient (CompletableFuture completing exceptionally with a MessagingException subtype),
 * from IonStore (Vert.x Future failure), or from a Director REST HTTP status - is mapped into one
 * of these cases. See design spec section 1.6.
 */
sealed class AppError(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    /** No network / transport unreachable (DNS, socket, mqtts drop). */
    class Network(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Operation timed out (e.g. MessageTimeoutException). */
    class Timeout(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Authentication required or token rejected (HTTP 401). */
    class Unauthorized(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Caller authenticated but not permitted (HTTP 403, InsufficientPermissionException). */
    class Forbidden(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /**
     * The account has a passphrase configured but none was supplied for a passphrase-gated
     * operation (HTTP 428 Precondition Required). The UI should prompt for the passphrase and retry.
     */
    class PassphraseRequired(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Target resource not found (HTTP 404). */
    class NotFound(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** State conflict, e.g. identity already bound (HTTP 409, 412). */
    class Conflict(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Invalid client-side argument (thrown synchronously by the client). */
    class InvalidInput(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Content integrity failure (IonStore ObjectIntegrityException). */
    class Integrity(message: String? = null, cause: Throwable? = null) : AppError(message, cause)

    /** Anything not otherwise classified. */
    class Unknown(message: String? = null, cause: Throwable? = null) : AppError(message, cause)
}
