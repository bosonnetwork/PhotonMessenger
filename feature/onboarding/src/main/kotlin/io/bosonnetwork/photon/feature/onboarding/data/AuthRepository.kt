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

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.AuthProvider
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.ProfileUpdate
import io.bosonnetwork.director.client.UserRegistration
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.DirectorClients
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.boson.toDirectorError
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.SessionStore
import io.bosonnetwork.photon.core.network.DirectorConfig
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.security.ProfileManager
import io.bosonnetwork.photon.feature.onboarding.R
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/** Outcome of consuming an OAuth callback token (spec 2.1). */
sealed interface SessionState {
    /** Authenticated but no Boson identity bound yet -> offer create/import (spec 2.2). */
    data object NeedsIdentity : SessionState

    /**
     * A Boson identity is already bound to this account, but this device has no local user key
     * (returning user on a fresh device/reinstall). The key must be imported from another device.
     */
    data class NeedsKey(val userId: String) : SessionState

    /** Fully signed in with a bound Boson user id and the matching key present on this device. */
    data class Authenticated(val userId: String) : SessionState

    /**
     * The signed-in account resolves to a Boson identity ([userId]) that ALREADY has its own on-device
     * profile ([profileId]), which is not the active one. The app must open that profile - never rehost
     * the identity here. The app seeds the target profile with the identity so it comes up signed in
     * without a second sign-in.
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
     * the identity) and continue onboarding there, never overwriting the active profile.
     */
    data class NewProfileForIdentity(
        val userId: String?,
        val seed: ProfileSeed? = null,
    ) : SessionState
}

/**
 * Identity material to seed into a target profile during a self-sovereign handoff (PoW create / key
 * import), so the target comes up signed in after the single relaunch without re-authenticating. OAuth
 * handoffs carry none of this ([SessionState] seed stays null): their target creates or binds its own
 * key. [devicePrivateKey64]/[registeredNodeId] are set only when a device is already registered for the
 * identity (the PoW initial device); a fresh device that must register itself on the target leaves them
 * null. This is a one-shot event payload - do not rely on data-class equality of the byte arrays.
 */
data class ProfileSeed(
    val userId: String,
    val displayName: String?,
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
 * M1-7..M1-13), through the Director client ([DirectorClients]).
 *
 * The Director client issues its own tokens from the key it acts with, so a Boson identity needs no
 * session token: once this device holds the user key it can act as the user. The OAuth session token is
 * onboarding-time only - it authorizes the identity bind - and is held IN MEMORY ONLY, never written to
 * disk. What is persisted ([SessionStore]) is just that this profile is signed in, and as whom.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val directorClients: DirectorClients,
    private val configStore: DirectorConfigStore,
    private val sessionStore: SessionStore,
    private val keyManager: KeyManager,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context,
) {
    /** The OAuth session token of an onboarding in progress; memory only. */
    @Volatile
    private var oauthToken: String? = null

    private suspend fun config(): DirectorConfig = configStore.config.first()

    /**
     * Current Director base URL for prefilling the pre-login server step (O1), or "" when none has been
     * configured yet so the field starts empty (the built-in placeholder default is not surfaced as text
     * the user would have to clear).
     */
    suspend fun currentDirectorUrl(): String = config().baseUrl.let { url ->
        if (url.trimEnd('/') == DirectorConfigStore.DEFAULT_DIRECTOR_URL.trimEnd('/')) "" else url
    }

    /** Current Director node id used to identity-pin its cert, or "" when none is set (prefill, O1). */
    suspend fun currentDirectorNodeId(): String = config().nodeId.orEmpty()

    /**
     * Persists the Director base URL and its identity-pin node id (pre-login server step, O1); the
     * Director clients follow the new config. A self-signed HTTPS Director requires [nodeId] to be
     * trusted; pass null or blank to clear it (a Director fronted by a real CA certificate needs none).
     */
    suspend fun setDirectorUrl(url: String, nodeId: String?) {
        val cleanId = nodeId?.trim()?.ifBlank { null }
        if (cleanId != null) {
            // Fail fast with a field-specific message: an unparseable id would otherwise only surface
            // later as an opaque failure when the Director client is built.
            try {
                Id.of(cleanId)
            } catch (e: Exception) {
                throw AppError.InvalidInput(context.getString(R.string.onb_error_invalid_server_id), e)
            }
        }
        configStore.setBaseUrl(url)
        configStore.setNodeId(cleanId)
    }

    /** Device key exists before any sign-in (used for device login + IonStore CWT later). */
    fun ensureDeviceKey() {
        keyManager.ensureDeviceKey()
    }

    suspend fun providers(): List<AuthProvider> = directorClients.auth().providers.await()

    suspend fun authorizeUrl(provider: String): String =
        directorClients.auth().authorizeUrl(provider, REDIRECT_URI)

    /**
     * True if this node accepts permissionless proof-of-work registration (policy `pow`/`either`), i.e.
     * the challenge endpoint answers. A 404 means the node is OAuth-only, so the "Create a new account"
     * option is hidden. Other failures propagate so a genuine connectivity problem surfaces.
     */
    suspend fun powAvailable(): Boolean = withContext(Dispatchers.IO) {
        directorClients.auth().isProofOfWorkRegistrationEnabled().await()
    }

    /**
     * Permissionless account creation (no OAuth): generates a user key and a device key, and has the
     * Director client fetch a proof-of-work challenge, solve the memory-hard puzzle (on a worker thread)
     * and create the user + initial device in one call (spec RegistrationPoW.md).
     *
     * The user id is derived from the freshly generated key and resolved against the profile registry
     * BEFORE anything is persisted (like [currentSession] does for the OAuth session):
     * - the active profile is a fresh scratch (or already this identity): the key + session + registered
     *   node are stored locally and [SessionState.Authenticated] is returned;
     * - the active profile is bound to a DIFFERENT identity: NOTHING is written to it; a
     *   [SessionState.NewProfileForIdentity] carrying a [ProfileSeed] (the new key, its already-registered
     *   initial device, and the node) is returned so the app opens a fresh profile seeded ready. This
     *   prevents the new account from bleeding into the previous user's profile.
     *
     * The `(n, k)`/effort parameters are always read from the authenticated challenge, never hardcoded.
     * If the challenge expires between solve and submit (403), it registers again - with a fresh
     * challenge - once.
     */
    suspend fun createAccountWithPow(name: String?, bio: String?, passphrase: String?): SessionState =
        withContext(Dispatchers.Default) {
            val userKey = BosonCrypto.generateKeyPair()
            val userIdBase58 = BosonCrypto.idOf(userKey).toString()
            val target = resolveProfileTarget(userIdBase58)

            // Host here: reuse the active profile's device key (a fresh identity, so never one already
            // registered under a previous user). Otherwise generate the device key in MEMORY so the
            // foreign active profile's key material is never touched; it travels to the target via the seed.
            val deviceKey =
                if (target is ProfileTarget.HostHere) keyManager.ensureDeviceKeyFor(null)
                else BosonCrypto.generateKeyPair()

            val cleanName = name?.trim()?.ifBlank { null }
            val registration = UserRegistration()
                .name(cleanName)
                .bio(bio?.trim()?.ifBlank { null })
                .passphrase(passphrase?.takeIf { it.isNotBlank() })
                .initialDevice(Build.MODEL?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_NAME, APP_NAME)

            val nodeIdBase58 = directorClients.withClient(userKey, deviceKey) { client ->
                val nodeId = config().nodeId?.takeIf { it.isNotBlank() } ?: client.nodeId.await().toString()
                register(client, registration)
                nodeId
            }

            // usersAndInitialDevice already registered this device, so the initial bring-up can skip
            // re-registration wherever this identity lands.
            val userPrivateKey64 = BosonCrypto.privateKeyBytes64(userKey)
            when (target) {
                is ProfileTarget.HostHere -> {
                    // The active profile hosts this identity: persist locally and proceed to Home.
                    keyManager.storeUserKey(userPrivateKey64)
                    sessionStore.setSession(userIdBase58)
                    keyManager.setRegisteredNodeId(nodeIdBase58)
                    SessionState.Authenticated(userIdBase58)
                }
                else -> {
                    // The active profile is a different identity: seed the new account into a fresh (or,
                    // defensively, existing) profile without touching the active one.
                    val seed = ProfileSeed(
                        userId = userIdBase58,
                        displayName = cleanName,
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

    /** Registers the user of [client] with proof-of-work, recovering what can be recovered. */
    private suspend fun register(client: DirectorClient, registration: UserRegistration) {
        var reSolvedForExpiry = false
        while (true) {
            try {
                client.registerUser(registration).await()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // No solution within the search budget: retryable (a fresh challenge/nonce may solve).
                throw AppError.Timeout(context.getString(R.string.onb_error_pow_timeout), e)
            } catch (e: Exception) {
                // A 403 arrives as Forbidden: the first one is treated as an expired challenge and triggers
                // a single re-registration, which solves a fresh challenge; a second is terminal. A 400
                // (malformed / invalid PoW) maps to Unknown.
                when (val err = e.toDirectorError()) {
                    is AppError.Forbidden -> if (!reSolvedForExpiry) {
                        reSolvedForExpiry = true
                    } else {
                        throw AppError.Forbidden(context.getString(R.string.onb_error_registration_rejected), e)
                    }
                    is AppError.RateLimited -> throw err
                    // Our own user already exists: almost always a lost-response resubmit of this very
                    // solve. Recover by acting as the user with its key; a genuine foreign id collision
                    // (astronomically rare) fails that and surfaces as a conflict.
                    is AppError.Conflict -> {
                        runCatching { client.profile.await() }.getOrElse {
                            throw AppError.Conflict(context.getString(R.string.onb_error_identity_taken), e)
                        }
                        return
                    }
                    is AppError.Unknown ->
                        throw AppError.InvalidInput(context.getString(R.string.onb_error_pow_verify_failed), e)
                    else -> throw err
                }
            }
        }
    }

    /**
     * Self-sovereign sign-in: acts as the user of [userKey] (no OAuth, no pre-registered device), which
     * proves the Director knows the user and accepts the key. Used by the permissionless returning-device
     * import.
     */
    private suspend fun verifySignIn(userKey: Signature.KeyPair) {
        directorClients.withClient(userKey) { it.profile.await() }
    }

    /**
     * Signs in as the identity of a stored key so a profile can be brought up signed in using ONLY the
     * Boson identity - no OAuth. Returns the user id to record as the session. Exposed for the app-layer
     * profile switch and the reuse-profile handoff.
     */
    suspend fun signInWith(privateKey64: ByteArray): String {
        val userKey = BosonCrypto.keyPairFromPrivate64(privateKey64)
        verifySignIn(userKey)
        return BosonCrypto.idOf(userKey).toString()
    }

    /**
     * Consumes the `?token=` from the OAuth deep link. The OAuth token is held IN MEMORY ONLY (never
     * written to disk) - it is onboarding-time KYC that authorizes the identity bind. Then resolves the
     * session (identity bound? key present on this device?).
     */
    suspend fun onAuthToken(token: String): SessionState {
        oauthToken = token
        return currentSession()
    }

    /**
     * Resolves which profile the OAuth-signed-in account belongs to, driven ONLY by the Boson user id
     * the OAuth session is bound to (never stale local state):
     * - [SessionState.Authenticated] when the account's identity is the active profile's identity
     *   (proceed here);
     * - [SessionState.ReuseProfile] when it already has its OWN profile elsewhere (the app opens that one
     *   directly, signing in with that profile's own key - no key/import needed);
     * - [SessionState.NeedsIdentity]/[SessionState.NeedsKey] otherwise: create/import the identity in the
     *   CURRENT context. The create ([bindIdentity]) / import ([importUserKeyText]) step then resolves the
     *   profile at completion and commits (or seeds a fresh profile), so a foreign active profile is never
     *   overwritten. This is the same resolve-and-commit-at-end pattern the self-sovereign paths use.
     *
     * @throws AppError.Unauthorized when there is no OAuth session to resolve
     */
    suspend fun currentSession(): SessionState {
        val token = oauthToken ?: throw AppError.Unauthorized("Session expired; please sign in again")
        val session = directorClients.auth().getSession(token).await()
        val accountId = session.userId.orElse(null)?.toString() // identity bound to this OAuth account
        val localId = keyManager.userId()?.toString()             // identity of the active profile's key
        val activeId = profileManager.activeProfileId()

        // Same identity as the active profile: record the session (dropping the in-memory OAuth token)
        // so the re-login survives a restart, then proceed here.
        if (accountId != null && accountId == localId) {
            sessionStore.setSession(accountId)
            oauthToken = null
            return SessionState.Authenticated(accountId)
        }

        // The account's identity already has its own profile: open it directly (it holds its own key).
        if (accountId != null) {
            val existing = profileManager.findByUserId(accountId)
            if (existing != null && existing.id != activeId) {
                return SessionState.ReuseProfile(existing.id, accountId)
            }
        }

        // Create (unbound account) or import (bound account whose key is not on this device) in the current
        // context; completion commits to the right profile without touching a foreign active one.
        return if (accountId == null) SessionState.NeedsIdentity else SessionState.NeedsKey(accountId)
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
     * from the OAuth session) belongs to, WITHOUT writing anything to the active profile. Mirrors
     * [currentSession]'s routing so PoW registration and key import land in the right profile instead of
     * overwriting the active (possibly foreign) one.
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

    /** True once this device is fully usable: signed in AND a local user key exists. */
    fun isReady(): Boolean = sessionStore.currentSession() != null && keyManager.hasUserKey()

    /** True if a user key is present on this device. */
    fun hasUserKey(): Boolean = keyManager.hasUserKey()

    /**
     * The base58 identity private key just generated in [state], for the post-create backup prompt: read
     * from the local key store when the identity is hosted on the active profile, or from the seed when it
     * is about to be handed to another profile. Null when [state] carries no freshly created key (an
     * imported or returning identity), so the backup screen is offered only for a brand-new key.
     */
    fun newKeyBackupBase58(state: SessionState): String? = when (state) {
        is SessionState.Authenticated ->
            keyManager.userKeyPair()?.let { BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(it)) }
        is SessionState.NewProfileForIdentity ->
            state.seed?.let { BosonCrypto.privateKey64ToBase58(it.userPrivateKey64) }
        is SessionState.ReuseProfile ->
            state.seed?.let { BosonCrypto.privateKey64ToBase58(it.userPrivateKey64) }
        else -> null
    }

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

            // Self-sovereign returning device (no OAuth session): act as the user with the imported key,
            // which proves the Director knows it, then commit-at-end.
            val token = oauthToken
            if (token == null) {
                verifySignIn(kp)
                return@withContext commitIdentity(privateKey64, derivedId, displayName = null)
            }

            // OAuth session present. Establish/confirm the account's identity, then commit-at-end (never
            // overwrite a foreign active profile).
            val auth = directorClients.auth()
            val boundId = auth.getSession(token).await().userId.orElse(null)?.toString()
            if (boundId == null) {
                // No identity bound to this account yet: bind the imported key as the account identity.
                auth.bindUserIdentity(token, kp).await()
            } else if (derivedId != boundId) {
                // An identity is already bound: the imported key must match it, or we would fork identity.
                throw AppError.InvalidInput(context.getString(R.string.onb_error_key_mismatch))
            }
            oauthToken = null // the identity authenticates by its own key from here on
            commitIdentity(privateKey64, derivedId, displayName = null)
        }

    /**
     * Commits an identity ([privateKey64]/[userId]) the Director has accepted (via bind or key import - no
     * device registered yet): stored on the active profile, and recorded as its session, when it can host
     * the identity, else returned as a [ProfileSeed] for a handoff with NOTHING written to the (foreign)
     * active profile. The device is registered on the target's first bring-up (no device key / node is
     * seeded). [displayName] names the profile when known (the entered name on create; null on import,
     * where the name is fetched from the Director profile on the target's first bring-up).
     */
    private suspend fun commitIdentity(
        privateKey64: ByteArray,
        userId: String,
        displayName: String?,
    ): SessionState =
        when (val target = resolveProfileTarget(userId)) {
            is ProfileTarget.HostHere -> {
                adoptIdentity(userId) // rotate a device key registered under a different identity
                keyManager.storeUserKey(privateKey64)
                sessionStore.setSession(userId)
                SessionState.Authenticated(userId)
            }
            else -> {
                val seed = ProfileSeed(
                    userId = userId,
                    displayName = displayName,
                    userPrivateKey64 = privateKey64,
                    devicePrivateKey64 = null,
                    registeredNodeId = null,
                )
                when (target) {
                    is ProfileTarget.Existing -> SessionState.ReuseProfile(target.profileId, userId, seed)
                    else -> SessionState.NewProfileForIdentity(userId, seed)
                }
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
     * Completes OAuth registration: generates a fresh user keypair IN MEMORY, binds its public key as
     * this session's Boson identity (authorized by the OAuth session), and writes the chosen name/bio
     * acting as the new user. The OAuth token is then dropped - it is never persisted past onboarding.
     * The identity is then resolved against the profile registry and committed at the end (like the
     * self-sovereign paths):
     * - [SessionState.Authenticated] with the key stored locally when the active profile can host it;
     * - a [SessionState.ReuseProfile]/[SessionState.NewProfileForIdentity] carrying a [ProfileSeed]
     *   otherwise, with NOTHING written to the (foreign) active profile.
     * The device is registered afterwards by the existing finish/connect path (this bind does not
     * register a device), so the seed carries no device key / node.
     */
    suspend fun bindIdentity(name: String? = null, bio: String? = null): SessionState =
        withContext(Dispatchers.IO) {
            val token = oauthToken ?: throw AppError.Unauthorized("Session expired; please sign in again")
            val cleanName = name?.trim()?.ifBlank { null }
            val cleanBio = bio?.trim()?.ifBlank { null }

            // Generate the identity key in memory: it is persisted only if the active profile hosts it,
            // so binding on a foreign active profile never overwrites its key.
            val userKey = BosonCrypto.generateKeyPair()
            val userId = BosonCrypto.idOf(userKey).toString()
            directorClients.auth().bindUserIdentity(token, userKey).await()
            oauthToken = null // the identity now exists and authenticates by its own key

            if (cleanName != null || cleanBio != null) {
                val update = ProfileUpdate()
                cleanName?.let { update.name(it) }
                cleanBio?.let { update.bio(it) }
                directorClients.withClient(userKey) { it.updateProfile(update).await() }
            }
            commitIdentity(BosonCrypto.privateKeyBytes64(userKey), userId, cleanName)
        }

    /**
     * True if the signed-in account requires a passphrase for sensitive actions (adding a device is
     * passphrase-gated on the Director). Onboarding checks this after acquiring an identity to decide
     * whether the user must be prompted for the passphrase before this device can be registered.
     */
    suspend fun isPassphraseProtected(): Boolean =
        withContext(Dispatchers.IO) { directorClients.client().profile.await().isPassphraseProtected }

    /**
     * The account's display name from the Director profile, used to name the on-device profile in the
     * account switcher. Null when unset or unreachable; the switcher then falls back to the short id.
     */
    suspend fun currentProfileName(): String? =
        withContext(Dispatchers.IO) {
            directorClients.client().profile.await().name.orElse(null)?.trim()?.ifBlank { null }
        }

    /**
     * Registers THIS device under the signed-in user so the messaging service will authorize its mqtts
     * session. The Director's `authenticateDevice` rejects any device absent from the user's device table,
     * and neither the OAuth bind nor a raw-key import registers a device (only the pairing flow and
     * self-registration do) - so without this the messaging client connects but never reaches READY
     * ("always connecting"). Idempotent: a 409 (already registered) is treated as success. Must be called
     * with a local user key present.
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
                ?: throw AppError.InvalidInput(context.getString(R.string.onb_error_no_user_identity))
            // Defense in depth: a device key registered under a different identity is rotated here
            // rather than re-registered across users.
            val deviceKey = keyManager.ensureDeviceKeyFor(userId)
            val client = directorClients.client()
            try {
                client.registerDevice(
                    deviceKey,
                    Build.MODEL?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_NAME,
                    APP_NAME,
                    passphrase?.takeIf { it.isNotBlank() },
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 409 (mapped to Conflict) = this device is already registered; anything else is a real
                // failure.
                if (e.toDirectorError() !is AppError.Conflict) throw e
            }
            // Persist the super node this device is now registered with, so subsequent bring-ups skip
            // re-registering until the node or identity changes. Its presence also marks the current
            // device key as registered. Reuse the caller's probed id when available, else fetch it.
            keyManager.setRegisteredNodeId(superNodeId ?: client.nodeId.await().toString())
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
        keyManager.userId() ?: return // no identity yet; nothing to register
        val nodeId = probeNodeId() ?: return
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
        val nodeId = probeNodeId() ?: return null
        return if (current != nodeId) NodeMigration(fromNodeId = current, toNodeId = nodeId) else null
    }

    // The node id the configured Director reports now, or null when it cannot be asked.
    private suspend fun probeNodeId(): String? =
        runCatching { directorClients.client().nodeId.await().toString() }.getOrNull()

    /** True if signed in, or signing in through OAuth (gates onboarding vs. home, and bind vs. create). */
    fun isSignedIn(): Boolean = oauthToken != null || sessionStore.currentSession() != null

    suspend fun signOut() {
        oauthToken = null
        sessionStore.clear()
        // Sign-out ends the session only; keys, the registered-node marker, and local data are retained.
        // Deleting an identity is an explicit delete-profile action, not a side effect of signing out.
    }

    companion object {
        /**
         * Where the Director sends the OAuth sign-in back to: the `?token=...` (or `?error=...`) deep link
         * the app captures (spec 2.1, M1-7/M1-8). Client-side only - the Director accepts any redirect URI.
         */
        const val REDIRECT_URI = "io.bosonnetwork.photon://auth"

        private const val APP_NAME = "Photon"
        private const val DEFAULT_DEVICE_NAME = "Android device"
    }
}
