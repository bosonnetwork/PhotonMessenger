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

package io.photonmessenger.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.photonmessenger.core.network.model.ProviderDto
import io.photonmessenger.feature.onboarding.data.AuthCallback
import io.photonmessenger.feature.onboarding.data.AuthDeepLinkBus
import io.photonmessenger.feature.onboarding.data.AuthRepository
import io.photonmessenger.feature.onboarding.data.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { SignIn, NeedsProfile, Authenticated }

data class OnboardingUiState(
    val loading: Boolean = false,
    val providers: List<ProviderDto> = emptyList(),
    val step: OnboardingStep = OnboardingStep.SignIn,
    val displayName: String = "",
    val bio: String = "",
    val error: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    deepLinkBus: AuthDeepLinkBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** One-shot URLs for the screen to open in a Custom Tab. */
    private val _launchAuthUrl = Channel<String>(Channel.BUFFERED)
    val launchAuthUrl = _launchAuthUrl.receiveAsFlow()

    init {
        authRepository.ensureDeviceKey()
        loadProviders()
        viewModelScope.launch {
            deepLinkBus.events.collect { callback ->
                when (callback) {
                    is AuthCallback.Token -> handleToken(callback.token)
                    is AuthCallback.Error -> _uiState.update { it.copy(loading = false, error = callback.message) }
                }
            }
        }
    }

    fun loadProviders() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.providers() }
                .onSuccess { providers -> _uiState.update { it.copy(loading = false, providers = providers) } }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun onProviderSelected(provider: ProviderDto) {
        viewModelScope.launch {
            runCatching { authRepository.authorizeUrl(provider.id) }
                .onSuccess { url -> _launchAuthUrl.send(url) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    private fun handleToken(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.onAuthToken(token) }
                .onSuccess { state ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            step = when (state) {
                                is SessionState.NeedsIdentity -> OnboardingStep.NeedsProfile
                                is SessionState.Authenticated -> OnboardingStep.Authenticated
                            },
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun onDisplayNameChange(value: String) = _uiState.update { it.copy(displayName = value) }

    fun onBioChange(value: String) = _uiState.update { it.copy(bio = value) }

    fun completeProfile() {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.bindIdentity(current.displayName, current.bio) }
                .onSuccess { _uiState.update { it.copy(loading = false, step = OnboardingStep.Authenticated) } }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
        }
    }
}
