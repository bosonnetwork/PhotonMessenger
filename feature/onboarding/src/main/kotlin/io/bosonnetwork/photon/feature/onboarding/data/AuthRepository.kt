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

package io.bosonnetwork.photon.feature.onboarding.data

import android.os.Build
import io.bosonnetwork.Id
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.AuthTokenStore
import io.bosonnetwork.photon.core.network.DirectorApi
import io.bosonnetwork.photon.core.network.DirectorApiFactory
import io.bosonnetwork.photon.core.network.DirectorConfig
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.network.DirectorOAuth
import io.bosonnetwork.photon.core.network.toDirectorError
import io.bosonnetwork.photon.core.network.model.AddDeviceRequest
import io.bosonnetwork.photon.core.network.model.BindIdentityRequest
import io.bosonnetwork.photon.core.network.model.MeDto
import io.bosonnetwork.photon.core.network.model.ProviderDto
import io.bosonnetwork.photon.core.network.model.UpdateProfileRequest
import io.bosonnetwork.photon.core.security.ProfileManager
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

    /** Fully signed in with a bound Boson user id and the matching key present on this device. */
    data class Authenticated(val me: MeDto, val userId: String) : SessionState

    /**
     * The signed-in account resolves to a Boson identity ([userId]) that ALREADY has its own on-device
     * profile ([profileId]), which is not the active one. The app must open that profile - never rehost
     * the identity here. The app seeds the target profile with the freshly obtained session so it comes
     * up ready without a second sign-in.
     */
    data class ReuseProfile(val profileId: String, val userId: String) : SessionState

    /**
     * The signed-in account's identity ([userId], null when the account has no bound identity) does not
     * match the active profile and has no profile of its own, yet the active profile is already bound to
     * a DIFFERENT identity - so it cannot host this one. The app must open a fresh profile (seeded with
     * the freshly obtained session) and continue onboarding there, never overwriting the active profile.
     */
    data class NewProfileForIdentity(val userId: String?) : SessionState
}

/**
 * A pending home super node migration: this device is registered with [fromNodeId] but the configured
 * Director now reports [toNodeId]. Confirming re-registers the device with the new node; the local
 * profile data is untouched (identity is independent of the home node in the federated network).
 */
data class NodeMigration(val fromNodeId: String, val toNodeId: String)

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
    private val profileManager: ProfileManager,
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
     * Resolves which profile the signed-in account belongs to, driven ONLY by the Boson user id from
     * `/me` (never stale local state):
     * - [Authenticated] when the account's identity is the active profile's identity (proceed here);
     * - [ReuseProfile] when it already has its OWN profile elsewhere (the app opens that one);
     * - [NeedsIdentity]/[NeedsKey] when the active profile is a fresh scratch that can host the identity
     *   (create/import here);
     * - [NewProfileForIdentity] when the active profile is bound to a different identity and cannot host
     *   this one (the app opens a fresh profile and continues onboarding there).
     */
    suspend fun currentSession(): SessionState {
        val me = api().getMe()
        val accountId = me.userId?.takeIf { it.isNotEmpty() } // identity bound to this OAuth account
        val localId = keyManager.userId()?.toString()          // identity of the active profile's key
        val activeId = profileManager.activeProfileId()

        // Same identity as the active profile: proceed here.
        if (accountId != null && accountId == localId) return SessionState.Authenticated(me, accountId)

        // The account's identity already has its own profile: reuse it, never rehost.
        if (accountId != null) {
            val existing = profileManager.findByUserId(accountId)
            if (existing != null && existing.id != activeId) {
                return SessionState.ReuseProfile(existing.id, accountId)
            }
        }

        // Active profile is a fresh scratch (no local identity): host the account's identity here.
        if (localId == null) {
            return if (accountId == null) SessionState.NeedsIdentity(me) else SessionState.NeedsKey(me, accountId)
        }

        // Active profile is bound to a different identity and has no profile for this account: a new
        // profile is required so the active one is never overwritten.
        return SessionState.NewProfileForIdentity(accountId)
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
            val derivedId = BosonCrypto.idOf(kp).toString()
            // Never reuse a device key across identity changes: adopting a different identity than
            // the one this device key was registered under rotates the key and drops the stale record.
            adoptIdentity(derivedId)
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
                if (derivedId != boundId) {
                    throw AppError.InvalidInput("This key does not match your account identity")
                }
                keyManager.storeUserKey(privateKey64)
                SessionState.Authenticated(me, boundId)
            }
        }

    /**
     * Prepares this device for adopting [newUserId] as its identity: when the device key is already
     * registered under a different user, it is rotated (a device key must never straddle two
     * identities; rotation also clears the registered-node marker). A never-registered key, or one
     * already owned by the current user == [newUserId], is kept. Ownership is derived from the current
     * user key - a device key is owned by exactly one user by design.
     */
    private fun adoptIdentity(newUserId: String?) {
        val current = keyManager.userId()?.toString()
        if (current != null && current != newUserId && keyManager.registeredNodeId() != null) {
            keyManager.rotateDeviceKey()
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
            // A freshly generated user key is by definition a new identity: never carry a device key
            // registered under a previous one into it.
            adoptIdentity(null)
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
    suspend fun registerDevice(passphrase: String? = null, superNodeId: String? = null) {
        withContext(Dispatchers.IO) {
            val userId = keyManager.userId()?.toString()
                ?: throw AppError.InvalidInput("No user identity on this device")
            // Defense in depth: a device key registered under a different identity is rotated here
            // rather than re-registered across users.
            val deviceKey = keyManager.ensureDeviceKeyFor(userId)
            val deviceId = BosonCrypto.idOf(deviceKey).toString()
            val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            val request = AddDeviceRequest(
                deviceId = deviceId,
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
            // Persist the super node this device is now registered with, so subsequent bring-ups skip
            // re-registering until the node or identity changes. Its presence also marks the current
            // device key as registered. Reuse the caller's probed id when available, else fetch it.
            keyManager.setRegisteredNodeId(superNodeId ?: api().getNodeId().id)
        }
    }

    /**
     * Silent best-effort device registration for the connect path (before every bring-up). Probes the
     * Director for its super node id (public, auth-less) and skips when the stored registered-node id
     * already matches it; a mismatch (never registered, device key rotated, or the node changed) falls
     * through to registering.
     *
     * Without a match: registers when the account has no passphrase, and skips otherwise - silent
     * bring-up has no passphrase to supply, and the Director checks the passphrase BEFORE the
     * already-registered (409) short-circuit, so a re-registration attempt would return 428
     * ("Passphrase required") even for a device that is already registered. Passphrase-protected
     * accounts register this device during onboarding instead - with the passphrase, via
     * [registerDevice] - which writes the record that makes later bring-ups skip.
     *
     * If the id probe fails (network down, or an older Director without the endpoint) it returns
     * without registering; the following discovery/connect surfaces the real error, and the idempotent
     * 409 path self-heals a stale registered-node id.
     */
    suspend fun ensureDeviceRegistered() {
        val userId = keyManager.userId()?.toString() ?: return // no identity yet; nothing to register
        val nodeId = runCatching { api().getNodeId().id }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (keyManager.registeredNodeId() == nodeId) return // already registered on this node
        if (isPassphraseProtected()) return
        registerDevice(null, nodeId)
    }

    /**
     * Detects a home super node MIGRATION for an already-registered identity: the configured Director
     * now reports a different node id than the one this device is registered with. Returns null when
     * the device has never registered (a first registration is not a migration), when the probe fails,
     * or when the node still matches - so callers only prompt on a genuine, deliberate node change.
     * Detection only; the actual re-registration happens through [ensureDeviceRegistered] once confirmed.
     */
    suspend fun checkNodeMigration(): NodeMigration? {
        val current = keyManager.registeredNodeId() ?: return null // never registered: not a migration
        val nodeId = runCatching { api().getNodeId().id }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return if (current != nodeId) NodeMigration(fromNodeId = current, toNodeId = nodeId) else null
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
        cachedApi = null
        // Sign-out ends the session only; keys, the registered-node marker, and local data are retained.
        // Deleting an identity is an explicit delete-profile action, not a side effect of signing out.
    }

    private companion object {
        const val APP_NAME = "Photon"
        const val DEFAULT_DEVICE_NAME = "Android device"
        const val NONCE_BYTES = 32
        val B64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
