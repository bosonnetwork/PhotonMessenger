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

import io.bosonnetwork.photon.core.boson.BosonSessionManager
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.boson.awaitResult
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.AuthTokenStore
import io.bosonnetwork.photon.core.model.NotificationPreferences
import io.bosonnetwork.photon.core.model.ThemeMode
import io.bosonnetwork.photon.core.model.ThemePreferences
import io.bosonnetwork.photon.core.model.shortId
import io.bosonnetwork.photon.core.network.DeviceRegistrationStore
import io.bosonnetwork.photon.core.network.DirectorApi
import io.bosonnetwork.photon.core.network.DirectorApiFactory
import io.bosonnetwork.photon.core.network.DirectorConfig
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.network.NotificationPreferencesStore
import io.bosonnetwork.photon.core.network.ThemePreferencesStore
import io.bosonnetwork.photon.core.network.model.ClearPassphraseRequest
import io.bosonnetwork.photon.core.network.model.RemoveDeviceRequest
import io.bosonnetwork.photon.core.network.model.SetPassphraseRequest
import io.bosonnetwork.photon.core.network.model.UpdateProfileRequest
import io.bosonnetwork.photon.core.network.toDirectorError
import io.bosonnetwork.photon.feature.settings.model.UiDevice
import io.bosonnetwork.photon.feature.settings.model.UiProfile
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.SessionInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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

    /** Drops the Director session, disconnects messaging, and wipes the token + local keys (M6-7). */
    suspend fun signOut(): Result<Unit>
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val apiFactory: DirectorApiFactory,
    private val configStore: DirectorConfigStore,
    private val themeStore: ThemePreferencesStore,
    private val notificationStore: NotificationPreferencesStore,
    private val tokenStore: AuthTokenStore,
    private val keyManager: KeyManager,
    private val session: BosonSessionManager,
    private val avatarPreparer: AvatarPreparer,
    private val registrationStore: DeviceRegistrationStore,
) : SettingsRepository {

    @Volatile
    private var cachedApi: Pair<DirectorConfig, DirectorApi>? = null

    private suspend fun config(): DirectorConfig = configStore.config.first()

    private suspend fun api(): DirectorApi {
        val cfg = config()
        cachedApi?.let { (cached, api) -> if (cached == cfg) return api }
        return apiFactory.create(cfg).also { cachedApi = cfg to it }
    }

    override val themePreferences: Flow<ThemePreferences> = themeStore.preferences

    override val notificationPreferences: Flow<NotificationPreferences> = notificationStore.preferences

    override suspend fun loadProfile(): Result<UiProfile> = runCatching {
        val cfg = config()
        val dto = api().getProfile()
        // Avatar is read from the public, auth-less endpoint; bust caches when the profile changes.
        val avatarUrl = dto.avatar?.takeIf { it.isNotBlank() }?.let {
            "${cfg.baseUrl}/api/v1/client/avatar/${dto.id}?v=${dto.updatedAt}"
        }
        UiProfile(
            id = dto.id,
            name = dto.name.orEmpty(),
            bio = dto.bio.orEmpty(),
            email = dto.email.orEmpty(),
            avatarUrl = avatarUrl,
            plan = dto.planName,
            passphraseProtected = dto.passphraseProtected,
        )
    }

    override suspend fun updateProfile(
        name: String?,
        bio: String?,
        email: String?,
        passphrase: String?,
    ): Result<Unit> = runCatching {
        api().updateProfile(UpdateProfileRequest(name = name, bio = bio, email = email, passphrase = passphrase))
    }.mapDirectorError()

    override suspend fun setPassphrase(newPassphrase: String, currentPassphrase: String?): Result<Unit> = runCatching {
        api().setPassphrase(SetPassphraseRequest(passphrase = newPassphrase, currentPassphrase = currentPassphrase))
    }.mapDirectorError()

    override suspend fun clearPassphrase(currentPassphrase: String): Result<Unit> = runCatching {
        api().clearPassphrase(ClearPassphraseRequest(passphrase = currentPassphrase))
    }.mapDirectorError()

    override suspend fun updateAvatar(uriString: String): Result<Unit> = runCatching {
        val prepared = avatarPreparer.prepare(uriString)
        val body = prepared.bytes.toRequestBody(prepared.mime.toMediaType())
        api().updateAvatar(body)
        Unit
    }

    override suspend fun removeAvatar(): Result<Unit> = runCatching { api().removeAvatar() }

    override suspend fun loadSessions(): Result<List<UiDevice>> = runCatching {
        // This screen lists messaging *sessions* (a service-level concept), not the Director device
        // registry (an account-level concept). A session exists only while a Photon client is
        // connected from a device, so the list is driven by getSessions(); the Director registry is
        // joined in on a best-effort basis only to supply a friendly name/app for each session.
        val client = session.messagingClient
            ?: throw AppError.Network("Not connected to the messaging service")
        val currentDeviceId = client.deviceId?.toString()
        val sessions: List<SessionInfo> = client.getSessions().awaitResult()

        val devicesById = runCatching { api().getDevices() }.getOrNull()
            ?.associateBy { it.id }
            ?: emptyMap()

        sessions.map { s ->
            val id = s.deviceId().toString()
            val device = devicesById[id]
            UiDevice(
                deviceId = id,
                name = device?.name?.takeIf { it.isNotBlank() } ?: shortId(id),
                app = device?.app,
                online = s.online(),
                lastActive = maxOf(s.lastActive(), device?.lastSeen ?: 0L),
                lastAddress = s.lastAddress()?.takeIf { it.isNotBlank() } ?: device?.lastAddress,
                registeredAt = device?.createdAt ?: 0L,
                isCurrent = id == currentDeviceId,
            )
        }.sortedWith(compareByDescending<UiDevice> { it.isCurrent }.thenByDescending { it.lastActive })
    }

    override suspend fun loadDevices(): Result<List<UiDevice>> = runCatching {
        // Account-level: every device registered under the account (from any Boson app), whether or
        // not it has a live messaging session. The current device is identified from the persisted
        // registration record so it resolves even while the messaging client is disconnected.
        val currentDeviceId = registrationStore.get()?.deviceId
        api().getDevices().map { d ->
            UiDevice(
                deviceId = d.id,
                name = d.name?.takeIf { it.isNotBlank() } ?: shortId(d.id),
                app = d.app,
                online = false,
                lastActive = d.lastSeen,
                lastAddress = d.lastAddress,
                registeredAt = d.createdAt,
                isCurrent = d.id == currentDeviceId,
            )
        }.sortedWith(compareByDescending<UiDevice> { it.isCurrent }.thenByDescending { it.registeredAt })
    }

    override suspend fun revokeSession(deviceId: String): Result<Unit> = runCatching {
        val client = session.messagingClient
            ?: throw AppError.Network("Not connected to the messaging service")
        client.revokeSession(parseId(deviceId)).awaitResult()
        Unit
    }

    override suspend fun removeDevice(deviceId: String, passphrase: String?): Result<Unit> = runCatching {
        api().removeDevice(deviceId, RemoveDeviceRequest(passphrase = passphrase))
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
        runCatching { api().signOut() }
        runCatching { session.disconnect() }
        tokenStore.clear()
        keyManager.clear()
        registrationStore.clear()
        cachedApi = null
    }

    private fun parseId(text: String): Id =
        try {
            Id.of(text.trim())
        } catch (e: Exception) {
            throw AppError.InvalidInput("Invalid device ID", e)
        }

    /** Re-wraps a Director HTTP failure as an [AppError] so the UI can tell 428/403 apart (M6 passphrase). */
    private fun <T> Result<T>.mapDirectorError(): Result<T> =
        recoverCatching { throw it.toDirectorError() }
}
