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

import io.bosonnetwork.photon.core.model.AuthTokenStore
import io.bosonnetwork.photon.core.network.model.TokenDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/**
 * Refreshes the CWT and retries once on a 401 (M1-10). OkHttp invokes [authenticate] when a request
 * comes back 401; we swap in a fresh token - or one another thread already refreshed - and retry.
 * If the refresh itself fails, we clear the token and give up (return null) so the app falls back to
 * re-authentication rather than looping.
 */
class TokenAuthenticator(
    private val tokenStore: AuthTokenStore,
    private val refreshUrl: String,
    private val json: Json,
) : Authenticator {
    // A bare client with no authenticator, so the refresh call can't recurse into this one.
    private val refreshClient = OkHttpClient()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= MAX_ATTEMPTS) return null
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        synchronized(this) {
            val current = tokenStore.currentToken()
            // If another thread already refreshed since this request was sent, reuse that token.
            val token = if (!current.isNullOrEmpty() && current != failedToken) {
                current
            } else {
                refresh(failedToken) ?: return null
            }
            return response.request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
    }

    /** POSTs to the refresh endpoint with the stale token; stores + returns a new token, or null. */
    private fun refresh(staleToken: String?): String? {
        val builder = Request.Builder().url(refreshUrl).post(EMPTY_BODY)
        if (!staleToken.isNullOrEmpty()) builder.header("Authorization", "Bearer $staleToken")

        val newToken = runCatching {
            refreshClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                json.decodeFromString(TokenDto.serializer(), body).token
            }
        }.getOrNull()

        return if (newToken.isNullOrEmpty()) {
            runBlocking { tokenStore.clear() }
            null
        } else {
            runBlocking { tokenStore.setToken(newToken) }
            newToken
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        val EMPTY_BODY = "".toRequestBody()
    }
}
