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
import io.bosonnetwork.crypto.CryptoBox
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.DevicePairing
import io.bosonnetwork.photon.core.boson.DirectorClients
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.boson.PairingPayload
import io.bosonnetwork.photon.core.boson.toDirectorError
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.SessionStore
import io.bosonnetwork.photon.feature.settings.R
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/** What a new device shows in its pairing QR plus the handle used to wait for approval (spec 2.4). */
data class PairingInvite(
    val registrationId: String,
    val qrText: String,
)

/** What the approving (existing) device reads about a scanned pairing request before confirming. */
data class PairingRequestInfo(
    val registrationId: String,
    val deviceId: String,
    val deviceName: String,
    val appName: String,
)

/**
 * Multi-device pairing backend (spec 2.4, decision D-1, M6-4). Two roles share one repository:
 *
 *  - **New device:** [createInvite] registers this device's key and returns a QR carrying the
 *    registration id + an ephemeral Curve25519 public key; [awaitApproval] long-polls the Director
 *    until the existing device approves, then opens the sealed user key and signs this device in.
 *  - **Existing device:** [readRequest] fetches what a scanned QR is asking for; [approve] seals the
 *    64-byte user key to the new device's ephemeral key (zero-knowledge to the Director) and uploads
 *    it; [deny] rejects the request.
 *
 * The Director only relays the sealed blob opaquely - it never sees the user key.
 */
interface DevicePairingRepository {
    suspend fun createInvite(deviceName: String): Result<PairingInvite>

    /** Long-polls until approval; on success stores the received user key and signs in. Returns the userId. */
    suspend fun awaitApproval(): Result<String>

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
    private val sessionStore: SessionStore,
    @ApplicationContext private val context: Context,
) : DevicePairingRepository {

    /** State the new device must keep between showing its QR and finishing the pairing. */
    private class ActivePairing(
        val registrationId: String,
        val deviceKey: Signature.KeyPair,
        val ephemeral: CryptoBox.KeyPair,
    )

    @Volatile
    private var active: ActivePairing? = null

    override suspend fun createInvite(deviceName: String): Result<PairingInvite> = runCatching {
        withContext(Dispatchers.IO) {
            // The pairing registration binds this device key server-side BEFORE the adopted identity
            // is known, so a key ever registered under a previous identity must be rotated NOW - the
            // paired identity is guaranteed new-or-unknown (never reuse a device key across users).
            val deviceKey = keyManager.ensureDeviceKeyFor(null)
            val registrationId = directorClients.auth().requestDeviceRegistration(
                deviceKey,
                deviceName.ifBlank { context.getString(R.string.settings_default_device_name) },
                context.getString(R.string.settings_about_app_name),
            ).await()

            val ephemeral = DevicePairing.generateEphemeralKeyPair()
            active = ActivePairing(registrationId, deviceKey, ephemeral)
            val qr = PairingPayload(registrationId, DevicePairing.publicKeyBytes(ephemeral)).encode()
            PairingInvite(registrationId, qr)
        }
    }

    override suspend fun awaitApproval(): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val pairing = active
                ?: throw AppError.InvalidInput(context.getString(R.string.settings_pairing_error_no_active))

            // The Director holds this call until the request is approved, denied or expired.
            val approval = directorClients.auth()
                .finishDeviceRegistration(pairing.deviceKey, pairing.registrationId).await()

            val userKey64 = DevicePairing.openUserKey(approval.userKey, pairing.ephemeral)
            keyManager.storeUserKey(userKey64)
            val userId = approval.userId.toString()
            sessionStore.setSession(userId)

            // Pairing registered this device server-side: record the super node so the first bring-up
            // after pairing skips re-registration (its presence also marks the key as registered).
            keyManager.setRegisteredNodeId(directorClients.client().nodeId.await().toString())

            active = null
            userId
        }
    }

    override suspend fun readRequest(qrText: String): Result<PairingRequestInfo> = runCatching {
        val payload = decodePayload(qrText)
        val info = directorClients.client().getDeviceRegistration(payload.registrationId).await()
        PairingRequestInfo(
            registrationId = payload.registrationId,
            deviceId = info.deviceId.toString(),
            deviceName = info.deviceName.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.settings_default_device_name),
            appName = info.appName.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.settings_about_app_name),
        )
    }

    override suspend fun approve(qrText: String, passphrase: String?): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val payload = decodePayload(qrText)
            val userKey = keyManager.userKeyPair()
                ?: throw AppError.InvalidInput(context.getString(R.string.settings_pairing_error_no_identity))
            val userKey64 = BosonCrypto.privateKeyBytes64(userKey)
            // Sealed to the new device's ephemeral key: the Director relays it without reading it.
            val sealed = DevicePairing.sealUserKey(userKey64, payload.ephemeralPublicKey)
            directorClients.client().approveDeviceRegistration(payload.registrationId, sealed, passphrase).await()
            Unit
        }
    }.mapDirectorError()

    override suspend fun deny(qrText: String): Result<Unit> = runCatching {
        val payload = decodePayload(qrText)
        directorClients.client().denyDeviceRegistration(payload.registrationId).await()
        Unit
    }

    private fun decodePayload(qrText: String): PairingPayload =
        PairingPayload.decode(qrText)
            ?: throw AppError.InvalidInput(context.getString(R.string.settings_pairing_error_invalid_code))

    /** Re-wraps a Director failure as an [AppError] so the UI can tell 428/403 apart (M6 passphrase). */
    private fun <T> Result<T>.mapDirectorError(): Result<T> =
        recoverCatching { throw it.toDirectorError() }
}
