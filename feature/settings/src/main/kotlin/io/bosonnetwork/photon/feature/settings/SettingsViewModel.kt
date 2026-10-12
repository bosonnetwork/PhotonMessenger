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

package io.bosonnetwork.photon.feature.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.director.client.exceptions.ProfileChangedException
import io.bosonnetwork.photon.core.boson.CardState
import io.bosonnetwork.photon.core.designsystem.component.passphraseLockedMessage
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.NotificationPreferences
import io.bosonnetwork.photon.core.model.ThemeMode
import io.bosonnetwork.photon.core.model.ThemePreferences
import io.bosonnetwork.photon.feature.settings.R
import io.bosonnetwork.photon.feature.settings.data.SettingsRepository
import io.bosonnetwork.photon.feature.settings.model.UiProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val profile: UiProfile? = null,
    val savingProfile: Boolean = false,
    val error: String? = null,
)

/** The public profile card as Settings shows it. */
data class ProfileCardUi(
    val status: Status = Status.Checking,
    /** Publishing or unpublishing is under way. */
    val busy: Boolean = false,
) {
    enum class Status {
        Checking,
        Off,
        On,

        /** Published, but it doesn't show the profile as it is now. */
        Outdated,

        /** The home node offers no web gateway to publish through. */
        Unavailable,

        /** The state couldn't be checked; the user can try again. */
        Unknown,
    }

    val published: Boolean get() = status == Status.On || status == Status.Outdated
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** True while the identity's backup waits for its check: Settings reminds the user. */
    private val _backupPending = MutableStateFlow(repository.isBackupPending())
    val backupPending: StateFlow<Boolean> = _backupPending.asStateFlow()

    /** Reads the backup reminder again, as the user may just have checked the backup. */
    fun refreshBackupState() {
        _backupPending.value = repository.isBackupPending()
    }

    val theme: StateFlow<ThemePreferences> = repository.themePreferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemePreferences(),
    )

    val notifications: StateFlow<NotificationPreferences> = repository.notificationPreferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotificationPreferences(),
    )

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signedOut = _signedOut.asSharedFlow()

    /** Emitted when a passphrase set/change/clear succeeds, so the UI can close its dialog. */
    private val _passphraseUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val passphraseUpdated = _passphraseUpdated.asSharedFlow()

    /** New recovery codes to show, once; null when none are waiting. */
    private val _recoveryCodes = MutableStateFlow<List<String>?>(null)
    val recoveryCodes: StateFlow<List<String>?> = _recoveryCodes.asStateFlow()

    /** One-shot: a URL to open in the browser (the web portal, to reset a forgotten passphrase). */
    private val _openUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openUrl = _openUrl.asSharedFlow()

    private val _card = MutableStateFlow(ProfileCardUi())
    val card: StateFlow<ProfileCardUi> = _card.asStateFlow()

    init {
        // The profile as kept shows at once, offline too, until the node's answer replaces it.
        viewModelScope.launch {
            repository.keptProfile.collect { kept ->
                if (kept != null && _uiState.value.profile?.synced != true)
                    _uiState.value = _uiState.value.copy(loading = false, profile = kept)
            }
        }
        refresh()
        checkCard()
    }

    /** Asks the home node how the public profile card stands. */
    fun checkCard() {
        viewModelScope.launch {
            _card.value = ProfileCardUi(ProfileCardUi.Status.Checking)
            _card.value = ProfileCardUi(cardStatus())
        }
    }

    /** Publishes the profile as the public card, or publishes it again when it is out of date. */
    fun publishCard() = changeCard(R.string.settings_card_publish_error) { repository.publishCard() }

    /** Stops publishing the public card. */
    fun unpublishCard() = changeCard(R.string.settings_card_stop_error) { repository.unpublishCard() }

    private fun changeCard(@StringRes failureMessageRes: Int, change: suspend () -> Result<Unit>) {
        if (_card.value.busy) return
        viewModelScope.launch {
            _card.value = _card.value.copy(busy = true)
            change().onFailure { _messages.tryEmit(context.getString(failureMessageRes, it.reason())) }
            _card.value = ProfileCardUi(cardStatus())
        }
    }

    private suspend fun cardStatus(): ProfileCardUi.Status =
        repository.cardState().fold(
            onSuccess = {
                when {
                    it !is CardState.Published -> ProfileCardUi.Status.Off
                    it.outdated -> ProfileCardUi.Status.Outdated
                    else -> ProfileCardUi.Status.On
                }
            },
            onFailure = { if (it is AppError.NotFound) ProfileCardUi.Status.Unavailable else ProfileCardUi.Status.Unknown },
        )

    // After a change of the profile: a published card follows it, so that it never shows the old profile.
    // A card not known yet (still checking, or the check failed) is checked again by the refresh.
    private suspend fun followProfileChange() {
        val before = _card.value
        if (before.status == ProfileCardUi.Status.Off || before.status == ProfileCardUi.Status.Unavailable) return
        _card.value = before.copy(busy = true)
        repository.refreshCard().onFailure {
            if (before.published) _messages.tryEmit(context.getString(R.string.settings_card_update_failed, it.reason()))
        }
        _card.value = ProfileCardUi(cardStatus())
    }

    // A profile changed elsewhere meanwhile (another device, Boson Identity) is not overwritten: it is
    // shown as it is now, and the user makes the change again on it.
    private fun Throwable.isProfileChanged(): Boolean =
        this is AppError.Conflict && cause is ProfileChangedException

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            repository.loadProfile()
                .onSuccess { _uiState.value = SettingsUiState(loading = false, profile = it) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(loading = false, error = it.message)
                }
        }
    }

    fun saveProfile(name: String, bio: String, email: String, passphrase: String? = null) {
        val revision = _uiState.value.profile?.revision ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(savingProfile = true)
            val result = repository.updateProfile(name = name, bio = bio, email = email, passphrase = passphrase, revision = revision)
                .onFailure { profileChangeFailed(it, R.string.settings_profile_save_error) }
            _uiState.value = _uiState.value.copy(savingProfile = false)
            refresh()
            if (result.isSuccess) followProfileChange()
        }
    }

    private fun profileChangeFailed(e: Throwable, @StringRes failureMessageRes: Int) {
        _messages.tryEmit(
            if (e.isProfileChanged()) context.getString(R.string.settings_profile_changed_elsewhere)
            else context.getString(failureMessageRes, e.reason()),
        )
    }

    /**
     * Sets the first passphrase or changes an existing one (pass [currentPassphrase] when one is set). A
     * first passphrase comes with recovery codes, so that forgetting it is not the end of the account.
     */
    fun setPassphrase(newPassphrase: String, currentPassphrase: String?) {
        viewModelScope.launch {
            repository.setPassphrase(newPassphrase, currentPassphrase)
                .onSuccess {
                    _passphraseUpdated.tryEmit(Unit)
                    if (currentPassphrase == null) {
                        repository.makeRecoveryCodes(newPassphrase)
                            .onSuccess { codes -> _recoveryCodes.value = codes }
                            .onFailure {
                                _messages.tryEmit(context.getString(R.string.settings_recovery_codes_after_set_error, it.reason()))
                            }
                    } else {
                        _messages.tryEmit(context.getString(R.string.settings_passphrase_saved_message))
                    }
                    refresh()
                }
                .onFailure {
                    _messages.tryEmit(context.getString(R.string.settings_passphrase_set_error, it.reason()))
                }
        }
    }

    /** Makes a new set of recovery codes, replacing the earlier ones; [passphrase] is the current one. */
    fun makeRecoveryCodes(passphrase: String) {
        viewModelScope.launch {
            repository.makeRecoveryCodes(passphrase)
                .onSuccess { codes ->
                    _passphraseUpdated.tryEmit(Unit)
                    _recoveryCodes.value = codes
                    refresh()
                }
                .onFailure {
                    _messages.tryEmit(context.getString(R.string.settings_recovery_codes_error, it.reason()))
                }
        }
    }

    /** The user saved the codes shown; they are not shown again. */
    fun dismissRecoveryCodes() {
        _recoveryCodes.value = null
    }

    /**
     * A forgotten passphrase is reset on the web portal (or in Boson Identity), with a recovery code, a
     * linked account or a passkey: the reset grant they win returns only to the Director's own site.
     */
    fun openPortal() {
        viewModelScope.launch { _openUrl.tryEmit(repository.portalUrl()) }
    }

    /** Removes the account passphrase; [currentPassphrase] must match the configured one. */
    fun clearPassphrase(currentPassphrase: String) {
        viewModelScope.launch {
            repository.clearPassphrase(currentPassphrase)
                .onSuccess {
                    _passphraseUpdated.tryEmit(Unit)
                    _messages.tryEmit(context.getString(R.string.settings_passphrase_removed_message))
                    refresh()
                }
                .onFailure {
                    _messages.tryEmit(context.getString(R.string.settings_passphrase_remove_error, it.reason()))
                }
        }
    }

    fun updateAvatar(uriString: String) = changeAvatar(R.string.settings_photo_update_error) { revision ->
        repository.updateAvatar(uriString, revision)
    }

    fun removeAvatar() = changeAvatar(R.string.settings_photo_remove_error) { revision ->
        repository.removeAvatar(revision)
    }

    private fun changeAvatar(@StringRes failureMessageRes: Int, change: suspend (revision: Long) -> Result<Unit>) {
        val revision = _uiState.value.profile?.revision ?: return
        viewModelScope.launch {
            change(revision)
                .onSuccess {
                    refresh()
                    followProfileChange()
                }
                .onFailure {
                    profileChangeFailed(it, failureMessageRes)
                    if (it.isProfileChanged()) refresh()
                }
        }
    }

    fun setThemeMode(mode: ThemeMode) = run(R.string.settings_theme_change_error) {
        repository.setThemeMode(mode)
    }

    fun setDynamicColor(enabled: Boolean) = run(R.string.settings_theme_change_error) {
        repository.setDynamicColor(enabled)
    }

    fun setNotificationsEnabled(enabled: Boolean) = run(R.string.settings_notifications_update_error) {
        repository.setNotificationsEnabled(enabled)
    }

    fun setNotificationPreview(showPreview: Boolean) = run(R.string.settings_notifications_update_error) {
        repository.setNotificationPreview(showPreview)
    }

    fun signOut() {
        viewModelScope.launch {
            repository.signOut()
                .onSuccess { _signedOut.tryEmit(Unit) }
                .onFailure { _messages.tryEmit(context.getString(R.string.settings_sign_out_error, it.reason())) }
        }
    }

    private fun run(@StringRes failureMessageRes: Int, action: suspend () -> Result<*>) {
        viewModelScope.launch {
            action().onFailure {
                _messages.tryEmit(context.getString(failureMessageRes, it.reason()))
            }
        }
    }

    private fun Throwable.reason(): String = when {
        this is AppError.RateLimited && retryAfterSeconds > 0 ->
            context.resources.passphraseLockedMessage(retryAfterSeconds)
        else -> message ?: context.getString(R.string.settings_unknown_error)
    }
}
