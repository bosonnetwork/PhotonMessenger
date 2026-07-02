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

import io.photonmessenger.core.model.AuthTokenStore
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private class FakeStore(@Volatile var token: String?) : AuthTokenStore {
        override fun currentToken(): String? = token
        override suspend fun setToken(token: String?) { this.token = token }
        override suspend fun clear() { token = null }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clientFor(store: AuthTokenStore): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(TokenAuthenticator(store, server.url("/auth/refresh").toString(), json))
            .build()

    @Test
    fun `refreshes the token and retries once on 401`() {
        val store = FakeStore("old")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"token":"new"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = clientFor(store)
            .newCall(Request.Builder().url(server.url("/client/node")).build())
            .execute()

        assertEquals(200, response.code)
        assertEquals("new", store.token)
        assertEquals(3, server.requestCount)

        val original = server.takeRequest()
        assertEquals("Bearer old", original.getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertTrue(refresh.path!!.endsWith("/auth/refresh"))
        val retried = server.takeRequest()
        assertEquals("Bearer new", retried.getHeader("Authorization"))
        response.close()
    }

    @Test
    fun `gives up and clears the token when refresh fails`() {
        val store = FakeStore("old")
        server.enqueue(MockResponse().setResponseCode(401)) // original request
        server.enqueue(MockResponse().setResponseCode(401)) // refresh attempt

        val response = clientFor(store)
            .newCall(Request.Builder().url(server.url("/client/node")).build())
            .execute()

        assertEquals(401, response.code)
        assertNull(store.token)
        assertEquals(2, server.requestCount)
        response.close()
    }
}
