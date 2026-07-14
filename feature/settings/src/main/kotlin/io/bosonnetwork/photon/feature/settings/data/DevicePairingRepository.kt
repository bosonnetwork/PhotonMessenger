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

import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.DevicePairing
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.boson.PairingPayload
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.AuthTokenStore
import io.bosonnetwork.photon.core.network.DeviceRegistration
import io.bosonnetwork.photon.core.network.DeviceRegistrationStore
import io.bosonnetwork.photon.core.network.DirectorApi
import io.bosonnetwork.photon.core.network.DirectorApiFactory
import io.bosonnetwork.photon.core.network.DirectorConfig
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.network.model.ClientAuthRequest
import io.bosonnetwork.photon.core.network.model.FinishRegistrationRequest
import io.bosonnetwork.photon.core.network.model.RegisterDeviceRequest
import io.bosonnetwork.photon.core.network.model.ReplyRegistrationRequest
import io.bosonnetwork.photon.core.network.toDirectorError
import io.bosonnetwork.crypto.CryptoBox
import io.bosonnetwork.crypto.Signature
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

    /** Long-polls until approval; on success stores the received user key + a CWT. Returns the userId. */
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
    private val apiFactory: DirectorApiFactory,
    private val configStore: DirectorConfigStore,
    private val keyManager: KeyManager,
    private val tokenStore: AuthTokenStore,
    private val registrationStore: DeviceRegistrationStore,
) : DevicePairingRepository {

    /** State the new device must keep between showing its QR and finishing the pairing. */
    private class ActivePairing(
        val registrationId: String,
        val deviceKey: Signature.KeyPair,
        val ephemeral: CryptoBox.KeyPair,
    )

    @Volatile
    private var active: ActivePairing? = null

    @Volatile
    private var cachedApi: Pair<String, DirectorApi>? = null

    private suspend fun config(): DirectorConfig = configStore.config.first()

    private suspend fun api(): DirectorApi {
        val cfg = config()
        cachedApi?.let { (url, api) -> if (url == cfg.baseUrl) return api }
        return apiFactory.create(cfg).also { cachedApi = cfg.baseUrl to it }
    }

    override suspend fun createInvite(deviceName: String): Result<PairingInvite> = runCatching {
        withContext(Dispatchers.IO) {
            // The pairing registration binds this device key server-side BEFORE the adopted identity
            // is known, so a key ever registered under a previous identity must be rotated NOW - the
            // paired identity is guaranteed new-or-unknown (never reuse a device key across users).
            val deviceKey = keyManager.ensureDeviceKeyFor(null)
            val deviceId = BosonCrypto.idOf(deviceKey).toString()
            val nonce = newNonce()
            val request = RegisterDeviceRequest(
                deviceId = deviceId,
                deviceName = deviceName.ifBlank { DEFAULT_DEVICE_NAME },
                appName = APP_NAME,
                nonce = b64(nonce),
                sig = b64(BosonCrypto.sign(deviceKey, nonce)),
            )
            val registrationId = api().registerDevice(request).registrationId

            val ephemeral = DevicePairing.generateEphemeralKeyPair()
            active = ActivePairing(registrationId, deviceKey, ephemeral)
            val qr = PairingPayload(registrationId, DevicePairing.publicKeyBytes(ephemeral)).encode()
            PairingInvite(registrationId, qr)
        }
    }

    override suspend fun awaitApproval(): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val pairing = active ?: throw AppError.InvalidInput("No pairing in progress")
            val deviceId = BosonCrypto.idOf(pairing.deviceKey).toString()

            // finishRegistration long-polls server-side until the request is approved/denied/timed out.
            val finishNonce = newNonce()
            val response = api().finishRegistration(
                pairing.registrationId,
                FinishRegistrationRequest(
                    deviceId = deviceId,
                    nonce = b64(finishNonce),
                    sig = b64(BosonCrypto.sign(pairing.deviceKey, finishNonce)),
                ),
            )

            val sealed = b64Decode(response.userPrivateKey)
            val userKey64 = DevicePairing.openUserKey(sealed, pairing.ephemeral)
            keyManager.storeUserKey(userKey64)

            // Device sign-in to obtain this device's CWT now that the key is in place.
            val authNonce = newNonce()
            val token = api().clientAuth(
                ClientAuthRequest(
                    userId = response.userId,
                    deviceId = deviceId,
                    nonce = b64(authNonce),
                    deviceSig = b64(BosonCrypto.sign(pairing.deviceKey, authNonce)),
                ),
            ).token
            tokenStore.setToken(token)

            // Pairing registered this device server-side: record it so the first bring-up after
            // pairing skips re-registration.
            keyManager.setDeviceKeyOwner(response.userId)
            val cfg = config()
            registrationStore.set(
                DeviceRegistration(response.userId, cfg.baseUrl, cfg.nodeId, deviceId),
            )

            active = null
            response.userId
        }
    }

    override suspend fun readRequest(qrText: String): Result<PairingRequestInfo> = runCatching {
        val payload = decodePayload(qrText)
        val info = api().getRegistration(payload.registrationId)
        PairingRequestInfo(
            registrationId = payload.registrationId,
            deviceId = info.deviceId,
            deviceName = info.deviceName?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_NAME,
            appName = info.appName?.takeIf { it.isNotBlank() } ?: APP_NAME,
        )
    }

    override suspend fun approve(qrText: String, passphrase: String?): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val payload = decodePayload(qrText)
            val userKey = keyManager.userKeyPair()
                ?: throw AppError.InvalidInput("No user identity on this device")
            val userKey64 = BosonCrypto.privateKeyBytes64(userKey)
            val sealed = DevicePairing.sealUserKey(userKey64, payload.ephemeralPublicKey)
            api().replyRegistration(
                payload.registrationId,
                ReplyRegistrationRequest(
                    approved = true,
                    passphrase = passphrase,
                    userPrivateKey = b64(sealed),
                ),
            )
            Unit
        }
    }.mapDirectorError()

    override suspend fun deny(qrText: String): Result<Unit> = runCatching {
        val payload = decodePayload(qrText)
        api().replyRegistration(payload.registrationId, ReplyRegistrationRequest(approved = false))
    }

    private fun decodePayload(qrText: String): PairingPayload =
        PairingPayload.decode(qrText) ?: throw AppError.InvalidInput("Not a Photon pairing code")

    private fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also { RANDOM.nextBytes(it) }

    private fun b64(bytes: ByteArray): String = B64URL.encodeToString(bytes)

    private fun b64Decode(text: String): ByteArray = B64URL_DEC.decode(text)

    /** Re-wraps a Director HTTP failure as an [AppError] so the UI can tell 428/403 apart (M6 passphrase). */
    private fun <T> Result<T>.mapDirectorError(): Result<T> =
        recoverCatching { throw it.toDirectorError() }

    private companion object {
        const val APP_NAME = "Photon"
        const val DEFAULT_DEVICE_NAME = "New device"
        const val NONCE_BYTES = 32
        val RANDOM = SecureRandom()
        val B64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val B64URL_DEC: Base64.Decoder = Base64.getUrlDecoder()
    }
}
