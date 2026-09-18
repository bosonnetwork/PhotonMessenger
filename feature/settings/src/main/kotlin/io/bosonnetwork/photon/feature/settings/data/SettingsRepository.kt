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

package io.bosonnetwork.photon.feature.settings.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.director.client.ProfileUpdate
import io.bosonnetwork.photon.core.boson.BosonSessionManager
import io.bosonnetwork.photon.core.boson.DirectorAvatars
import io.bosonnetwork.photon.core.boson.DirectorClients
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.boson.awaitResult
import io.bosonnetwork.photon.core.boson.toDirectorError
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.SessionStore
import io.bosonnetwork.photon.core.model.NotificationPreferences
import io.bosonnetwork.photon.core.model.ThemeMode
import io.bosonnetwork.photon.core.model.ThemePreferences
import io.bosonnetwork.photon.core.model.shortId
import io.bosonnetwork.photon.core.network.NotificationPreferencesStore
import io.bosonnetwork.photon.core.network.ThemePreferencesStore
import io.bosonnetwork.photon.feature.settings.R
import io.bosonnetwork.photon.feature.settings.model.UiDevice
import io.bosonnetwork.photon.feature.settings.model.UiProfile
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.SessionInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.await

/**
 * Settings backend (spec 2.6, screen 6, M6-1/2/3/5/7): profile + avatar over the Director, the
 * device/session list (Director registry joined with live messaging sessions), appearance prefs, and
 * sign-out. Returns UI models so the ViewModels stay decoupled and unit-testable.
 */
interface SettingsRepository {
    val themePreferences: Flow<ThemePreferences>
    val notificationPreferences: Flow<NotificationPreferences>

    suspend fun loadProfile(): Result<UiProfile>

    /**
     * Updates any subset of {name, bio, email}. [passphrase] is required only when the account has a
     * passphrase configured; a wrong/missing one fails with [AppError.Forbidden]/[AppError.PassphraseRequired].
     */
    suspend fun updateProfile(name: String?, bio: String?, email: String?, passphrase: String?): Result<Unit>
    suspend fun updateAvatar(uriString: String): Result<Unit>
    suspend fun removeAvatar(): Result<Unit>

    /** Sets the first passphrase or changes an existing one; pass [currentPassphrase] when one is set. */
    suspend fun setPassphrase(newPassphrase: String, currentPassphrase: String?): Result<Unit>

    /** Removes the account passphrase; [currentPassphrase] must match the configured one. */
    suspend fun clearPassphrase(currentPassphrase: String): Result<Unit>

    /** Live messaging sessions (service-level); one row per connected device (screen 6). */
    suspend fun loadSessions(): Result<List<UiDevice>>

    /**
     * All devices registered under the account (Director registry, account-level), regardless of
     * whether each one currently has a live messaging session. Contrast [loadSessions].
     */
    suspend fun loadDevices(): Result<List<UiDevice>>

    suspend fun revokeSession(deviceId: String): Result<Unit>

    /** Deregisters a device. [passphrase] is required only when the account has one configured. */
    suspend fun removeDevice(deviceId: String, passphrase: String?): Result<Unit>

    suspend fun setThemeMode(mode: ThemeMode): Result<Unit>
    suspend fun setDynamicColor(enabled: Boolean): Result<Unit>

    suspend fun setNotificationsEnabled(enabled: Boolean): Result<Unit>
    suspend fun setNotificationPreview(showPreview: Boolean): Result<Unit>

    /** Ends the session and disconnects messaging; the profile's keys and data stay (M6-7). */
    suspend fun signOut(): Result<Unit>
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val directorClients: DirectorClients,
    private val themeStore: ThemePreferencesStore,
    private val notificationStore: NotificationPreferencesStore,
    private val sessionStore: SessionStore,
    private val keyManager: KeyManager,
    private val session: BosonSessionManager,
    private val avatarPreparer: AvatarPreparer,
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private suspend fun director() = directorClients.client()

    override val themePreferences: Flow<ThemePreferences> = themeStore.preferences

    override val notificationPreferences: Flow<NotificationPreferences> = notificationStore.preferences

    override suspend fun loadProfile(): Result<UiProfile> = runCatching {
        val profile = director().profile.await()
        val id = profile.id.toString()
        // Bust the image cache when the profile changes.
        val avatarUrl = profile.avatar.orElse(null)?.takeIf { it.isNotBlank() }?.let {
            DirectorAvatars.uri(id, profile.updatedAt)
        }
        UiProfile(
            id = id,
            name = profile.name.orElse(""),
            bio = profile.bio.orElse(""),
            email = profile.email.orElse(""),
            avatarUrl = avatarUrl,
            plan = profile.planName,
            passphraseProtected = profile.isPassphraseProtected,
        )
    }

    override suspend fun updateProfile(
        name: String?,
        bio: String?,
        email: String?,
        passphrase: String?,
    ): Result<Unit> = runCatching {
        // Only the fields given are changed; a null one is left as it is, never cleared.
        if (name == null && bio == null && email == null) return@runCatching
        val update = ProfileUpdate()
        name?.let { update.name(it) }
        bio?.let { update.bio(it) }
        email?.let { update.email(it) }
        director().updateProfile(update, passphrase).await()
        Unit
    }.mapDirectorError()

    override suspend fun setPassphrase(newPassphrase: String, currentPassphrase: String?): Result<Unit> = runCatching {
        val director = director()
        if (currentPassphrase == null) director.setPassphrase(newPassphrase).await()
        else director.updatePassphrase(currentPassphrase, newPassphrase).await()
        Unit
    }.mapDirectorError()

    override suspend fun clearPassphrase(currentPassphrase: String): Result<Unit> = runCatching {
        director().clearPassphrase(currentPassphrase).await()
        Unit
    }.mapDirectorError()

    override suspend fun updateAvatar(uriString: String): Result<Unit> = runCatching {
        val prepared = avatarPreparer.prepare(uriString)
        director().updateAvatar(prepared.bytes, prepared.mime).await()
        Unit
    }

    override suspend fun removeAvatar(): Result<Unit> = runCatching {
        director().removeAvatar().await()
        Unit
    }

    override suspend fun loadSessions(): Result<List<UiDevice>> = runCatching {
        // This screen lists messaging *sessions* (a service-level concept), not the Director device
        // registry (an account-level concept). A session exists only while a Photon client is
        // connected from a device, so the list is driven by getSessions(); the Director registry is
        // joined in on a best-effort basis only to supply a friendly name/app for each session.
        val client = session.messagingClient
            ?: throw AppError.Network(context.getString(R.string.settings_repository_error_not_connected))
        val currentDeviceId = client.deviceId?.toString()
        val sessions: List<SessionInfo> = client.getSessions().awaitResult()

        val devicesById = runCatching { director().listDevices().await() }.getOrNull()
            ?.associateBy { it.id.toString() }
            ?: emptyMap()

        sessions.map { s ->
            val id = s.deviceId().toString()
            val device = devicesById[id]
            UiDevice(
                deviceId = id,
                name = device?.name?.takeIf { it.isNotBlank() } ?: shortId(id),
                app = device?.app?.takeIf { it.isNotBlank() },
                online = s.online(),
                lastActive = maxOf(s.lastActive(), device?.lastSeen ?: 0L),
                lastAddress = s.lastAddress()?.takeIf { it.isNotBlank() } ?: device?.lastAddress?.orElse(null),
                registeredAt = device?.createdAt ?: 0L,
                isCurrent = id == currentDeviceId,
            )
        }.sortedByDescending { it.lastActive } // most-recently-active first; no last-active (0) sinks to the end
    }

    override suspend fun loadDevices(): Result<List<UiDevice>> = runCatching {
        // Account-level: every device registered under the account (from any Boson app), whether or
        // not it has a live messaging session. The current device is identified from the local device
        // key so it resolves even while the messaging client is disconnected.
        val currentDeviceId = keyManager.deviceId()?.toString()
        director().listDevices().await().map { d ->
            val id = d.id.toString()
            UiDevice(
                deviceId = id,
                name = d.name.takeIf { it.isNotBlank() } ?: shortId(id),
                app = d.app.takeIf { it.isNotBlank() },
                online = false,
                lastActive = d.lastSeen,
                lastAddress = d.lastAddress.orElse(null),
                registeredAt = d.createdAt,
                isCurrent = id == currentDeviceId,
            )
        }.sortedByDescending { it.lastActive } // most-recently-active first; no last-active (0) sinks to the end
    }

    override suspend fun revokeSession(deviceId: String): Result<Unit> = runCatching {
        val client = session.messagingClient
            ?: throw AppError.Network(context.getString(R.string.settings_repository_error_not_connected))
        client.revokeSession(parseId(deviceId)).awaitResult()
        Unit
    }

    override suspend fun removeDevice(deviceId: String, passphrase: String?): Result<Unit> = runCatching {
        director().removeDevice(parseId(deviceId), passphrase).await()
        Unit
    }.mapDirectorError()

    override suspend fun setThemeMode(mode: ThemeMode): Result<Unit> = runCatching {
        themeStore.setMode(mode)
    }

    override suspend fun setDynamicColor(enabled: Boolean): Result<Unit> = runCatching {
        themeStore.setDynamicColor(enabled)
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean): Result<Unit> = runCatching {
        notificationStore.setEnabled(enabled)
    }

    override suspend fun setNotificationPreview(showPreview: Boolean): Result<Unit> = runCatching {
        notificationStore.setShowPreview(showPreview)
    }

    override suspend fun signOut(): Result<Unit> = runCatching {
        runCatching { session.disconnect() }
        sessionStore.clear()
        // Sign-out ends the session only; the profile's keys, registered-node marker, and local data
        // are retained (this device is federated - the client owns the permanent data). Removing an
        // identity is an explicit delete-profile action.
    }

    private fun parseId(text: String): Id =
        try {
            Id.of(text.trim())
        } catch (e: Exception) {
            throw AppError.InvalidInput(context.getString(R.string.settings_repository_error_invalid_device_id), e)
        }

    /** Re-wraps a Director HTTP failure as an [AppError] so the UI can tell 428/403 apart (M6 passphrase). */
    private fun <T> Result<T>.mapDirectorError(): Result<T> =
        recoverCatching { throw it.toDirectorError() }
}
