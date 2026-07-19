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
import io.bosonnetwork.photon.core.model.AuthTokenStore
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.security.EncryptedAuthTokenStore
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
    private val tokenStore: AuthTokenStore,
    private val configStore: DirectorConfigStore,
    authRepository: AuthRepository,
) : ViewModel() {

    /**
     * Home only when this device is fully ready (token AND local user key); otherwise onboarding.
     * A token without a key (returning user on a fresh device) must acquire its key first, so it goes
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
    private val pendingRequestCount: Flow<Int> =
        contactRepository.friendRequests().map { it.size }.catch { emit(0) }

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
        if (authRepository.isReady()) sessionController.ensureConnected()
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
            // freshly added account): hand off to it - carrying the session - rather than keep a copy.
            handOffToProfile(existing.id)
            return true
        }
        profileManager.bindUserId(active, userId, displayName = null, homeNodeId = keyManager.registeredNodeId())
        return false
    }

    /**
     * Hands the just-authenticated session to the target profile and relaunches into it so the whole
     * singleton graph rebinds. [existingProfileId] non-null reuses that profile (comes up ready at Home);
     * null creates a fresh profile and continues onboarding there (a new identity the active, already
     * bound, profile cannot host). The target is seeded with the current token + Director config so it
     * needs no second sign-in; the source profile's token is cleared, since the freshly obtained token
     * authorizes the TARGET identity, not the source. Selection is driven only by the resolved identity
     * (see AuthRepository.currentSession), never by stale local state.
     */
    fun handOffToProfile(existingProfileId: String?) {
        viewModelScope.launch {
            val sourceId = profileManager.activeProfileId()
            val token = tokenStore.currentToken()
            val cfg = configStore.config.first()
            val targetId = existingProfileId ?: profileManager.createProfile()
            if (token != null) authTokenStoreFor(targetId).seedDurably(token)
            seedDirectorConfig(targetId, cfg.baseUrl, cfg.nodeId)
            if (targetId != sourceId) authTokenStoreFor(sourceId).clearDurably()
            profileManager.setActive(targetId)
            AppRelauncher.relaunch(appContext)
        }
    }

    /**
     * Hands a self-sovereign identity (PoW create / key import) to a target profile the app RESOLVED it
     * belongs to, seeding the key material the target lacks so it comes up ready after the single
     * relaunch. Unlike [handOffToProfile] this never reads or clears the active (possibly foreign)
     * profile: the source path deliberately wrote nothing to it, so the previous user's data stays
     * intact. [existingProfileId] non-null reuses that profile (its key/device are already present, so
     * only the token is refreshed); null creates a fresh profile seeded with the key (its device
     * registers itself on first bring-up when [ProfileSeed.registeredNodeId] is null). The target profile
     * is bound to the identity so the account switcher can name it.
     */
    fun handOffSeededProfile(existingProfileId: String?, seed: ProfileSeed) {
        viewModelScope.launch {
            val cfg = configStore.config.first()
            val targetId = existingProfileId ?: profileManager.createProfile()
            val secrets = SecretStore(appContext, profileManager.secretsFileNameFor(targetId))
            EncryptedAuthTokenStore(secrets).seedDurably(seed.token)
            KeyManager.seedInto(secrets, seed.userPrivateKey64, seed.devicePrivateKey64, seed.registeredNodeId)
            seedDirectorConfig(targetId, cfg.baseUrl, cfg.nodeId)
            profileManager.bindUserId(targetId, seed.userId, seed.displayName, seed.registeredNodeId)
            profileManager.setActive(targetId)
            AppRelauncher.relaunch(appContext)
        }
    }

    /** Token store for an ARBITRARY profile (used to seed/clear a profile that is not the active one). */
    private fun authTokenStoreFor(id: String): EncryptedAuthTokenStore =
        EncryptedAuthTokenStore(SecretStore(appContext, profileManager.secretsFileNameFor(id)))

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

    /** Switches the active profile and relaunches so the whole graph rebinds to it. */
    fun switchProfile(id: String) {
        if (id == profileManager.activeProfileId()) return
        profileManager.setActive(id)
        AppRelauncher.relaunch(appContext)
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
