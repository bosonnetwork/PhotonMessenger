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

package io.photonmessenger.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.photonmessenger.core.model.NotificationPreferences
import io.photonmessenger.core.model.ThemeMode
import io.photonmessenger.core.model.ThemePreferences
import io.photonmessenger.feature.settings.data.SettingsRepository
import io.photonmessenger.feature.settings.model.UiProfile
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

    fun saveProfile(name: String, bio: String, email: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(savingProfile = true)
            repository.updateProfile(name = name, bio = bio, email = email)
                .onFailure { _messages.tryEmit("Couldn't save profile: ${it.reason()}") }
            _uiState.value = _uiState.value.copy(savingProfile = false)
            refresh()
        }
    }

    fun updateAvatar(uriString: String) = run("Couldn't update photo") {
        repository.updateAvatar(uriString).also { if (it.isSuccess) refresh() }
    }

    fun removeAvatar() = run("Couldn't remove photo") {
        repository.removeAvatar().also { if (it.isSuccess) refresh() }
    }

    fun setThemeMode(mode: ThemeMode) = run("Couldn't change theme") { repository.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = run("Couldn't change theme") {
        repository.setDynamicColor(enabled)
    }

    fun setNotificationsEnabled(enabled: Boolean) = run("Couldn't update notifications") {
        repository.setNotificationsEnabled(enabled)
    }

    fun setNotificationPreview(showPreview: Boolean) = run("Couldn't update notifications") {
        repository.setNotificationPreview(showPreview)
    }

    fun signOut() {
        viewModelScope.launch {
            repository.signOut()
                .onSuccess { _signedOut.tryEmit(Unit) }
                .onFailure { _messages.tryEmit("Couldn't sign out: ${it.reason()}") }
        }
    }

    private fun run(failurePrefix: String, action: suspend () -> Result<*>) {
        viewModelScope.launch {
            action().onFailure { _messages.tryEmit("$failurePrefix: ${it.reason()}") }
        }
    }

    private fun Throwable.reason() = message ?: "unknown error"
}
