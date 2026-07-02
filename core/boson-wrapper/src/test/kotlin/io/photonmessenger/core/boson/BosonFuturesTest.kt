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

package io.photonmessenger.core.boson

import io.vertx.core.Future
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Verifies the two async->coroutine bridges (spec 4.5 / X-T2): a `java.util.concurrent`
 * CompletableFuture (MessagingClient) and a Vert.x Future (IonStore) each suspend to a value on
 * success and rethrow the underlying cause (not a wrapping ExecutionException) on failure.
 */
class BosonFuturesTest {

    @Test
    fun `completable future resolves to its value`() = runTest {
        val future = CompletableFuture.completedFuture("ok")
        assertEquals("ok", future.awaitResult())
    }

    @Test
    fun `completable future rethrows the underlying cause, not ExecutionException`() = runTest {
        val boom = IllegalStateException("boom")
        val future = CompletableFuture<String>().apply { completeExceptionally(boom) }
        val thrown = runCatching { future.awaitResult() }.exceptionOrNull()
        assertSame(boom, thrown)
    }

    @Test
    fun `vertx future resolves to its value`() = runTest {
        assertEquals(42, Future.succeededFuture(42).awaitResult())
    }

    @Test
    fun `vertx future rethrows its failure cause`() = runTest {
        val boom = IllegalArgumentException("bad")
        val thrown = runCatching { Future.failedFuture<Int>(boom).awaitResult() }.exceptionOrNull()
        assertSame(boom, thrown)
    }
}
