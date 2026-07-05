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

import android.os.Build
import io.bosonnetwork.Id
import io.photonmessenger.core.boson.BosonCrypto
import io.photonmessenger.core.boson.KeyManager
import io.photonmessenger.core.model.AppError
import io.photonmessenger.core.model.AuthTokenStore
import io.photonmessenger.core.network.DirectorApi
import io.photonmessenger.core.network.DirectorApiFactory
import io.photonmessenger.core.network.DirectorConfig
import io.photonmessenger.core.network.DirectorConfigStore
import io.photonmessenger.core.network.DirectorOAuth
import io.photonmessenger.core.network.toDirectorError
import io.photonmessenger.core.network.model.AddDeviceRequest
import io.photonmessenger.core.network.model.BindIdentityRequest
import io.photonmessenger.core.network.model.MeDto
import io.photonmessenger.core.network.model.ProviderDto
import io.photonmessenger.core.network.model.UpdateProfileRequest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Outcome of consuming an OAuth callback token (spec 2.1). */
sealed interface SessionState {
    /** Authenticated but no Boson identity bound yet -> offer create/import (spec 2.2). */
    data class NeedsIdentity(val me: MeDto) : SessionState

    /**
     * A Boson identity is already bound to this account, but this device has no local user key
     * (returning user on a fresh device/reinstall). The key must be imported from another device.
     */
    data class NeedsKey(val me: MeDto, val userId: String) : SessionState

    /** Fully signed in with a bound Boson user id and the key present on this device. */
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
    private var cachedApi: Pair<DirectorConfig, DirectorApi>? = null

    private suspend fun config(): DirectorConfig = configStore.config.first()

    /** Current Director base URL (prefilled into the pre-login server step, O1). */
    suspend fun currentDirectorUrl(): String = config().baseUrl

    /** Current Director node id used to identity-pin its cert, or "" when none is set (prefill, O1). */
    suspend fun currentDirectorNodeId(): String = config().nodeId.orEmpty()

    /**
     * Persists the Director base URL and its identity-pin node id (pre-login server step, O1) and
     * drops the cached API. A self-signed HTTPS Director requires [nodeId] to be trusted; pass null or
     * blank to clear it (a Director fronted by a real CA certificate needs none).
     */
    suspend fun setDirectorUrl(url: String, nodeId: String?) {
        val cleanId = nodeId?.trim()?.ifBlank { null }
        if (cleanId != null) {
            // Fail fast with a field-specific message: an unparseable id would otherwise only surface
            // later as an opaque TLS/build failure when DirectorApiFactory constructs the trust manager.
            try {
                Id.of(cleanId)
            } catch (e: Exception) {
                throw AppError.InvalidInput("Invalid Server ID (expected a Boson node id)", e)
            }
        }
        configStore.setBaseUrl(url)
        configStore.setNodeId(cleanId)
        cachedApi = null
    }

    private suspend fun api(): DirectorApi {
        val cfg = config()
        cachedApi?.let { (cached, api) -> if (cached == cfg) return api }
        return apiFactory.create(cfg).also { cachedApi = cfg to it }
    }

    /** Device key exists before any sign-in (used for device login + IonStore CWT later). */
    fun ensureDeviceKey() {
        keyManager.ensureDeviceKey()
    }

    suspend fun providers(): List<ProviderDto> = api().getProviders()

    suspend fun authorizeUrl(provider: String): String =
        DirectorOAuth.authorizeUrl(config(), provider)

    /**
     * Consumes the `?token=` from the OAuth deep link: stores the CWT, then resolves the session
     * (identity bound? key present on this device?).
     */
    suspend fun onAuthToken(token: String): SessionState {
        tokenStore.setToken(token)
        return currentSession()
    }

    /**
     * Resolves the session for the current token: NeedsIdentity (no identity bound), NeedsKey
     * (identity bound but no local key -> import required), or Authenticated (key present).
     */
    suspend fun currentSession(): SessionState {
        val me = api().getMe()
        val userId = me.userId
        return when {
            userId.isNullOrEmpty() -> SessionState.NeedsIdentity(me)
            !keyManager.hasUserKey() -> SessionState.NeedsKey(me, userId)
            else -> SessionState.Authenticated(me, userId)
        }
    }

    /** True once this device is fully usable: a token AND a local user key both exist. */
    fun isReady(): Boolean = tokenStore.currentToken() != null && keyManager.hasUserKey()

    /** True if a user key is present on this device. */
    fun hasUserKey(): Boolean = keyManager.hasUserKey()

    /**
     * Imports a user key pasted or scanned as raw text (base58 or hex, O4). If no identity is bound to
     * this account yet, the imported key is bound; if one is, the imported key must derive to the same
     * user id. The 64-byte key is stored only after it is accepted.
     */
    suspend fun importUserKeyText(text: String): SessionState.Authenticated {
        val privateKey64 = BosonCrypto.decodePrivateKey64(text) // throws IllegalArgumentException on bad input
        return importUserKey(privateKey64)
    }

    private suspend fun importUserKey(privateKey64: ByteArray): SessionState.Authenticated =
        withContext(Dispatchers.IO) {
            val kp = BosonCrypto.keyPairFromPrivate64(privateKey64)
            val me = api().getMe()
            val boundId = me.userId
            if (boundId.isNullOrEmpty()) {
                // No identity bound to this account yet: bind the imported key as this account's identity.
                keyManager.storeUserKey(privateKey64)
                val nonce = api().getBindingNonce().nonce
                val bound = api().bindUserIdentity(
                    BindIdentityRequest(
                        publicKey = BosonCrypto.publicKeyBase58(kp),
                        signature = BosonCrypto.signNonceBase58(kp, nonce),
                    ),
                )
                tokenStore.setToken(bound.token)
                SessionState.Authenticated(api().getMe(), bound.userId)
            } else {
                // An identity is already bound: the imported key must match it, or we would fork identity.
                val derived = BosonCrypto.idOf(kp).toString()
                if (derived != boundId) {
                    throw AppError.InvalidInput("This key does not match your account identity")
                }
                keyManager.storeUserKey(privateKey64)
                SessionState.Authenticated(me, boundId)
            }
        }

    /**
     * Completes registration: generates the user keypair, signs the Director nonce, and binds the
     * public key as this session's Boson identity. Stores the returned post-bind CWT (spec 2.2).
     * A non-blank [name] or [bio] is then written to the profile so onboarding actually persists the
     * user's chosen identity (M1-11); avatar setup lives in Settings.
     */
    suspend fun bindIdentity(name: String? = null, bio: String? = null): SessionState.Authenticated =
        withContext(Dispatchers.IO) {
            val directorApi = api()
            val nonce = directorApi.getBindingNonce().nonce
            val userKey = keyManager.generateUserKey()
            val request = BindIdentityRequest(
                publicKey = BosonCrypto.publicKeyBase58(userKey),
                signature = BosonCrypto.signNonceBase58(userKey, nonce),
            )
            val bound = directorApi.bindUserIdentity(request)
            tokenStore.setToken(bound.token)

            val cleanName = name?.trim()?.ifBlank { null }
            val cleanBio = bio?.trim()?.ifBlank { null }
            if (cleanName != null || cleanBio != null) {
                directorApi.updateProfile(UpdateProfileRequest(name = cleanName, bio = cleanBio))
            }

            SessionState.Authenticated(directorApi.getMe(), bound.userId)
        }

    /**
     * True if the signed-in account requires a passphrase for sensitive actions (adding a device is
     * passphrase-gated on the Director). Onboarding checks this after acquiring an identity to decide
     * whether the user must be prompted for the passphrase before this device can be registered.
     */
    suspend fun isPassphraseProtected(): Boolean =
        withContext(Dispatchers.IO) { api().getProfile().passphraseProtected }

    /**
     * Registers THIS device under the signed-in user so the messaging service will authorize its mqtts
     * session. The Director's `authenticateDevice` rejects any device absent from the user's device table,
     * and neither the OAuth bind nor a raw-key import registers a device (only the pairing flow and
     * self-registration do) - so without this the messaging client connects but never reaches READY
     * ("always connecting"). Idempotent: a 409 (already registered) is treated as success. Must be called
     * with a bound user session and a local user key present.
     *
     * [passphrase] must be supplied when the account is passphrase-protected (adding a device is a
     * passphrase-gated action: the Director returns 428 if a passphrase is required but omitted, 403 if
     * it is wrong); pass null for a non-protected account. Note the Director verifies the passphrase
     * BEFORE the already-registered (409) short-circuit, so a re-registration with the correct
     * passphrase still succeeds (the 409 is swallowed).
     */
    suspend fun registerDevice(passphrase: String? = null) {
        withContext(Dispatchers.IO) {
            val deviceKey = keyManager.ensureDeviceKey()
            val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            val request = AddDeviceRequest(
                deviceId = BosonCrypto.idOf(deviceKey).toString(),
                deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_NAME,
                appName = APP_NAME,
                nonce = B64URL.encodeToString(nonce),
                deviceSig = B64URL.encodeToString(BosonCrypto.sign(deviceKey, nonce)),
                passphrase = passphrase?.takeIf { it.isNotBlank() },
            )
            try {
                api().addDevice(request)
            } catch (e: Exception) {
                // 409 (mapped to Conflict) = this device is already registered; anything else is a real
                // failure (a cancellation maps to a non-Conflict error and so is rethrown).
                if (e.toDirectorError() !is AppError.Conflict) throw e
            }
        }
    }

    /**
     * Silent best-effort device registration for the connect path (before every bring-up). Registers
     * this device when the account has no passphrase, and skips otherwise: silent bring-up has no
     * passphrase to supply, and the Director checks the passphrase BEFORE the already-registered (409)
     * short-circuit, so a re-registration attempt would return 428 ("Passphrase required") even for a
     * device that is already registered. Passphrase-protected accounts register this device during
     * onboarding instead - with the passphrase, via [registerDevice].
     */
    suspend fun ensureDeviceRegistered() {
        if (isPassphraseProtected()) return
        registerDevice(null)
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

    private companion object {
        const val APP_NAME = "PhotonMessenger"
        const val DEFAULT_DEVICE_NAME = "Android device"
        const val NONCE_BYTES = 32
        val B64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
