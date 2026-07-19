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

package io.bosonnetwork.photon.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.photon.core.network.model.ProviderDto
import io.bosonnetwork.photon.core.network.toDirectorError
import io.bosonnetwork.photon.feature.onboarding.data.AuthCallback
import io.bosonnetwork.photon.feature.onboarding.data.AuthDeepLinkBus
import io.bosonnetwork.photon.feature.onboarding.data.AuthRepository
import io.bosonnetwork.photon.feature.onboarding.data.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep {
    Server, ChooseMethod, ChooseIdentity, CreateProfile, ScanKey, PasteKey, Passphrase, Solving, Authenticated
}

data class OnboardingUiState(
    val loading: Boolean = false,
    val providers: List<ProviderDto> = emptyList(),
    val step: OnboardingStep = OnboardingStep.Server,
    val serverUrl: String = "",
    /** Director node id used to identity-pin its self-signed cert; blank for a CA-fronted Director. */
    val directorNodeId: String = "",
    /** True only when no identity is bound yet, so "Create new identity" is offered (else import only). */
    val allowCreateIdentity: Boolean = false,
    /** True when the node accepts permissionless PoW registration, so "Create a new account" is offered. */
    val powAvailable: Boolean = false,
    /**
     * True when CreateProfile was entered to make a brand-new permissionless account (PoW), so the
     * optional passphrase field is shown; false when binding a profile to an existing OAuth session.
     */
    val creatingNewAccount: Boolean = false,
    val keyInput: String = "",
    val displayName: String = "",
    val bio: String = "",
    /** Optional account passphrase collected during permissionless (PoW) account creation. */
    val createPassphrase: String = "",
    val passphraseInput: String = "",
    val error: String? = null,
)

/**
 * One-shot request for the app to hand the just-authenticated session to another profile:
 * [existingProfileId] non-null opens that existing profile (reuse); null creates a fresh profile and
 * continues onboarding there. Either way the app seeds the target with the current token + server
 * config before relaunching, so it comes up ready (or ready to continue) without a second sign-in.
 */
data class ProfileHandoff(val existingProfileId: String?)

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

    /** One-shot: the signed-in account belongs to a different profile; the app should hand off to it. */
    private val _handoff = Channel<ProfileHandoff>(Channel.BUFFERED)
    val handoff = _handoff.receiveAsFlow()

    init {
        authRepository.ensureDeviceKey()
        viewModelScope.launch {
            if (authRepository.isSignedIn() && !authRepository.hasUserKey()) {
                // Returning user on a fresh device/reinstall: a token exists but no local key. Skip the
                // server + OAuth steps and resolve straight to the identity-acquisition screen (O2/O4).
                resolveExistingSession()
            } else {
                // Prefill the server step with the current/default Director URL + pin id (O1, Mattermost-style).
                _uiState.update {
                    it.copy(
                        serverUrl = authRepository.currentDirectorUrl(),
                        directorNodeId = authRepository.currentDirectorNodeId(),
                    )
                }
            }
        }
        viewModelScope.launch {
            deepLinkBus.events.collect { callback ->
                when (callback) {
                    is AuthCallback.Token -> handleToken(callback.token)
                    is AuthCallback.Error -> _uiState.update { it.copy(loading = false, error = callback.message) }
                }
            }
        }
    }

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value) }

    fun onDirectorNodeIdChange(value: String) = _uiState.update { it.copy(directorNodeId = value) }

    /** Returns to the server step to point at a different Director. */
    fun editServer() = _uiState.update { it.copy(step = OnboardingStep.Server, error = null) }

    /**
     * Saves the entered Director URL, then probes that server for its OAuth providers AND whether it
     * accepts permissionless proof-of-work registration, and advances to the method-choice hub. Both
     * probes run in parallel; PoW is offered as the primary path when available, OAuth as secondary.
     */
    fun confirmServer() {
        val url = _uiState.value.serverUrl.trim()
        if (url.isEmpty()) return
        val nodeId = _uiState.value.directorNodeId.trim().ifBlank { null }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching {
                authRepository.setDirectorUrl(url, nodeId)
                coroutineScope {
                    val providers = async { authRepository.providers() }
                    val pow = async { authRepository.powAvailable() }
                    providers.await() to pow.await()
                }
            }.onSuccess { (providers, powAvailable) ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        providers = providers,
                        powAvailable = powAvailable,
                        step = OnboardingStep.ChooseMethod,
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(loading = false, error = serverErrorMessage(e)) }
            }
        }
    }

    /** From the method hub: create a brand-new permissionless account (collect a profile, then PoW). */
    fun chooseCreateAccount() = _uiState.update {
        it.copy(
            step = OnboardingStep.CreateProfile,
            creatingNewAccount = true,
            displayName = "",
            bio = "",
            createPassphrase = "",
            error = null,
        )
    }

    /** From the method hub: bring an existing identity onto this device by importing its key. */
    fun addFromAnotherDevice() = _uiState.update {
        it.copy(step = OnboardingStep.ChooseIdentity, allowCreateIdentity = false, error = null)
    }

    /** Friendly copy for server-connect failures; TLS trust errors point at the Server ID field. */
    private fun serverErrorMessage(e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is javax.net.ssl.SSLException || cause is java.security.cert.CertificateException) {
                return "Couldn't establish a secure connection. If this server uses a " +
                    "self-signed certificate, enter its Server ID under Advanced options."
            }
            cause = cause.cause
        }
        return e.message ?: "Couldn't reach that server"
    }

    /** Reloads providers for the confirmed server (retry affordance on the sign-in step). */
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
                .onSuccess { state -> applySession(state) }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
        }
    }

    /** Resolves the session for an already-present token (returning user, no local key). */
    private suspend fun resolveExistingSession() {
        _uiState.update { it.copy(loading = true, error = null) }
        runCatching { authRepository.currentSession() }
            .onSuccess { state -> applySession(state) }
            .onFailure { e ->
                // Offline / server unreachable: land on the import screen so the user can still paste a key.
                _uiState.update {
                    it.copy(loading = false, step = OnboardingStep.ChooseIdentity, allowCreateIdentity = false, error = e.message)
                }
            }
    }

    private fun applySession(state: SessionState) {
        when (state) {
            // The identity belongs to (or needs) a different profile: hand off to the app to open the
            // right profile and relaunch. Never touch the active profile's key here.
            is SessionState.ReuseProfile -> _handoff.trySend(ProfileHandoff(state.profileId))
            is SessionState.NewProfileForIdentity -> _handoff.trySend(ProfileHandoff(null))
            is SessionState.NeedsIdentity ->
                _uiState.update { it.copy(loading = false, step = OnboardingStep.ChooseIdentity, allowCreateIdentity = true) }
            is SessionState.NeedsKey ->
                _uiState.update { it.copy(loading = false, step = OnboardingStep.ChooseIdentity, allowCreateIdentity = false) }
            is SessionState.Authenticated ->
                _uiState.update { it.copy(loading = false, step = OnboardingStep.Authenticated) }
        }
    }

    // --- Identity choice (O4) ---

    /** Create a brand-new identity: collect a profile, then generate + bind a fresh key. */
    fun chooseCreateNew() =
        _uiState.update { it.copy(step = OnboardingStep.CreateProfile, creatingNewAccount = false, error = null) }

    fun chooseScanKey() = _uiState.update { it.copy(step = OnboardingStep.ScanKey, error = null) }

    fun choosePasteKey() = _uiState.update { it.copy(step = OnboardingStep.PasteKey, keyInput = "", error = null) }

    /** Back to the create/scan/paste choice from a sub-step. */
    fun backToChoose() = _uiState.update { it.copy(step = OnboardingStep.ChooseIdentity, error = null) }

    fun onKeyInputChange(value: String) = _uiState.update { it.copy(keyInput = value) }

    /** Imports the pasted raw key (base58 or hex). */
    fun importPastedKey() = importKey(_uiState.value.keyInput)

    // The scanner re-emits the same payload every frame; only act on the first sighting of each value.
    private var lastScannedValue: String? = null

    /** Imports a raw key scanned from another device's QR. */
    fun onKeyScanned(text: String) {
        if (_uiState.value.loading || text == lastScannedValue) return
        lastScannedValue = text
        importKey(text)
    }

    private fun importKey(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.importUserKeyText(text) }
                .onSuccess { finishIdentitySetup() }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message ?: "Invalid key") } }
        }
    }

    /**
     * After an identity is acquired on this device (create or import), registers this device so it can
     * connect to the messaging service. Adding a device is passphrase-gated: a passphrase-protected
     * account is routed to the [OnboardingStep.Passphrase] step to collect the passphrase first; a
     * non-protected account skips straight to [OnboardingStep.Authenticated], where the connect path
     * registers the device silently.
     */
    private fun finishIdentitySetup() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.isPassphraseProtected() }
                .onSuccess { protected ->
                    _uiState.update {
                        if (protected) {
                            it.copy(loading = false, step = OnboardingStep.Passphrase, passphraseInput = "")
                        } else {
                            it.copy(loading = false, step = OnboardingStep.Authenticated)
                        }
                    }
                }
                .onFailure {
                    // Couldn't determine passphrase state; proceed and let the connect path register the
                    // device (non-protected) or surface a retryable connection error.
                    _uiState.update { it.copy(loading = false, step = OnboardingStep.Authenticated) }
                }
        }
    }

    fun onPassphraseInputChange(value: String) = _uiState.update { it.copy(passphraseInput = value) }

    /**
     * Registers this device under a passphrase-protected account using the supplied passphrase (adding a
     * device is a passphrase-gated action). On success the device is ready to connect; a wrong passphrase
     * (403) keeps the user on the passphrase step with an error.
     */
    fun submitPassphrase() {
        val passphrase = _uiState.value.passphraseInput
        if (passphrase.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.registerDevice(passphrase) }
                .onSuccess { _uiState.update { it.copy(loading = false, step = OnboardingStep.Authenticated) } }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(loading = false, error = e.toDirectorError().message ?: "Couldn't register this device")
                    }
                }
        }
    }

    fun onDisplayNameChange(value: String) = _uiState.update { it.copy(displayName = value) }

    fun onBioChange(value: String) = _uiState.update { it.copy(bio = value) }

    fun onCreatePassphraseChange(value: String) = _uiState.update { it.copy(createPassphrase = value) }

    /**
     * Finalizes the CreateProfile step. With an OAuth session present the profile is bound to that
     * account (bind path); without one it is a permissionless account creation via proof-of-work.
     */
    fun completeProfile() {
        if (authRepository.isSignedIn()) bindProfileIdentity() else createAccount()
    }

    private fun bindProfileIdentity() {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { authRepository.bindIdentity(current.displayName, current.bio) }
                .onSuccess { finishIdentitySetup() }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
        }
    }

    /** The running PoW solve, kept so [cancelSolving] can abort a long/expensive search. */
    private var solveJob: Job? = null

    /**
     * Permissionless account creation: runs the memory-hard proof-of-work off the UI thread on the
     * [OnboardingStep.Solving] screen. On success the account exists and this device is registered
     * (one call), so the flow jumps straight to Authenticated. Failures return to CreateProfile with a
     * retryable message; a cancel is silent (the UI already reset).
     */
    private fun createAccount() {
        val current = _uiState.value
        _uiState.update { it.copy(loading = false, step = OnboardingStep.Solving, error = null) }
        solveJob = viewModelScope.launch {
            try {
                authRepository.createAccountWithPow(current.displayName, current.bio, current.createPassphrase)
                _uiState.update { it.copy(step = OnboardingStep.Authenticated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        step = OnboardingStep.CreateProfile,
                        error = e.message ?: "Couldn't create your account; please try again",
                    )
                }
            }
        }
    }

    /** Aborts an in-progress PoW solve and returns to the profile step. */
    fun cancelSolving() {
        solveJob?.cancel()
        solveJob = null
        _uiState.update { it.copy(loading = false, step = OnboardingStep.CreateProfile, error = null) }
    }
}
