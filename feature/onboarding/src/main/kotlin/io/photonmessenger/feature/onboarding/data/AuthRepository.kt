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

package io.photonmessenger.feature.onboarding.data

import io.photonmessenger.core.boson.BosonCrypto
import io.photonmessenger.core.boson.KeyManager
import io.photonmessenger.core.model.AuthTokenStore
import io.photonmessenger.core.network.DirectorApi
import io.photonmessenger.core.network.DirectorApiFactory
import io.photonmessenger.core.network.DirectorConfig
import io.photonmessenger.core.network.DirectorConfigStore
import io.photonmessenger.core.network.DirectorOAuth
import io.photonmessenger.core.network.model.BindIdentityRequest
import io.photonmessenger.core.network.model.MeDto
import io.photonmessenger.core.network.model.ProviderDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Outcome of consuming an OAuth callback token (spec 2.1). */
sealed interface SessionState {
    /** Authenticated but no Boson identity bound yet -> show new-user profile + bind (spec 2.2). */
    data class NeedsIdentity(val me: MeDto) : SessionState

    /** Fully signed in with a bound Boson user id. */
    data class Authenticated(val me: MeDto, val userId: String) : SessionState
}

/**
 * OAuth sign-in, session, and Boson identity binding against the Director (spec 2.1-2.2,
 * M1-7..M1-13). The DirectorApi is rebuilt when the base URL changes.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val apiFactory: DirectorApiFactory,
    private val configStore: DirectorConfigStore,
    private val tokenStore: AuthTokenStore,
    private val keyManager: KeyManager,
) {
    @Volatile
    private var cachedApi: Pair<String, DirectorApi>? = null

    private suspend fun config(): DirectorConfig = configStore.config.first()

    private suspend fun api(): DirectorApi {
        val cfg = config()
        cachedApi?.let { (url, api) -> if (url == cfg.baseUrl) return api }
        return apiFactory.create(cfg).also { cachedApi = cfg.baseUrl to it }
    }

    /** Device key exists before any sign-in (used for device login + IonStore CWT later). */
    fun ensureDeviceKey() {
        keyManager.ensureDeviceKey()
    }

    suspend fun providers(): List<ProviderDto> = api().getProviders()

    suspend fun authorizeUrl(provider: String): String =
        DirectorOAuth.authorizeUrl(config(), provider)

    /**
     * Consumes the `?token=` from the OAuth deep link: stores the CWT, then reads /me to decide
     * whether identity binding is still required.
     */
    suspend fun onAuthToken(token: String): SessionState {
        tokenStore.setToken(token)
        val me = api().getMe()
        val userId = me.userId
        return if (userId.isNullOrEmpty()) SessionState.NeedsIdentity(me)
        else SessionState.Authenticated(me, userId)
    }

    /**
     * Completes registration: generates the user keypair, signs the Director nonce, and binds the
     * public key as this session's Boson identity. Stores the returned post-bind CWT (spec 2.2).
     */
    suspend fun bindIdentity(): SessionState.Authenticated = withContext(Dispatchers.IO) {
        val directorApi = api()
        val nonce = directorApi.getBindingNonce().nonce
        val userKey = keyManager.generateUserKey()
        val request = BindIdentityRequest(
            publicKey = BosonCrypto.publicKeyBase58(userKey),
            signature = BosonCrypto.signNonceBase58(userKey, nonce),
        )
        val bound = directorApi.bindUserIdentity(request)
        tokenStore.setToken(bound.token)
        SessionState.Authenticated(directorApi.getMe(), bound.userId)
    }

    /** Refreshes the CWT (spec 2.1, M1-10). Call on app resume or after a 401. */
    suspend fun refreshToken() {
        val token = api().refresh().token
        tokenStore.setToken(token)
    }

    /** True if a session token is present (used to gate onboarding vs. home at startup). */
    fun isSignedIn(): Boolean = tokenStore.currentToken() != null

    suspend fun signOut() {
        runCatching { api().signOut() }
        tokenStore.clear()
        keyManager.clear()
        cachedApi = null
    }
}
