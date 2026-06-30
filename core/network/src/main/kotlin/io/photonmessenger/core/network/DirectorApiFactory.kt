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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * Builds a [DirectorApi] bound to a specific Director base URL. The base URL is dynamic
 * (user-configurable, M1-1), so the API is rebuilt when it changes. The Bearer interceptor attaches
 * the CWT from [AuthTokenStore]. TLS pinning is layered on the OkHttpClient in M1-4.
 */
class DirectorApiFactory(
    private val tokenStore: AuthTokenStore,
) {
    private val json: Json = DEFAULT_JSON

    fun create(config: DirectorConfig): DirectorApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .build()

        return Retrofit.Builder()
            // baseUrl must end with '/'; client routes resolve against "<base>/api/v1/".
            .baseUrl("${config.apiPrefix}/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
