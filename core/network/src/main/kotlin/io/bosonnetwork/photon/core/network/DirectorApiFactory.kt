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
import java.net.URI
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * Builds a [DirectorApi] bound to a specific Director base URL. The base URL is dynamic
 * (user-configurable, M1-1), so the API is rebuilt when it changes. The Bearer interceptor attaches
 * the CWT from [AuthTokenStore]. When the base URL is HTTPS and pins are configured (see
 * [KnownDirectorPins]), an OkHttp [CertificatePinner] enforces SHA-256 SPKI pinning (X-S4 / M1-4).
 */
class DirectorApiFactory(
    private val tokenStore: AuthTokenStore,
    /**
     * Supplies the identity-pinning trust manager for HTTPS Directors (see
     * [DirectorTrustManagerProvider]). Null (the default) leaves the client on default system-CA
     * trust, which is all that unit tests and cleartext dev endpoints need.
     */
    private val trustManagerProvider: DirectorTrustManagerProvider? = null,
    /**
     * Renews an expired session on a 401 by re-minting from the device's Boson identity (see
     * [SessionReminter]). Null (the default) leaves the client on the `auth/refresh` endpoint, which is
     * all tests and OAuth-lineage sessions need.
     */
    private val sessionReminter: SessionReminter? = null,
) {
    private val json: Json = DEFAULT_JSON

    /**
     * OkHttp client with Bearer auth + token refresh + certificate/identity pinning for [config].
     * Shared by the Retrofit API and the Coil image loader so avatar fetches carry the same trust
     * and authentication as API calls (the Director requires the CWT to resolve remote users).
     */
    fun createHttpClient(config: DirectorConfig): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator(tokenStore, "${config.authPrefix}/refresh", json, sessionReminter))
            .apply { certificatePinner(config)?.let { certificatePinner(it) } }
            .apply { applyIdentityPinning(config) }
            .build()

    fun create(config: DirectorConfig): DirectorApi =
        Retrofit.Builder()
            // baseUrl must end with '/'; client routes resolve against "<base>/api/v1/".
            .baseUrl("${config.apiPrefix}/")
            .client(createHttpClient(config))
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create()

    /**
     * Installs Boson identity pinning on the Director client when [trustManagerProvider] yields a
     * trust manager for [config] (i.e. a node id is configured). Trust is then anchored to the
     * Director's Boson identity - not its DNS name - so, as with the messaging/ion-store clients, the
     * peer may be reached via any host, IP, or emulator alias; OkHttp's hostname check is disabled to
     * match. When the provider returns null the client keeps default system-CA trust untouched.
     */
    private fun OkHttpClient.Builder.applyIdentityPinning(config: DirectorConfig) {
        val trustManager = trustManagerProvider?.trustManagerFor(config) ?: return
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        sslSocketFactory(sslContext.socketFactory, trustManager)
        hostnameVerifier { _, _ -> true }
    }

    companion object {
        /**
         * A [CertificatePinner] pinning the config's host to its configured pins, or null when there
         * is nothing to enforce (no pins, or a cleartext/non-HTTPS base URL). Pinning a cleartext
         * host would be a silent no-op, so we require HTTPS to avoid a false sense of protection.
         * Exposed for tests.
         */
        internal fun certificatePinner(config: DirectorConfig): CertificatePinner? {
            if (config.certificatePins.isEmpty()) return null
            val uri = runCatching { URI(config.baseUrl) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host ?: return null
            return CertificatePinner.Builder()
                .apply { config.certificatePins.forEach { add(host, it) } }
                .build()
        }

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
