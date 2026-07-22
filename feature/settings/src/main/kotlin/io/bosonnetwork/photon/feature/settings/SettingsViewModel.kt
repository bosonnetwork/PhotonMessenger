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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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

    init {
        refresh()
    }

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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(savingProfile = true)
            repository.updateProfile(name = name, bio = bio, email = email, passphrase = passphrase)
                .onFailure {
                    _messages.tryEmit(context.getString(R.string.settings_profile_save_error, it.reason()))
                }
            _uiState.value = _uiState.value.copy(savingProfile = false)
            refresh()
        }
    }

    /** Sets the first passphrase or changes an existing one (pass [currentPassphrase] when one is set). */
    fun setPassphrase(newPassphrase: String, currentPassphrase: String?) {
        viewModelScope.launch {
            repository.setPassphrase(newPassphrase, currentPassphrase)
                .onSuccess {
                    _passphraseUpdated.tryEmit(Unit)
                    _messages.tryEmit(context.getString(R.string.settings_passphrase_saved_message))
                    refresh()
                }
                .onFailure {
                    _messages.tryEmit(context.getString(R.string.settings_passphrase_set_error, it.reason()))
                }
        }
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

    fun updateAvatar(uriString: String) = run(R.string.settings_photo_update_error) {
        repository.updateAvatar(uriString).also { if (it.isSuccess) refresh() }
    }

    fun removeAvatar() = run(R.string.settings_photo_remove_error) {
        repository.removeAvatar().also { if (it.isSuccess) refresh() }
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

    private fun Throwable.reason() = message ?: context.getString(R.string.settings_unknown_error)
}
