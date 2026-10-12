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

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.crypto.KeyBackup
import io.bosonnetwork.photon.core.boson.ScannedCode
import io.bosonnetwork.photon.core.boson.misplacedMessage
import io.bosonnetwork.photon.core.boson.toDirectorError
import io.bosonnetwork.photon.core.designsystem.component.passphraseLockedMessage
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.feature.onboarding.data.AuthRepository
import io.bosonnetwork.photon.feature.onboarding.data.ProfileSeed
import io.bosonnetwork.photon.feature.onboarding.data.SessionState
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The steps of bringing an identity into Photon, identity first, as Boson Identity does it:
 * - a new identity: [NewIdentity] > [BackupKey] > [CheckBackup] > [Server] > [CreateProfile] > [Solving];
 * - an existing one: [Server] > [ChooseWay] > [Pairing] (approved on another device) or [ImportBackup]
 *   (its key file, QR code or text), then [Passphrase] if the account has one.
 */
enum class OnboardingStep {
    Welcome,
    NewIdentity, BackupKey, CheckBackup,
    Server, ScanServer,
    CreateProfile, Solving,
    ChooseWay, Pairing, ImportBackup, ScanBackup, Passphrase,
    Authenticated,
}

data class OnboardingUiState(
    val loading: Boolean = false,
    val step: OnboardingStep = OnboardingStep.Welcome,
    /** True on the way of an identity the user already has; false while making a new one. */
    val existing: Boolean = false,
    val serverUrl: String = "",
    /** Director node id used to identity-pin its self-signed cert; blank for a CA-fronted Director. */
    val directorNodeId: String = "",
    /** The new identity's id, while it is being made. */
    val newUserId: String? = null,
    /** The new identity's key, shown on the [OnboardingStep.BackupKey] screen. */
    val backupKeyBase58: String? = null,
    /** The two groups of the key the [OnboardingStep.CheckBackup] screen asks for. */
    val challenge: KeyBackup.Challenge? = null,
    val checkFailed: Boolean = false,
    /** Whether the new identity's backup was checked; if not, Settings reminds the user. */
    val backupChecked: Boolean = false,
    val displayName: String = "",
    val bio: String = "",
    /** The pairing code shown on the [OnboardingStep.Pairing] screen, while waiting for its approval. */
    val pairingCode: String? = null,
    /** The pairing request expired before anyone approved it: a new code can be shown. */
    val pairingExpired: Boolean = false,
    /** The key typed or pasted on the [OnboardingStep.ImportBackup] screen. */
    val backupInput: String = "",
    val passphraseInput: String = "",
    val error: String? = null,
)

/**
 * One-shot request for the app to hand the identity to another profile: [existingProfileId] non-null
 * opens that existing profile; null creates a fresh profile. The app seeds the target with [seed] (the
 * identity material) before relaunching, so it comes up ready.
 */
data class ProfileHandoff(val existingProfileId: String?, val seed: ProfileSeed? = null)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** One-shot: the identity belongs to a different profile; the app should hand off to it. */
    private val _handoff = Channel<ProfileHandoff>(Channel.BUFFERED)
    val handoff = _handoff.receiveAsFlow()

    init {
        authRepository.ensureDeviceKey()
        viewModelScope.launch {
            // Prefill the node step with the current Director URL and pin id.
            _uiState.update {
                it.copy(
                    serverUrl = authRepository.currentDirectorUrl(),
                    directorNodeId = authRepository.currentDirectorNodeId(),
                )
            }
        }
    }

    // ---- Welcome -----------------------------------------------------------------------------------------

    /** Makes a new identity, in memory until a node registers it. */
    fun chooseCreate() {
        val userId = authRepository.newIdentity()
        _uiState.update {
            it.copy(
                step = OnboardingStep.NewIdentity,
                existing = false,
                newUserId = userId,
                backupKeyBase58 = null,
                challenge = null,
                checkFailed = false,
                backupChecked = false,
                displayName = "",
                bio = "",
                error = null,
            )
        }
    }

    /** Brings in an identity the user already has: first its home node. */
    fun chooseExisting() = _uiState.update { it.copy(step = OnboardingStep.Server, existing = true, error = null) }

    // ---- A new identity: back up and check ------------------------------------------------------------

    fun continueToBackup() = _uiState.update {
        it.copy(step = OnboardingStep.BackupKey, backupKeyBase58 = authRepository.pendingIdentityKeyBase58(), error = null)
    }

    /** The user saved the key: ask for two of its groups. */
    fun continueFromBackup() {
        val key = _uiState.value.backupKeyBase58 ?: return
        _uiState.update { it.copy(step = OnboardingStep.CheckBackup, challenge = KeyBackup.challenge(key), checkFailed = false) }
    }

    fun checkBackup(first: String, second: String) {
        val s = _uiState.value
        val key = s.backupKeyBase58 ?: return
        val challenge = s.challenge ?: return
        if (KeyBackup.verify(key, challenge, first, second)) {
            _uiState.update { it.copy(step = OnboardingStep.Server, backupChecked = true, backupKeyBase58 = null, challenge = null) }
        } else {
            _uiState.update { it.copy(checkFailed = true) }
        }
    }

    fun showKeyAgain() = _uiState.update { it.copy(step = OnboardingStep.BackupKey, checkFailed = false) }

    /** Checks the backup later: Settings reminds the user until they do. */
    fun checkLater() = _uiState.update {
        it.copy(step = OnboardingStep.Server, backupChecked = false, backupKeyBase58 = null, challenge = null)
    }

    // ---- The home node -----------------------------------------------------------------------------------

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value) }

    fun onDirectorNodeIdChange(value: String) = _uiState.update { it.copy(directorNodeId = value) }

    /** Opens the camera to scan a super node QR code and prefill the node fields. */
    fun scanServerQr() = _uiState.update {
        lastScanned = null
        it.copy(step = OnboardingStep.ScanServer, error = null)
    }

    fun cancelServerScan() = _uiState.update { it.copy(step = OnboardingStep.Server, error = null) }

    // The scanner re-emits the same payload every frame; only act on the first sighting of each value.
    private var lastScanned: String? = null

    /**
     * Handles a scanned node code (`boson:supernode:1`, or the JSON form read before it): the node
     * fields are filled and the flow returns to the (editable) node step. Any other code leaves the
     * scanner open, saying what it is.
     */
    fun onServerQrScanned(text: String) {
        if (text == lastScanned) return
        lastScanned = text
        when (val code = ScannedCode.classify(text)) {
            is ScannedCode.SuperNode -> _uiState.update {
                it.copy(
                    step = OnboardingStep.Server,
                    serverUrl = code.code.url,
                    directorNodeId = code.code.nodeId.map { id -> id.toBase58String() }.orElse(""),
                    error = null,
                )
            }
            ScannedCode.Unknown -> _uiState.update { it.copy(error = context.getString(R.string.onb_error_invalid_super_node_qr)) }
            else -> _uiState.update { it.copy(error = code.misplacedMessage(context)) }
        }
    }

    /**
     * Saves the node and checks it: for a new identity, that it takes new accounts; for an existing one,
     * that it answers. Then on to the profile, or to the ways in.
     */
    fun confirmServer() {
        val url = _uiState.value.serverUrl.trim()
        if (url.isEmpty()) return
        val nodeId = _uiState.value.directorNodeId.trim().ifBlank { null }
        val existing = _uiState.value.existing
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                authRepository.setDirectorUrl(url, nodeId)
                if (existing) {
                    authRepository.reachNode()
                    _uiState.update { it.copy(loading = false, step = OnboardingStep.ChooseWay) }
                } else if (authRepository.powAvailable()) {
                    _uiState.update { it.copy(loading = false, step = OnboardingStep.CreateProfile) }
                } else {
                    _uiState.update { it.copy(loading = false, error = context.getString(R.string.onb_error_node_no_new_accounts)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = serverErrorMessage(e)) }
            }
        }
    }

    /** Friendly copy for node-connect failures; TLS trust errors point at the node id field. */
    private fun serverErrorMessage(e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is javax.net.ssl.SSLException || cause is java.security.cert.CertificateException) {
                return context.getString(R.string.onb_error_secure_connection)
            }
            cause = cause.cause
        }
        return e.message ?: context.getString(R.string.onb_error_cant_reach_server)
    }

    // ---- A new identity: profile and registration -------------------------------------------------------

    fun onDisplayNameChange(value: String) = _uiState.update { it.copy(displayName = value) }

    fun onBioChange(value: String) = _uiState.update { it.copy(bio = value) }

    /** The running proof-of-work solve, kept so [cancelSolving] can abort a long search. */
    private var solveJob: Job? = null

    /**
     * Registers the new identity on the node by proof of work, off the UI thread on the
     * [OnboardingStep.Solving] screen. The account exists and this device is registered in one call.
     * Failures return to the profile with a retryable message; a cancel is silent.
     */
    fun createAccount() {
        val current = _uiState.value
        _uiState.update { it.copy(loading = false, step = OnboardingStep.Solving, error = null) }
        solveJob = viewModelScope.launch {
            try {
                val state = authRepository.createAccountWithPow(current.displayName, current.bio, current.backupChecked)
                applySession(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        step = OnboardingStep.CreateProfile,
                        error = e.message ?: context.getString(R.string.onb_error_create_account_failed),
                    )
                }
            }
        }
    }

    fun cancelSolving() {
        solveJob?.cancel()
        solveJob = null
        _uiState.update { it.copy(loading = false, step = OnboardingStep.CreateProfile, error = null) }
    }

    // ---- An existing identity: pairing ----------------------------------------------------------------

    private var pairingJob: Job? = null

    /**
     * Shows this device's pairing code and waits for another device that holds the identity (Boson
     * Identity, or Photon) to approve it. Approval registers this device and hands the user key over.
     */
    fun choosePairing() {
        pairingJob?.cancel()
        _uiState.update { it.copy(step = OnboardingStep.Pairing, pairingCode = null, pairingExpired = false, error = null) }
        pairingJob = viewModelScope.launch {
            try {
                val code = authRepository.startPairing(deviceName())
                _uiState.update { it.copy(pairingCode = code) }
                applySession(authRepository.finishPairing())
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.Timeout) {
                _uiState.update { it.copy(pairingCode = null, pairingExpired = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(pairingCode = null, error = e.message ?: context.getString(R.string.onb_error_pairing_failed))
                }
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        authRepository.cancelPairing()
        _uiState.update { it.copy(step = OnboardingStep.ChooseWay, pairingCode = null, pairingExpired = false, error = null) }
    }

    private fun deviceName(): String =
        listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .joinToString(" ")
            .trim()

    // ---- An existing identity: its backup --------------------------------------------------------------

    fun chooseImport() = _uiState.update { it.copy(step = OnboardingStep.ImportBackup, backupInput = "", error = null) }

    fun onBackupInputChange(value: String) = _uiState.update { it.copy(backupInput = value) }

    fun importTyped() = importBackup(_uiState.value.backupInput)

    /** The text of a key file the user opened. */
    fun onBackupFileOpened(text: String) = importBackup(text)

    fun scanBackup() = _uiState.update {
        lastScanned = null
        it.copy(step = OnboardingStep.ScanBackup, error = null)
    }

    fun cancelBackupScan() = _uiState.update { it.copy(step = OnboardingStep.ImportBackup, error = null) }

    /** A scanned key; any other code leaves the scanner open, saying what it is. */
    fun onBackupScanned(text: String) {
        if (_uiState.value.loading || text == lastScanned) return
        lastScanned = text
        when (val code = ScannedCode.classify(text)) {
            ScannedCode.PrivateKey -> importBackup(text)
            ScannedCode.Unknown -> _uiState.update { it.copy(error = context.getString(R.string.onb_error_invalid_key)) }
            else -> _uiState.update { it.copy(error = code.misplacedMessage(context)) }
        }
    }

    private fun importBackup(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                when (val state = authRepository.importBackup(text)) {
                    is SessionState.Authenticated -> finishIdentitySetup()
                    else -> applySession(state)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        step = if (it.step == OnboardingStep.ScanBackup) OnboardingStep.ImportBackup else it.step,
                        error = e.message ?: context.getString(R.string.onb_error_invalid_key),
                    )
                }
            }
        }
    }

    /**
     * After an imported identity is stored, registers this device so it can connect to the messaging
     * service. Adding a device is passphrase-gated: a passphrase-protected account goes to the
     * [OnboardingStep.Passphrase] step first; otherwise straight on, where the connect path registers the
     * device silently.
     */
    private fun finishIdentitySetup() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val protected = try {
                authRepository.isPassphraseProtected()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Couldn't tell: go on, and let the connect path register the device or surface a
                // retryable connection error.
                false
            }
            _uiState.update {
                if (protected) it.copy(loading = false, step = OnboardingStep.Passphrase, passphraseInput = "")
                else it.copy(loading = false, step = OnboardingStep.Authenticated)
            }
        }
    }

    fun onPassphraseInputChange(value: String) = _uiState.update { it.copy(passphraseInput = value) }

    /** Registers this device with the account's passphrase; a wrong one keeps the user on the step. */
    fun submitPassphrase() {
        val passphrase = _uiState.value.passphraseInput
        if (passphrase.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                authRepository.registerDevice(passphrase)
                _uiState.update { it.copy(loading = false, step = OnboardingStep.Authenticated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = passphraseError(e)) }
            }
        }
    }

    private fun passphraseError(e: Throwable): String = when (val error = e.toDirectorError()) {
        is AppError.Forbidden -> context.getString(R.string.onb_error_wrong_passphrase)
        is AppError.RateLimited ->
            if (error.retryAfterSeconds > 0) context.resources.passphraseLockedMessage(error.retryAfterSeconds)
            else error.message ?: context.getString(R.string.onb_error_register_device_failed)
        else -> error.message ?: context.getString(R.string.onb_error_register_device_failed)
    }

    // ---- Where the identity lands ------------------------------------------------------------------------

    private fun applySession(state: SessionState) {
        when (state) {
            // The identity belongs to (or needs) a different profile: hand off to the app to open the
            // right profile and relaunch. Never touch the active profile's key here.
            is SessionState.ReuseProfile -> _handoff.trySend(ProfileHandoff(state.profileId, state.seed))
            is SessionState.NewProfileForIdentity -> _handoff.trySend(ProfileHandoff(null, state.seed))
            is SessionState.Authenticated -> _uiState.update { it.copy(loading = false, step = OnboardingStep.Authenticated) }
        }
    }

    // ---- Back --------------------------------------------------------------------------------------------

    /** Whether the system back gesture goes to a previous step (rather than leaving the app). */
    fun canGoBack(): Boolean = when (_uiState.value.step) {
        OnboardingStep.Welcome, OnboardingStep.Passphrase, OnboardingStep.Authenticated -> false
        else -> true
    }

    /** One step back. */
    fun back() {
        val s = _uiState.value
        when (s.step) {
            OnboardingStep.NewIdentity -> {
                authRepository.dropPendingIdentity()
                _uiState.update { it.copy(step = OnboardingStep.Welcome, newUserId = null, error = null) }
            }
            OnboardingStep.BackupKey -> _uiState.update { it.copy(step = OnboardingStep.NewIdentity, error = null) }
            OnboardingStep.CheckBackup -> showKeyAgain()
            OnboardingStep.Server ->
                if (s.existing) _uiState.update { it.copy(step = OnboardingStep.Welcome, existing = false, error = null) }
                else continueToBackup()
            OnboardingStep.ScanServer -> cancelServerScan()
            OnboardingStep.CreateProfile -> _uiState.update { it.copy(step = OnboardingStep.Server, error = null) }
            OnboardingStep.Solving -> cancelSolving()
            OnboardingStep.ChooseWay -> _uiState.update { it.copy(step = OnboardingStep.Server, error = null) }
            OnboardingStep.Pairing -> cancelPairing()
            OnboardingStep.ImportBackup -> _uiState.update { it.copy(step = OnboardingStep.ChooseWay, error = null) }
            OnboardingStep.ScanBackup -> cancelBackupScan()
            OnboardingStep.Welcome, OnboardingStep.Passphrase, OnboardingStep.Authenticated -> Unit
        }
    }
}
