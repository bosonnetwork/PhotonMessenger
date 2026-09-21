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

package io.bosonnetwork.photon.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.photon.app.navigation.Routes
import io.bosonnetwork.photon.app.navigation.TopLevelDestination
import io.bosonnetwork.photon.app.session.SessionController
import io.bosonnetwork.photon.app.session.SessionStatus
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.boson.UnreadTracker
import io.bosonnetwork.photon.core.model.SessionStore
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.security.EncryptedSessionStore
import io.bosonnetwork.photon.core.security.Profile
import io.bosonnetwork.photon.core.security.ProfileManager
import io.bosonnetwork.photon.core.security.SecretStore
import io.bosonnetwork.photon.feature.contacts.data.ContactRepository
import io.bosonnetwork.photon.feature.onboarding.data.AuthRepository
import io.bosonnetwork.photon.feature.onboarding.data.NodeMigration
import io.bosonnetwork.photon.feature.onboarding.data.ProfileSeed
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-root state (F1 / M1-16): picks the start destination from the persisted session and drives
 * the messaging bring-up. Exposes the [SessionController] status so the shell can show a connection
 * banner. A returning (signed-in) user auto-connects on launch; onboarding completion and sign-out
 * flip the session on/off.
 */
/** Unread/notification counts for the bottom-navigation tabs. */
data class TabBadges(val chats: Int = 0, val contacts: Int = 0)

@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sessionController: SessionController,
    private val unreadTracker: UnreadTracker,
    private val contactRepository: ContactRepository,
    private val profileManager: ProfileManager,
    private val keyManager: KeyManager,
    private val sessionStore: SessionStore,
    private val configStore: DirectorConfigStore,
    private val authRepository: AuthRepository,
) : ViewModel() {

    /**
     * Home only when this device is fully ready (signed in AND a local user key); otherwise onboarding.
     * A session without a key (returning user on a fresh device) must acquire its key first, so it goes
     * to onboarding rather than Home where bring-up would fail with "user key missing" (O2).
     */
    val startDestination: String =
        if (authRepository.isReady()) TopLevelDestination.HOME.route else Routes.ONBOARDING

    val sessionStatus: StateFlow<SessionStatus> = sessionController.status

    /** A pending home super node migration awaiting the user's confirmation (null when none). */
    val migrationRequired: StateFlow<NodeMigration?> = sessionController.migrationRequired

    fun confirmMigration() = sessionController.confirmMigration()
    fun dismissMigration() = sessionController.dismissMigration()

    // friendRequests() re-subscribes itself when the session's client appears, so this just follows it.
    // The list holds every request record; only incoming ones still waiting for an answer need attention.
    private val pendingRequestCount: Flow<Int> =
        contactRepository.friendRequests().map { list -> list.count { it.awaitingAnswer } }.catch { emit(0) }

    /** Bottom-tab badges: total unread messages (Chats) and pending friend requests (Contacts). */
    val badges: StateFlow<TabBadges> =
        combine(unreadTracker.totalUnread, pendingRequestCount) { unread, requests ->
            TabBadges(chats = unread, contacts = requests)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TabBadges())

    init {
        // Drop transient onboarding leftovers (unbound, non-active profiles) and their secrets prefs.
        profileManager.gcUnboundInactiveProfiles().forEach {
            appContext.deleteSharedPreferences(profileManager.secretsFileNameFor(it))
        }
        if (authRepository.isReady()) {
            sessionController.ensureConnected()
            // A profile seeded via handoff (e.g. an imported identity) lands at Home without a name; fill
            // it in from the Director profile so the account switcher shows a name, not a bare id.
            val active = profileManager.activeProfileId()
            if (profileManager.listProfiles().find { it.id == active }?.displayName.isNullOrBlank()) {
                syncActiveProfileName()
            }
        }
    }

    /** The profiles available on this device, and which one is active (for the account switcher). */
    fun profiles(): List<Profile> = profileManager.listProfiles()
    fun activeProfileId(): String = profileManager.activeProfileId()

    /** Called after onboarding completes (sign-in or identity bind) to bring the session up. */
    fun onSignedIn() = sessionController.ensureConnected()

    /**
     * After onboarding resolves the identity, reconcile it with the profile registry. If this identity
     * already lives in a DIFFERENT profile (e.g. re-imported into a freshly added account), switch to
     * that profile and relaunch; otherwise bind it to the active profile so the switcher can name it.
     * Returns true if a relaunch was triggered (the caller must not navigate).
     */
    fun reconcileProfileIdentityAndMaybeRelaunch(): Boolean {
        val userId = keyManager.userId()?.toString() ?: return false
        val active = profileManager.activeProfileId()
        val existing = profileManager.findByUserId(userId)
        if (existing != null && existing.id != active) {
            // This identity already has its own profile (a rare duplicate, e.g. re-imported into a
            // freshly added account): hand off to it - signing in with its own key.
            handOffToProfile(existing.id)
            return true
        }
        // Bind immediately (short-id fallback), then fill in the display name from the Director profile.
        profileManager.bindUserId(active, userId, displayName = null, homeNodeId = keyManager.registeredNodeId())
        syncActiveProfileName()
        return false
    }

    /**
     * Names the active profile from the Director profile so the account switcher shows a name rather than
     * a bare id (covers the host-here create/import that binds a placeholder, and a seeded profile whose
     * name was not known at seed time). No-op when unreachable - the switcher falls back to the short id.
     */
    private fun syncActiveProfileName() {
        val userId = keyManager.userId()?.toString() ?: return
        val active = profileManager.activeProfileId()
        viewModelScope.launch {
            runCatching { authRepository.currentProfileName() }.getOrNull()?.let { name ->
                profileManager.bindUserId(active, userId, name, keyManager.registeredNodeId())
            }
        }
    }

    /**
     * Hands the resolved identity to [existingProfileId] (or a fresh profile when null) and relaunches so
     * the whole singleton graph rebinds. The active/source profile is never modified: onboarding kept its
     * session in memory only, so the previous user's data and disk session stay intact (no source clear).
     *
     * With a [seed] (a create/import completed in the current context) the target is seeded with the Boson
     * identity and signed in to it so it comes up ready. Without a seed (reusing a profile that ALREADY
     * holds this identity's key) the target is signed in with that profile's own key. No OAuth token is
     * ever relocated.
     */
    fun handOffToProfile(existingProfileId: String?, seed: ProfileSeed? = null) {
        viewModelScope.launch {
            val cfg = configStore.config.first()
            val targetId = existingProfileId ?: profileManager.createProfile()
            val secrets = SecretStore(appContext, profileManager.secretsFileNameFor(targetId))
            if (seed != null) {
                EncryptedSessionStore(secrets).seedDurably(seed.userId)
                KeyManager.seedInto(secrets, seed.userPrivateKey64, seed.devicePrivateKey64, seed.registeredNodeId)
                profileManager.bindUserId(targetId, seed.userId, seed.displayName, seed.registeredNodeId)
            } else {
                // Reuse: the target already holds its key + device; sign in with that key so it comes up
                // ready (never through OAuth).
                KeyManager.readUserKey(secrets)?.let { key ->
                    runCatching { authRepository.signInWith(key) }
                        .onSuccess { EncryptedSessionStore(secrets).seedDurably(it) }
                }
            }
            seedDirectorConfig(targetId, cfg.baseUrl, cfg.nodeId)
            profileManager.setActive(targetId)
            AppRelauncher.relaunch(appContext)
        }
    }

    /** Writes the Director base URL + pin node id into a target profile's settings (merges, not clobbers). */
    private suspend fun seedDirectorConfig(id: String, baseUrl: String, nodeId: String?) {
        val dataStore = PreferenceDataStoreFactory.create {
            File(profileManager.filesRootFor(id), "settings.preferences_pb")
        }
        val configStore = DirectorConfigStore(dataStore)
        configStore.setBaseUrl(baseUrl)
        configStore.setNodeId(nodeId)
    }

    /** Creates a fresh profile and relaunches into onboarding for a new identity. */
    fun addAccount() {
        profileManager.setActive(profileManager.createProfile())
        AppRelauncher.relaunch(appContext)
    }

    /**
     * Switches the active profile and relaunches so the whole graph rebinds to it. Selecting an existing
     * account is an explicit sign-in to that identity, not fresh onboarding: if the target has no persisted
     * session (e.g. it was signed out of earlier - which ends the session but keeps the key/data), sign in
     * with the target's OWN stored user key so it comes up ready at Home rather than bouncing to onboarding.
     * Its keys, Director config, and data are left untouched; a target that is still signed in switches
     * immediately with no network round-trip.
     */
    fun switchProfile(id: String) {
        if (id == profileManager.activeProfileId()) return
        viewModelScope.launch {
            val secrets = SecretStore(appContext, profileManager.secretsFileNameFor(id))
            val store = EncryptedSessionStore(secrets)
            if (store.currentSession() == null) {
                KeyManager.readUserKey(secrets)?.let { key ->
                    runCatching { authRepository.signInWith(key) }
                        .onSuccess { store.seedDurably(it) }
                }
            }
            profileManager.setActive(id)
            AppRelauncher.relaunch(appContext)
        }
    }

    /**
     * Signs into [id] from the onboarding account picker and relaunches so the graph rebinds to it.
     * Unlike [switchProfile] this also handles re-signing into the CURRENTLY active profile: sign-out
     * ends the session but keeps the profile's key and local data, so the just-signed-out account is a
     * valid target. A profile that is signed out is signed in with its own stored user key; one that is
     * still signed in is opened as-is.
     */
    fun signInToProfile(id: String) {
        viewModelScope.launch {
            val secrets = SecretStore(appContext, profileManager.secretsFileNameFor(id))
            val store = EncryptedSessionStore(secrets)
            if (store.currentSession() == null) {
                KeyManager.readUserKey(secrets)?.let { key ->
                    runCatching { authRepository.signInWith(key) }
                        .onSuccess { store.seedDurably(it) }
                }
            }
            profileManager.setActive(id)
            AppRelauncher.relaunch(appContext)
        }
    }

    /** Removes a profile (and its secrets). If it was active, relaunches into another/fresh profile. */
    fun deleteProfile(id: String) {
        val wasActive = id == profileManager.activeProfileId()
        profileManager.deleteProfile(id)
        appContext.deleteSharedPreferences(profileManager.secretsFileNameFor(id))
        if (wasActive) {
            val next = profileManager.listProfiles().firstOrNull()?.id ?: profileManager.createProfile()
            profileManager.setActive(next)
            AppRelauncher.relaunch(appContext)
        }
    }

    /** Called after sign-out to tear the session + foreground service down. */
    fun onSignedOut() = sessionController.disconnect()

    fun retryConnection() = sessionController.retry()
}
