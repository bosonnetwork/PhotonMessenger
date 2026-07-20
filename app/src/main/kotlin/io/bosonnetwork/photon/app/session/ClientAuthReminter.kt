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

package io.bosonnetwork.photon.app.session

import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.network.SessionReminter
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Renews a Director session by re-minting a clientAuth CWT from the device's stored user key (proving key
 * possession over a fresh nonce), independent of OAuth and of `auth/refresh` (which cannot renew a
 * sid-less clientAuth token). Uses a bare OkHttp client - `client/auth` is a public, unauthenticated
 * route that returns 201, so it never recurses into the session authenticator. Returns null when there is
 * no local identity or the call fails, so the caller signs out and falls back to onboarding.
 */
class ClientAuthReminter(
    private val configStore: DirectorConfigStore,
    private val keyManager: KeyManager,
) : SessionReminter {
    private val client = OkHttpClient()
    private val b64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    override fun remint(): String? = runCatching {
        val kp = keyManager.userKeyPair() ?: return null
        val cfg = runBlocking { configStore.config.first() }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        // Wire matches ClientAuthRequest (user sign-in branch): userId base58, nonce/userSig base64url.
        val body = JSONObject()
            .put("userId", BosonCrypto.idOf(kp).toString())
            .put("nonce", b64.encodeToString(nonce))
            .put("userSig", b64.encodeToString(BosonCrypto.sign(kp, nonce)))
            .toString()
        val request = Request.Builder()
            .url("${cfg.apiPrefix}/client/auth")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null
            else JSONObject(resp.body?.string().orEmpty()).optString("token").ifBlank { null }
        }
    }.getOrNull()

    private companion object {
        const val NONCE_BYTES = 32
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
