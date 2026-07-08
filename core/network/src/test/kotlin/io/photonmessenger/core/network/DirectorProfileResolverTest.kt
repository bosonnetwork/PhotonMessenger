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

package io.photonmessenger.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.photonmessenger.core.model.AuthTokenStore
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectorProfileResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var configStore: DirectorConfigStore
    private lateinit var resolver: DirectorProfileResolver
    private val clock = AtomicLong(1_000_000L)

    private class FakeTokenStore : AuthTokenStore {
        override fun currentToken(): String? = "cwt"
        override suspend fun setToken(token: String?) = Unit
        override suspend fun clear() = Unit
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // A real multi-threaded scope: the resolver's fetches run truly async against MockWebServer.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "resolver_test.preferences_pb")
        }
        configStore = DirectorConfigStore(dataStore)
        runBlocking { configStore.setBaseUrl(server.url("/").toString().trimEnd('/')) }
        resolver = DirectorProfileResolver(
            apiFactory = DirectorApiFactory(FakeTokenStore()),
            configStore = configStore,
            scope = scope,
            nowMs = clock::get,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    private fun serveProfile(userId: String = "USER1", name: String = "Alice", withAvatar: Boolean = true) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.contains("/client/profile/") -> {
                    val avatar = if (withAvatar) ""","avatar":"bnr://node/api/v1/client/avatar/$userId"""" else ""
                    MockResponse().setBody("""{"id":"$userId","name":"$name","bio":"hi"$avatar}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun serveStatus(code: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(code)
        }
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
        assertEquals(true, profile.avatarUrl!!.endsWith("/api/v1/client/avatar/USER1"))
        val requests = server.requestCount

        // Fresh entry: another collection answers from the cache with no new request.
        assertEquals("Alice", resolveBlocking("USER1").name)
        assertEquals(requests, server.requestCount)
        assertEquals("Alice", resolver.cached("USER1")?.name)
    }

    @Test
    fun `no avatar field yields a null avatarUrl`() {
        serveProfile(withAvatar = false)
        assertNull(resolveBlocking("USER1").avatarUrl)
    }

    @Test
    fun `a stale entry is refetched after the TTL`() {
        serveProfile(name = "Alice")
        resolveBlocking("USER1")
        val requests = server.requestCount

        serveProfile(name = "Alicia")
        clock.addAndGet(2L * 60 * 60 * 1000) // beyond the 1h found-TTL
        resolver.prefetch("USER1")

        awaitTrue { server.requestCount > requests }
        awaitTrue { resolver.cached("USER1")?.name == "Alicia" }
    }

    @Test
    fun `404 is negative-cached within its TTL`() {
        serveStatus(404)

        resolver.prefetch("GHOST")
        awaitTrue { server.requestCount == 1 }
        assertNull(resolver.cached("GHOST"))

        // Within the negative TTL: no re-request.
        resolver.prefetch("GHOST")
        runBlocking { delay(100) }
        assertEquals(1, server.requestCount)

        // After the negative TTL: checked again.
        clock.addAndGet(11L * 60 * 1000)
        resolver.prefetch("GHOST")
        awaitTrue { server.requestCount == 2 }
    }

    @Test
    fun `a transient failure retries only after its window`() {
        serveStatus(500)

        resolver.prefetch("USER1")
        awaitTrue { server.requestCount == 1 }

        resolver.prefetch("USER1")
        runBlocking { delay(100) }
        assertEquals(1, server.requestCount)

        clock.addAndGet(60L * 1000)
        serveProfile()
        resolver.prefetch("USER1")
        awaitTrue { resolver.cached("USER1") != null }
    }

    @Test
    fun `concurrent prefetches of the same user issue one request`() {
        // A slow response keeps the first fetch in flight while the others arrive.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody("""{"id":"USER1","name":"Alice"}""")
                    .setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        repeat(10) { resolver.prefetch("USER1") }

        awaitTrue { resolver.cached("USER1") != null }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a Director config change clears the cache`() {
        serveProfile()
        resolveBlocking("USER1")

        runBlocking { configStore.setBaseUrl(server.url("/other").toString().trimEnd('/')) }

        awaitTrue { resolver.cached("USER1") == null }
    }
}
