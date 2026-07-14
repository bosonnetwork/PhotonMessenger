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

package io.bosonnetwork.photon.core.network.model

import kotlinx.serialization.Serializable

/*
 * Multi-device registration (spec 2.4, M6-4). Boson ids (deviceId/userId) are Base58; byte[] fields
 * (nonce/sig/userPrivateKey) are Base64URL-no-pad strings - the Director's Jackson endpoints decode
 * byte[] with the URL-safe variant. See [[reference_director_wire_encoding]].
 */

/** POST /api/v1/client/devices/registrations (auth-less): the new device requests registration. */
@Serializable
data class RegisterDeviceRequest(
    val deviceId: String,
    val deviceName: String,
    val appName: String,
    val nonce: String,
    val sig: String,
)

/** POST .../devices/registrations -> {registrationId} (the pending request handle, shown in the QR). */
@Serializable
data class RegistrationIdDto(
    val registrationId: String,
)

/** GET .../devices/registrations/{id} (approver, authenticated): what the new device is asking for. */
@Serializable
data class RegistrationInfoDto(
    val deviceId: String,
    val deviceName: String? = null,
    val appName: String? = null,
)

/**
 * PATCH .../devices/registrations/{id} (approver, authenticated): approve or deny. On approval the
 * existing device relays the user key as [userPrivateKey] (sealed to the new device's ephemeral key,
 * so it is opaque to the Director). [passphrase] is only needed for legacy passphrase accounts; OAuth
 * accounts approve on the authenticated session alone (Director D-1 change).
 */
@Serializable
data class ReplyRegistrationRequest(
    val approved: Boolean,
    val passphrase: String? = null,
    val userPrivateKey: String? = null,
)

/** POST .../devices/registrations/{id} (auth-less): the new device finishes once approved. */
@Serializable
data class FinishRegistrationRequest(
    val deviceId: String,
    val nonce: String,
    val sig: String,
)

/** POST .../devices/registrations/{id} -> {userId, userPrivateKey} (the relayed sealed blob). */
@Serializable
data class FinishRegistrationResponse(
    val userId: String,
    val userPrivateKey: String,
)
