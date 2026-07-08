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

package io.photonmessenger.app.image

import io.photonmessenger.core.network.DirectorApiFactory
import io.photonmessenger.core.network.DirectorConfigStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OkHttp [Call.Factory] for the Coil image loader that carries the same Bearer auth + certificate/
 * identity pinning as the Director API client (avatars for REMOTE users require the CWT, and a
 * self-signed Director requires identity pinning). The loader is created once at Application scope,
 * so this indirection swaps the underlying client whenever the Director config changes.
 */
@Singleton
class DirectorImageCallFactory @Inject constructor(
    apiFactory: DirectorApiFactory,
    configStore: DirectorConfigStore,
) : Call.Factory {

    // Pre-config fallback for the first milliseconds of process life; carries no pinning and can
    // only fail closed (TLS/401 -> the avatar falls back to initials).
    @Volatile
    private var client: OkHttpClient = OkHttpClient()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            configStore.config.collect { client = apiFactory.createHttpClient(it) }
        }
    }

    override fun newCall(request: Request): Call = client.newCall(request)
}
