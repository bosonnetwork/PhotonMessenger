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
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.crypto.pow.RegistrationPowClient
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
import io.bosonnetwork.photon.core.network.model.ClientAuthRequest
import io.bosonnetwork.photon.core.network.model.MeDto
import io.bosonnetwork.photon.core.network.model.ProviderDto
import io.bosonnetwork.photon.core.network.model.SelfRegisterRequest
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
    data class ReuseProfile(
        val profileId: String,
        val userId: String,
        val seed: ProfileSeed? = null,
    ) : SessionState

    /**
     * The signed-in account's identity ([userId], null when the account has no bound identity) does not
     * match the active profile and has no profile of its own, yet the active profile is already bound to
     * a DIFFERENT identity - so it cannot host this one. The app must open a fresh profile (seeded with
     * the freshly obtained session) and continue onboarding there, never overwriting the active profile.
     */
    data class NewProfileForIdentity(
        val userId: String?,
        val seed: ProfileSeed? = null,
    ) : SessionState
}

/**
 * Identity material to seed into a target profile during a self-sovereign handoff (PoW create / key
 * import), so the target comes up ready after the single relaunch without re-authenticating. OAuth
 * handoffs carry none of this ([SessionState] seed stays null): their target creates or binds its own
 * key. [devicePrivateKey64]/[registeredNodeId] are set only when a device is already registered for the
 * identity (the PoW initial device); a fresh device that must register itself on the target leaves them
 * null. This is a one-shot event payload - do not rely on data-class equality of the byte arrays.
 */
data class ProfileSeed(
    val userId: String,
    val displayName: String?,
    val token: String,
    val userPrivateKey64: ByteArray,
    val devicePrivateKey64: ByteArray?,
    val registeredNodeId: String?,
)

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
     * True if this node accepts permissionless proof-of-work registration (policy `pow`/`either`/`open`),
     * i.e. the challenge endpoint answers. A 404 means the node is OAuth-only, so the "Create a new
     * account" option is hidden. Other failures propagate so a genuine connectivity problem surfaces.
     */
    suspend fun powAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            api().getRegistrationChallenge()
            true
        } catch (e: Exception) {
            if (e.toDirectorError() is AppError.NotFound) false else throw e
        }
    }

    /**
     * Permissionless account creation (no OAuth): fetches a proof-of-work challenge, solves the
     * memory-hard puzzle off the UI thread, signs it with a freshly generated user key and a device
     * key, and creates the user + initial device in one call (spec RegistrationPoW.md).
     *
     * The user id is derived from the freshly generated key and resolved against the profile registry
     * BEFORE anything is persisted (like [currentSession] does for `/me`):
     * - the active profile is a fresh scratch (or already this identity): the key + token + registered
     *   node are stored locally and [SessionState.Authenticated] is returned;
     * - the active profile is bound to a DIFFERENT identity: NOTHING is written to it; a
     *   [SessionState.NewProfileForIdentity] carrying a [ProfileSeed] (the new key, its already-registered
     *   initial device, the node, and the token) is returned so the app opens a fresh profile seeded
     *   ready. This prevents the new account from bleeding into the previous user's profile.
     *
     * The `(n, k)`/effort parameters are always read from the authenticated challenge, never hardcoded.
     * If the challenge expires between solve and submit (403), it re-fetches and re-solves once. Run on
     * [Dispatchers.Default]: the solve is CPU/memory-hard with high run-to-run variance by design.
     */
    suspend fun createAccountWithPow(name: String?, bio: String?, passphrase: String?): SessionState =
        withContext(Dispatchers.Default) {
            val directorApi = api()
            val nodeIdBase58 = config().nodeId?.takeIf { it.isNotBlank() } ?: directorApi.getNodeId().id
            val superNodeId = Id.of(nodeIdBase58).bytesUnsafe()

            val userKey = BosonCrypto.generateKeyPair()
            val userIdBase58 = BosonCrypto.idOf(userKey).toString()
            val target = resolveProfileTarget(userIdBase58)

            // Host here: reuse the active profile's device key (a fresh identity, so never one already
            // registered under a previous user). Otherwise generate the device key in MEMORY so the
            // foreign active profile's key material is never touched; it travels to the target via the seed.
            val deviceKey =
                if (target is ProfileTarget.HostHere) keyManager.ensureDeviceKeyFor(null)
                else BosonCrypto.generateKeyPair()
            val deviceIdBase58 = BosonCrypto.idOf(deviceKey).toString()

            val cleanName = name?.trim()?.ifBlank { null }
            val cleanBio = bio?.trim()?.ifBlank { null }
            val cleanPass = passphrase?.takeIf { it.isNotBlank() }

            var challenge = directorApi.getRegistrationChallenge()
            var reSolvedForExpiry = false
            var token: String? = null
            while (token == null) {
                val challengeNonce = B64URL_DEC.decode(challenge.nonce)
                val result = try {
                    RegistrationPowClient.solve(
                        superNodeId, userKey, challenge.n, challenge.k, challenge.effort,
                        challengeNonce, MAX_POW_NONCES,
                    )
                } catch (e: IllegalStateException) {
                    // No solution within the search budget: retryable (a fresh challenge/nonce may solve).
                    throw AppError.Timeout("Couldn't complete the security check in time; please try again", e)
                }
                // The device co-signs with its own key (bound to the device identity); the Director
                // verifies deviceSig against the device key, as it verifies userSig against the user key.
                val deviceSig = RegistrationPowClient.sign(
                    superNodeId, deviceKey, challengeNonce, result.powNonce, challenge.effort,
                )
                val request = SelfRegisterRequest(
                    userId = userIdBase58,
                    passphrase = cleanPass,
                    userName = cleanName,
                    bio = cleanBio,
                    deviceId = deviceIdBase58,
                    deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_NAME,
                    appName = APP_NAME,
                    userSig = B64URL.encodeToString(result.signature),
                    deviceSig = B64URL.encodeToString(deviceSig),
                    challenge = challenge.challenge,
                    challengeSig = challenge.challengeSig,
                    powNonce = B64URL.encodeToString(result.powNonce),
                    solution = result.solution.toList(),
                )
                try {
                    token = directorApi.register(request).token
                } catch (e: Exception) {
                    // Branch on the mapped domain error (the transport HttpException is not on this
                    // module's classpath). A 403 arrives as Forbidden: the first one is treated as an
                    // expired challenge and triggers a single re-solve; a second is terminal. A 400
                    // (malformed / invalid PoW) maps to Unknown.
                    when (val err = e.toDirectorError()) {
                        is AppError.Forbidden -> if (!reSolvedForExpiry) {
                            reSolvedForExpiry = true
                            challenge = directorApi.getRegistrationChallenge()
                        } else {
                            throw AppError.Forbidden("Registration was rejected; please try again", e)
                        }
                        is AppError.RateLimited -> throw err
                        // Our own user already exists: almost always a lost-response resubmit of this very
                        // solve. Recover by signing in with the user key; a genuine foreign id collision
                        // (astronomically rare) fails that sign-in and surfaces as a conflict.
                        is AppError.Conflict ->
                            token = runCatching { userSignInToken(userKey) }.getOrElse {
                                throw AppError.Conflict("That identity is already taken; please try again", e)
                            }
                        is AppError.Unknown ->
                            throw AppError.InvalidInput("Couldn't verify the security check; please try again", e)
                        else -> throw err
                    }
                }
            }

            // usersAndInitialDevice already registered this device, so the initial bring-up can skip
            // re-registration wherever this identity lands.
            val sessionToken = token!! // the loop only exits once a token was obtained
            val userPrivateKey64 = BosonCrypto.privateKeyBytes64(userKey)
            when (target) {
                is ProfileTarget.HostHere -> {
                    // The active profile hosts this identity: persist locally and proceed to Home.
                    keyManager.storeUserKey(userPrivateKey64)
                    tokenStore.setToken(sessionToken)
                    keyManager.setRegisteredNodeId(nodeIdBase58)
                    SessionState.Authenticated(MeDto(sessionId = userIdBase58, userId = userIdBase58), userIdBase58)
                }
                else -> {
                    // The active profile is a different identity: seed the new account into a fresh (or,
                    // defensively, existing) profile without touching the active one.
                    val seed = ProfileSeed(
                        userId = userIdBase58,
                        displayName = cleanName,
                        token = sessionToken,
                        userPrivateKey64 = userPrivateKey64,
                        devicePrivateKey64 = BosonCrypto.privateKeyBytes64(deviceKey),
                        registeredNodeId = nodeIdBase58,
                    )
                    when (target) {
                        is ProfileTarget.Existing ->
                            SessionState.ReuseProfile(target.profileId, userIdBase58, seed)
                        else -> SessionState.NewProfileForIdentity(userIdBase58, seed)
                    }
                }
            }
        }

    /**
     * Self-sovereign user sign-in: proves possession of [userKey] over a fresh nonce to mint a
     * CLIENT-scoped CWT with no OAuth and no pre-registered device (Director `clientAuth` user branch).
     * Used by the permissionless returning-device import and by PoW resubmit recovery.
     */
    private suspend fun userSignInToken(userKey: Signature.KeyPair): String {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        return api().clientAuth(
            ClientAuthRequest(
                userId = BosonCrypto.idOf(userKey).toString(),
                nonce = B64URL.encodeToString(nonce),
                userSig = B64URL.encodeToString(BosonCrypto.sign(userKey, nonce)),
            ),
        ).token
    }

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

    /** Where a self-sovereign identity (derived from a key we hold) belongs relative to the active profile. */
    private sealed interface ProfileTarget {
        /** The active profile is a fresh scratch or already this identity: host it here. */
        object HostHere : ProfileTarget
        /** This identity already has its OWN profile ([profileId]): hand off to it. */
        data class Existing(val profileId: String) : ProfileTarget
        /** The active profile is a DIFFERENT bound identity: a fresh profile is needed. */
        object New : ProfileTarget
    }

    /**
     * Resolves which profile a self-sovereign identity ([userId], derived from a public key rather than
     * from `/me`) belongs to, WITHOUT writing anything to the active profile. Mirrors [currentSession]'s
     * routing so PoW registration and key import land in the right profile instead of overwriting the
     * active (possibly foreign) one.
     */
    private fun resolveProfileTarget(userId: String): ProfileTarget {
        val localId = keyManager.userId()?.toString()
        val activeId = profileManager.activeProfileId()
        // Fresh scratch (no key) or already this identity: proceed on the active profile.
        if (localId == null || localId == userId) return ProfileTarget.HostHere
        // This identity already lives in its own profile: open that one, never rehost.
        val existing = profileManager.findByUserId(userId)
        if (existing != null && existing.id != activeId) return ProfileTarget.Existing(existing.id)
        // Active profile is a different identity with no profile for this one: a fresh profile is needed.
        return ProfileTarget.New
    }

    /** True once this device is fully usable: a token AND a local user key both exist. */
    fun isReady(): Boolean = tokenStore.currentToken() != null && keyManager.hasUserKey()

    /** True if a user key is present on this device. */
    fun hasUserKey(): Boolean = keyManager.hasUserKey()

    /**
     * Imports a user key pasted or scanned as raw text (base58 or hex, O4). With no OAuth session this is
     * a self-sovereign returning device: the identity is resolved against the profile registry (like a
     * PoW create) and may hand off to another profile. With an OAuth session, if no identity is bound to
     * the account the imported key is bound; if one is, the imported key must derive to the same user id.
     * The 64-byte key is stored only after it is accepted, and never into a foreign active profile.
     */
    suspend fun importUserKeyText(text: String): SessionState {
        val privateKey64 = BosonCrypto.decodePrivateKey64(text) // throws IllegalArgumentException on bad input
        return importUserKey(privateKey64)
    }

    private suspend fun importUserKey(privateKey64: ByteArray): SessionState =
        withContext(Dispatchers.IO) {
            val kp = BosonCrypto.keyPairFromPrivate64(privateKey64)
            val derivedId = BosonCrypto.idOf(kp).toString()

            // Self-sovereign returning device (no OAuth session yet): resolve where this identity belongs
            // BEFORE writing anything, so importing it never overwrites a different active profile. Mint a
            // CLIENT-scoped CWT by proving possession of the imported key (no getMe/bind: that token has no
            // OAuth auth-identity, so /auth/me would not resolve it - the session id stands in for MeDto).
            if (tokenStore.currentToken() == null) {
                val target = resolveProfileTarget(derivedId)
                val token = userSignInToken(kp)
                return@withContext when (target) {
                    is ProfileTarget.HostHere -> {
                        // The active profile hosts this identity: rotate a stale device key if needed,
                        // persist the key + session, and let onboarding register this device.
                        adoptIdentity(derivedId)
                        keyManager.storeUserKey(privateKey64)
                        tokenStore.setToken(token)
                        SessionState.Authenticated(MeDto(sessionId = derivedId, userId = derivedId), derivedId)
                    }
                    else -> {
                        // The active profile is a different identity: seed the imported key into a fresh
                        // (or its own existing) profile without touching the active one. No device key or
                        // registered node is seeded - the target registers its own device on first bring-up.
                        val seed = ProfileSeed(
                            userId = derivedId,
                            displayName = null,
                            token = token,
                            userPrivateKey64 = privateKey64,
                            devicePrivateKey64 = null,
                            registeredNodeId = null,
                        )
                        when (target) {
                            is ProfileTarget.Existing ->
                                SessionState.ReuseProfile(target.profileId, derivedId, seed)
                            else -> SessionState.NewProfileForIdentity(derivedId, seed)
                        }
                    }
                }
            }

            // OAuth session present: currentSession already resolved the profile, so the active one is a
            // valid host. Rotate a device key registered under a different identity before adopting this.
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
        // Generous proof-of-work search budget (matches the Director E2E reference); solve throws if a
        // solution is not found within it, which the UI surfaces as a retryable error.
        const val MAX_POW_NONCES = 1_000_000L
        val B64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val B64URL_DEC: Base64.Decoder = Base64.getUrlDecoder()
    }
}
