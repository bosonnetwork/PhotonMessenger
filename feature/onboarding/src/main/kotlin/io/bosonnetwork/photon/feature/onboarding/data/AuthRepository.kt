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
import io.bosonnetwork.crypto.KeyBackup
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DeviceRegistration
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.UserRegistration
import io.bosonnetwork.director.client.exceptions.NotFoundException
import io.bosonnetwork.director.client.exceptions.ProofOfWorkException
import io.bosonnetwork.director.client.exceptions.RegistrationDeniedException
import io.bosonnetwork.director.client.exceptions.RegistrationExpiredException
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

/** Where an identity brought onto this device belongs. */
sealed interface SessionState {
    /** Hosted on the active profile, with its key on this device. */
    data class Authenticated(val userId: String) : SessionState

    /**
     * The identity ([userId]) ALREADY has its own on-device profile ([profileId]), which is not the active
     * one. The app opens that profile - never rehosts the identity here - seeded with the identity so it
     * comes up signed in.
     */
    data class ReuseProfile(
        val profileId: String,
        val userId: String,
        val seed: ProfileSeed? = null,
    ) : SessionState

    /**
     * The identity ([userId]) has no profile of its own, and the active profile is already bound to a
     * DIFFERENT identity, so it cannot host this one. The app opens a fresh profile seeded with the
     * identity, never overwriting the active profile.
     */
    data class NewProfileForIdentity(
        val userId: String,
        val seed: ProfileSeed? = null,
    ) : SessionState
}

/**
 * Identity material to seed into a target profile during a handoff, so the target comes up signed in
 * after the single relaunch. [devicePrivateKey64]/[registeredNodeId] are set only when a device is already
 * registered for the identity (a new account's initial device, or a paired device); a device that must
 * register itself on the target leaves them null. [backupPending] carries the reminder of a new
 * identity whose backup wasn't checked yet. This is a one-shot event payload - do not rely on data-class
 * equality of the byte arrays.
 */
data class ProfileSeed(
    val userId: String,
    val displayName: String?,
    val userPrivateKey64: ByteArray,
    val devicePrivateKey64: ByteArray?,
    val registeredNodeId: String?,
    val backupPending: Boolean = false,
)

/**
 * A pending home super node migration: this device is registered with [fromNodeId] but the configured
 * Director now reports [toNodeId]. Confirming re-registers the device with the new node; the local
 * profile data is untouched (identity is independent of the home node in the federated network).
 */
data class NodeMigration(val fromNodeId: String, val toNodeId: String)

/**
 * Brings a Boson identity onto this device, identity first: a new one (made here, backed up, then
 * registered on a node by proof of work), or an existing one (paired from another device that holds it,
 * or imported from its backup), through the Director client ([DirectorClients]).
 *
 * Photon acts as the user itself (messaging encrypts and signs with the user key), so every way in ends
 * with the user key on this device. Nothing is written to the active profile until the identity is known
 * and accepted; an identity that belongs to another profile is handed off to it instead ([SessionState]).
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
    private suspend fun config(): DirectorConfig = configStore.config.first()

    /**
     * Current Director base URL for prefilling the node step, or "" when none has been configured yet so
     * the field starts empty (the built-in placeholder default is not surfaced as text the user would have
     * to clear).
     */
    suspend fun currentDirectorUrl(): String = config().baseUrl.let { url ->
        if (url.trimEnd('/') == DirectorConfigStore.DEFAULT_DIRECTOR_URL.trimEnd('/')) "" else url
    }

    /** Current Director node id used to identity-pin its cert, or "" when none is set (prefill). */
    suspend fun currentDirectorNodeId(): String = config().nodeId.orEmpty()

    /**
     * Persists the Director base URL and its identity-pin node id; the Director clients follow the new
     * config. A self-signed HTTPS Director requires [nodeId] to be trusted; pass null or blank to clear it
     * (a Director fronted by a real CA certificate needs none).
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

    /** Asks the configured Director for its node id: that it is there, and who it is. */
    suspend fun reachNode(): String = withContext(Dispatchers.IO) { directorClients.probeNodeId().toString() }

    /** The registration options of a Director, remembered per config: they change only with it. */
    @Volatile
    private var registrationOptions: Pair<DirectorConfig, Boolean>? = null

    /**
     * True if this node accepts permissionless proof-of-work registration (policy `pow`/`either`); a node
     * that doesn't can't take a new identity from Photon. Asked once per Director config, and without side
     * effects on the node. A Director too old to report its options is taken to accept it: registering
     * there then fails plainly if it does not. Other failures propagate so a genuine connectivity problem
     * surfaces.
     */
    suspend fun powAvailable(): Boolean = withContext(Dispatchers.IO) {
        val cfg = config()
        registrationOptions?.let { (cached, pow) -> if (cached == cfg) return@withContext pow }
        val pow = try {
            directorClients.guest().registrationOptions.await().isProofOfWorkEnabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.toDirectorError() !is AppError.NotFound) throw e
            true
        }
        registrationOptions = cfg to pow
        pow
    }

    // ---- A new identity ----------------------------------------------------------------------------

    /**
     * The key of a new identity, made by [newIdentity] and held IN MEMORY ONLY until it is registered
     * ([createAccountWithPow]) or dropped: an identity is written to a profile only once a node accepted it.
     */
    @Volatile
    private var pendingKey: Signature.KeyPair? = null

    /** Makes a new identity's key, in memory, replacing any earlier one not yet registered; returns its id. */
    fun newIdentity(): String {
        val key = BosonCrypto.generateKeyPair()
        pendingKey = key
        return BosonCrypto.idOf(key).toString()
    }

    /** The new identity's id, or null when none is pending. */
    fun pendingIdentityId(): String? = pendingKey?.let { BosonCrypto.idOf(it).toString() }

    /** The new identity's private key as Base58, for its backup, or null when none is pending. */
    fun pendingIdentityKeyBase58(): String? =
        pendingKey?.let { KeyBackup.encode(BosonCrypto.privateKeyBytes64(it)) }

    /** Forgets the new identity, never registered. */
    fun dropPendingIdentity() {
        pendingKey = null
    }

    /**
     * Registers the new identity ([newIdentity]) on the configured node by proof of work: the Director
     * client fetches a challenge, solves the memory-hard puzzle (on a worker thread) and creates the user
     * and this initial device in one call (spec RegistrationPoW.md). [backupChecked] says whether the user
     * checked the backup; if not, Settings keeps reminding them.
     *
     * The user id is resolved against the profile registry BEFORE anything is persisted:
     * - the active profile is a fresh scratch (or already this identity): the key, session and registered
     *   node are stored locally and [SessionState.Authenticated] is returned;
     * - the active profile is bound to a DIFFERENT identity: NOTHING is written to it; a
     *   [SessionState.NewProfileForIdentity] carrying a [ProfileSeed] (the new key, its already-registered
     *   initial device, and the node) is returned so the app opens a fresh profile seeded ready.
     *
     * The `(n, k)`/effort parameters are always read from the authenticated challenge, never hardcoded.
     * If the challenge expires between solve and submit (403), it registers again - with a fresh
     * challenge - once.
     */
    suspend fun createAccountWithPow(name: String?, bio: String?, backupChecked: Boolean): SessionState =
        withContext(Dispatchers.Default) {
            val userKey = pendingKey ?: throw AppError.InvalidInput(context.getString(R.string.onb_error_no_new_identity))
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
                .initialDevice(Build.MODEL?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_NAME, APP_NAME)

            val nodeIdBase58 = directorClients.withClient(userKey, deviceKey) { client ->
                val nodeId = config().nodeId?.takeIf { it.isNotBlank() } ?: client.nodeId.await().toString()
                register(client, registration)
                nodeId
            }
            pendingKey = null

            // usersAndInitialDevice already registered this device, so the initial bring-up can skip
            // re-registration wherever this identity lands.
            val userPrivateKey64 = BosonCrypto.privateKeyBytes64(userKey)
            when (target) {
                is ProfileTarget.HostHere -> {
                    keyManager.storeUserKey(userPrivateKey64)
                    keyManager.setBackupPending(!backupChecked)
                    sessionStore.setSession(userIdBase58)
                    keyManager.setRegisteredNodeId(nodeIdBase58)
                    SessionState.Authenticated(userIdBase58)
                }
                else -> {
                    val seed = ProfileSeed(
                        userId = userIdBase58,
                        displayName = cleanName,
                        userPrivateKey64 = userPrivateKey64,
                        devicePrivateKey64 = BosonCrypto.privateKeyBytes64(deviceKey),
                        registeredNodeId = nodeIdBase58,
                        backupPending = !backupChecked,
                    )
                    when (target) {
                        is ProfileTarget.Existing -> SessionState.ReuseProfile(target.profileId, userIdBase58, seed)
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
            } catch (e: ProofOfWorkException) {
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
     * Acts as the user of [userKey] (no pre-registered device), which proves the Director knows the user
     * and accepts the key. An identity this node doesn't know is told apart: it was probably registered on
     * another node.
     */
    private suspend fun verifySignIn(userKey: Signature.KeyPair) {
        try {
            directorClients.withClient(userKey) { it.profile.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (e.toDirectorError()) {
                is AppError.Unauthorized, is AppError.NotFound ->
                    throw AppError.InvalidInput(context.getString(R.string.onb_error_not_registered_here), e)
                else -> throw e
            }
        }
    }

    /**
     * Signs in as the identity of a stored key so a profile can be brought up signed in using ONLY the
     * Boson identity. Returns the user id to record as the session. Exposed for the app-layer profile
     * switch and the reuse-profile handoff.
     */
    suspend fun signInWith(privateKey64: ByteArray): String {
        val userKey = BosonCrypto.keyPairFromPrivate64(privateKey64)
        verifySignIn(userKey)
        return BosonCrypto.idOf(userKey).toString()
    }

    // ---- An existing identity: its backup ------------------------------------------------------------

    /**
     * Brings in an identity from its backup, as the user opened, scanned, pasted or typed it (the key
     * file, the key, or its numbered rows; see [KeyBackup.parse]). The configured node must know the
     * identity. The key is stored only after it is accepted, and never into a foreign active profile; the
     * device registers itself afterwards ([registerDevice]).
     */
    suspend fun importBackup(text: String): SessionState = withContext(Dispatchers.IO) {
        val privateKey64 = try {
            KeyBackup.parse(text)
        } catch (e: IllegalArgumentException) {
            throw AppError.InvalidInput(context.getString(R.string.onb_error_invalid_key), e)
        }
        val keyPair = BosonCrypto.keyPairFromPrivate64(privateKey64)
        verifySignIn(keyPair)
        commitIdentity(privateKey64, BosonCrypto.idOf(keyPair).toString(), displayName = null)
    }

    // ---- An existing identity: pairing -----------------------------------------------------------------

    /** The pairing in progress: its registration, and whether its device key is the active profile's. */
    private class Pairing(val registration: DeviceRegistration, val deviceKey: Signature.KeyPair, val profileKey: Boolean)

    @Volatile
    private var pairing: Pairing? = null

    /**
     * Asks the configured node to register this device to an account yet to be chosen, and returns the
     * pairing code (`boson:pair:1`) to show: Boson Identity, or Photon on another device, scans it and
     * approves, which hands the user key over sealed to the code. The request expires after three minutes.
     *
     * The device key is the active profile's when that profile is empty; otherwise a new one, in memory, so
     * that a profile bound to another identity never has its key replaced.
     */
    suspend fun startPairing(deviceName: String): String = withContext(Dispatchers.IO) {
        val profileKey = keyManager.userId() == null
        val deviceKey = if (profileKey) keyManager.ensureDeviceKeyFor(null) else BosonCrypto.generateKeyPair()
        // Photon acts as the user itself, so it asks for the user key.
        val registration = directorClients.guest()
            .requestDeviceRegistration(deviceKey, deviceName.ifBlank { DEFAULT_DEVICE_NAME }, APP_NAME, true)
            .await()
        pairing = Pairing(registration, deviceKey, profileKey)
        registration.pairingCode.toString()
    }

    /**
     * Waits for the pairing started by [startPairing] to be answered: the Director holds the call until
     * the request is approved, denied or expired. On approval this device is registered to the user, and
     * the identity is committed here or handed to its own profile, with the device and node it was
     * registered with.
     */
    suspend fun finishPairing(): SessionState = withContext(Dispatchers.IO) {
        val active = pairing ?: throw AppError.InvalidInput(context.getString(R.string.onb_error_pairing_not_started))
        val approval = try {
            directorClients.guest().finishDeviceRegistration(active.registration).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RegistrationDeniedException) {
            pairing = null
            throw AppError.Conflict(context.getString(R.string.onb_error_pairing_denied), e)
        } catch (e: RegistrationExpiredException) {
            pairing = null
            throw AppError.Timeout(context.getString(R.string.onb_error_pairing_expired), e)
        } catch (e: NotFoundException) {
            pairing = null
            throw AppError.Timeout(context.getString(R.string.onb_error_pairing_expired), e)
        }
        pairing = null

        // The library refuses an approval without the key Photon asked for, so it is there.
        val userPrivateKey64 = BosonCrypto.privateKeyBytes64(approval.userKey.orElseThrow())
        val userId = approval.userId.toString()
        val nodeId = config().nodeId?.takeIf { it.isNotBlank() } ?: directorClients.probeNodeId().toString()
        val devicePrivateKey64 = BosonCrypto.privateKeyBytes64(active.deviceKey)

        when (val target = resolveProfileTarget(userId)) {
            is ProfileTarget.HostHere -> {
                keyManager.storeUserKey(userPrivateKey64)
                if (!active.profileKey)
                    keyManager.storeDeviceKey(devicePrivateKey64)
                sessionStore.setSession(userId)
                keyManager.setRegisteredNodeId(nodeId)
                SessionState.Authenticated(userId)
            }
            else -> {
                val seed = ProfileSeed(
                    userId = userId,
                    displayName = null,
                    userPrivateKey64 = userPrivateKey64,
                    devicePrivateKey64 = devicePrivateKey64,
                    registeredNodeId = nodeId,
                )
                when (target) {
                    is ProfileTarget.Existing -> SessionState.ReuseProfile(target.profileId, userId, seed)
                    else -> SessionState.NewProfileForIdentity(userId, seed)
                }
            }
        }
    }

    /** Forgets the pairing in progress; its request expires on the node by itself. */
    fun cancelPairing() {
        pairing = null
    }

    // ---- Profiles ------------------------------------------------------------------------------------

    /** Where an identity (derived from a key we hold) belongs relative to the active profile. */
    private sealed interface ProfileTarget {
        /** The active profile is a fresh scratch or already this identity: host it here. */
        object HostHere : ProfileTarget
        /** This identity already has its OWN profile ([profileId]): hand off to it. */
        data class Existing(val profileId: String) : ProfileTarget
        /** The active profile is a DIFFERENT bound identity: a fresh profile is needed. */
        object New : ProfileTarget
    }

    /**
     * Resolves which profile an identity ([userId]) belongs to, WITHOUT writing anything to the active
     * profile, so a new, paired or imported identity lands in the right profile instead of overwriting the
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

    /** True once this device is fully usable: signed in AND a local user key exists. */
    fun isReady(): Boolean = sessionStore.currentSession() != null && keyManager.hasUserKey()

    /** True if a user key is present on this device. */
    fun hasUserKey(): Boolean = keyManager.hasUserKey()

    /**
     * Commits an identity ([privateKey64]/[userId]) the Director has accepted, with no device registered
     * yet: stored on the active profile, and recorded as its session, when it can host the identity, else
     * returned as a [ProfileSeed] for a handoff with NOTHING written to the (foreign) active profile. The
     * device is registered afterwards, here or on the target's first bring-up.
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
     * already owned by the current user == [newUserId], is kept.
     */
    private fun adoptIdentity(newUserId: String?) {
        val current = keyManager.userId()?.toString()
        if (current != null && current != newUserId && keyManager.registeredNodeId() != null) {
            keyManager.rotateDeviceKey()
        }
    }

    /**
     * True if the signed-in account requires a passphrase for sensitive actions (adding a device is
     * passphrase-gated on the Director). Onboarding checks this after acquiring an identity to decide
     * whether the user must be prompted for the passphrase before this device can be registered.
     */
    suspend fun isPassphraseProtected(): Boolean = isPassphraseProtected(null)

    // As asked of the node [nodeId] names, when it is not the one this device is registered with yet.
    private suspend fun isPassphraseProtected(nodeId: String?): Boolean =
        withContext(Dispatchers.IO) {
            directorClients.client(pinnedNodeId = nodeId?.let { Id.of(it) }).profile.await().isPassphraseProtected
        }

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
     * and an imported backup registers no device (only pairing and a new account's registration do) - so
     * without this the messaging client connects but never reaches READY
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
            // Registering with a node the device is not registered with yet - a migration - binds the tokens
            // to that node rather than to the old one.
            val client = directorClients.client(pinnedNodeId = superNodeId?.let { Id.of(it) })
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
        if (isPassphraseProtected(nodeId)) return
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
        runCatching { directorClients.probeNodeId().toString() }.getOrNull()

    suspend fun signOut() {
        sessionStore.clear()
        // Sign-out ends the session only; keys, the registered-node marker, and local data are retained.
        // Deleting an identity is an explicit delete-profile action, not a side effect of signing out.
    }

    companion object {
        private const val APP_NAME = "Photon"
        private const val DEFAULT_DEVICE_NAME = "Android device"
    }
}
