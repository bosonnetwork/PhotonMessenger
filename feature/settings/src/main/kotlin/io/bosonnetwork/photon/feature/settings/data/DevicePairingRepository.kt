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
import io.bosonnetwork.director.client.DeviceRegistration
import io.bosonnetwork.director.client.PairingCode
import io.bosonnetwork.director.client.exceptions.NotFoundException
import io.bosonnetwork.director.client.exceptions.RegistrationDeniedException
import io.bosonnetwork.director.client.exceptions.RegistrationExpiredException
import io.bosonnetwork.photon.core.boson.DirectorClients
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.boson.ScannedCode
import io.bosonnetwork.photon.core.boson.misplacedMessage
import io.bosonnetwork.photon.core.boson.toDirectorError
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.feature.settings.R
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/** What the approving (existing) device reads about a scanned pairing request before confirming. */
data class PairingRequestInfo(
    val registrationId: String,
    val deviceId: String,
    val deviceName: String,
    val appName: String,
)

/**
 * Approving a new device (spec 2.4, decision D-1, M6-4), over the Director client's pairing, which owns
 * the protocol - the pairing code, and the user key sealed to it - so that any Boson app can pair with
 * any other: [readRequest] reads what a scanned code is asking for; [approve] hands the user key over,
 * sealed to the code (zero-knowledge to the Director); [deny] rejects the request. A device joins in
 * onboarding (AuthRepository's pairing).
 *
 * Photon keeps the QR scanning, and the messages it shows.
 */
interface DevicePairingRepository {
    suspend fun readRequest(qrText: String): Result<PairingRequestInfo>

    /**
     * Approves a pending request, sealing the user key to the new device. [passphrase] is required only
     * when the approving account has a passphrase configured; a wrong/missing one fails with
     * [AppError.Forbidden]/[AppError.PassphraseRequired].
     */
    suspend fun approve(qrText: String, passphrase: String?): Result<Unit>
    suspend fun deny(qrText: String): Result<Unit>
}

@Singleton
class DevicePairingRepositoryImpl @Inject constructor(
    private val directorClients: DirectorClients,
    private val keyManager: KeyManager,
    @ApplicationContext private val context: Context,
) : DevicePairingRepository {

    override suspend fun readRequest(qrText: String): Result<PairingRequestInfo> = runCatching {
        val code = decode(qrText)
        val info = directorClients.client().getDeviceRegistration(code).await().orElse(null)
            ?: throw AppError.NotFound(context.getString(R.string.settings_pairing_error_not_pending))
        PairingRequestInfo(
            registrationId = code.registrationId,
            deviceId = info.deviceId.toString(),
            deviceName = info.deviceName.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.settings_default_device_name),
            appName = info.appName.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.settings_about_app_name),
        )
    }.mapPairingError()

    override suspend fun approve(qrText: String, passphrase: String?): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val code = decode(qrText)
            if (keyManager.userKeyPair() == null)
                throw AppError.InvalidInput(context.getString(R.string.settings_pairing_error_no_identity))
            // The client seals this profile's user key to the code: the Director relays it unread.
            directorClients.client().approveDeviceRegistration(code, passphrase).await()
            Unit
        }
    }.mapPairingError()

    override suspend fun deny(qrText: String): Result<Unit> = runCatching {
        directorClients.client().denyDeviceRegistration(decode(qrText)).await()
        Unit
    }.mapPairingError()

    // A code of another kind is named, so that a node or contact code scanned here says what it is.
    private fun decode(qrText: String): PairingCode =
        when (val code = ScannedCode.classify(qrText)) {
            is ScannedCode.Pairing -> code.code
            ScannedCode.Unknown -> throw AppError.InvalidInput(context.getString(R.string.settings_pairing_error_invalid_code))
            else -> throw AppError.InvalidInput(code.misplacedMessage(context))
        }

    /**
     * Turns a Director failure into an [AppError] the UI can show: the pairing outcomes the user has to
     * act on get their own message; the rest map as every Director failure does (428/403 passphrase).
     */
    private fun <T> Result<T>.mapPairingError(): Result<T> =
        recoverCatching { e ->
            throw when (e) {
                is RegistrationDeniedException ->
                    AppError.Conflict(context.getString(R.string.settings_pairing_error_denied), e)
                is RegistrationExpiredException ->
                    AppError.Timeout(context.getString(R.string.settings_pairing_error_expired), e)
                is NotFoundException ->
                    AppError.NotFound(context.getString(R.string.settings_pairing_error_not_pending), e)
                else -> e.toDirectorError()
            }
        }
}
