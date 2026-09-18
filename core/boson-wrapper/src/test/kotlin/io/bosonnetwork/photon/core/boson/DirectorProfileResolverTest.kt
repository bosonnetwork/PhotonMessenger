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
import io.bosonnetwork.photon.core.model.ResolvedProfile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DirectorProfileResolverTest {

    private lateinit var scope: CoroutineScope
    private lateinit var resolver: DirectorProfileResolver
    private val clock = AtomicLong(1_000_000L)
    private val directorChanges = MutableSharedFlow<Any>(extraBufferCapacity = 1)

    /** What the Director answers, and how often it was asked. */
    @Volatile
    private var answer: suspend (String) -> ResolvedProfile? = { null }
    private val lookups = AtomicInteger()

    @Before
    fun setUp() {
        // A real multi-threaded scope: the resolver's fetches run truly async.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        resolver = DirectorProfileResolver(
            directorChanges = directorChanges,
            lookup = { userId -> lookups.incrementAndGet(); answer(userId) },
            scope = scope,
            nowMs = clock::get,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun serveProfile(name: String = "Alice", withAvatar: Boolean = true) {
        answer = { userId ->
            ResolvedProfile(userId, name, "hi", if (withAvatar) DirectorAvatars.uri(userId) else null)
        }
    }

    private fun serveFailure(status: Int) {
        answer = { throw DirectorException.fromResponse(status, "failed", null) }
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in time")
            Thread.sleep(20)
        }
    }

    private fun resolveBlocking(userId: String) = runBlocking {
        withTimeout(5_000) { resolver.profile(userId).filterNotNull().first() }
    }

    @Test
    fun `resolves and caches a profile`() {
        serveProfile()

        val profile = resolveBlocking("USER1")

        assertEquals("Alice", profile.name)
        assertEquals("hi", profile.bio)
        assertEquals(DirectorAvatars.uri("USER1"), profile.avatarUrl)
        val requests = lookups.get()

        // Fresh entry: another collection answers from the cache with no new request.
        assertEquals("Alice", resolveBlocking("USER1").name)
        assertEquals(requests, lookups.get())
        assertEquals("Alice", resolver.cached("USER1")?.name)
    }

    @Test
    fun `no avatar yields a null avatarUrl`() {
        serveProfile(withAvatar = false)
        assertNull(resolveBlocking("USER1").avatarUrl)
    }

    @Test
    fun `a stale entry is refetched after the TTL`() {
        serveProfile(name = "Alice")
        resolveBlocking("USER1")
        val requests = lookups.get()

        serveProfile(name = "Alicia")
        clock.addAndGet(2L * 60 * 60 * 1000) // beyond the 1h found-TTL
        resolver.prefetch("USER1")

        awaitTrue { lookups.get() > requests }
        awaitTrue { resolver.cached("USER1")?.name == "Alicia" }
    }

    @Test
    fun `an unknown user is negative-cached within its TTL`() {
        answer = { null }

        resolver.prefetch("GHOST")
        awaitTrue { lookups.get() == 1 }
        assertNull(resolver.cached("GHOST"))

        // Within the negative TTL: no re-request.
        resolver.prefetch("GHOST")
        runBlocking { delay(100) }
        assertEquals(1, lookups.get())

        // After the negative TTL: checked again.
        clock.addAndGet(11L * 60 * 1000)
        resolver.prefetch("GHOST")
        awaitTrue { lookups.get() == 2 }
    }

    @Test
    fun `a 404 failure is negative-cached like an unknown user`() {
        serveFailure(404)

        resolver.prefetch("GHOST")
        awaitTrue { lookups.get() == 1 }

        // A transient failure would retry after 30s; a 404 waits out the 10min negative TTL.
        clock.addAndGet(60L * 1000)
        resolver.prefetch("GHOST")
        runBlocking { delay(100) }
        assertEquals(1, lookups.get())
    }

    @Test
    fun `a transient failure retries only after its window`() {
        serveFailure(500)

        resolver.prefetch("USER1")
        awaitTrue { lookups.get() == 1 }

        resolver.prefetch("USER1")
        runBlocking { delay(100) }
        assertEquals(1, lookups.get())

        clock.addAndGet(60L * 1000)
        serveProfile()
        resolver.prefetch("USER1")
        awaitTrue { resolver.cached("USER1") != null }
    }

    @Test
    fun `concurrent prefetches of the same user issue one request`() {
        // A slow answer keeps the first fetch in flight while the others arrive.
        answer = { userId ->
            delay(300)
            ResolvedProfile(userId, "Alice", null, null)
        }

        repeat(10) { resolver.prefetch("USER1") }

        awaitTrue { resolver.cached("USER1") != null }
        assertEquals(1, lookups.get())
    }

    @Test
    fun `a Director change clears the cache`() {
        serveProfile()
        resolveBlocking("USER1")

        directorChanges.tryEmit(Unit)

        awaitTrue { resolver.cached("USER1") == null }
    }
}
