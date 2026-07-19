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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /api/v1/auth/providers -> [{id, name}] (Director AuthService.getProviders). */
@Serializable
data class ProviderDto(
    val id: String,
    val name: String,
)

/**
 * GET /api/v1/auth/me (Director AuthService.getMe). `userId` is null pre-bind (new user),
 * present post-bind. `sessionId` is always present. See spec 2.1.
 */
@Serializable
data class MeDto(
    val sessionId: String,
    val userId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val avatar: String? = null,
    val provider: String? = null,
    val admin: Boolean = false,
    val createdAt: Long = 0,
)

/** GET /api/v1/auth/user-identity/nonce -> {nonce}. */
@Serializable
data class NonceDto(
    val nonce: String,
)

/** PUT /api/v1/auth/user-identity body: {publicKey, signature} (Base58). */
@Serializable
data class BindIdentityRequest(
    val publicKey: String,
    val signature: String,
)

/** PUT /api/v1/auth/user-identity -> {userId, token} (post-bind CWT, subject = userId). */
@Serializable
data class BindIdentityResponse(
    val userId: String,
    val token: String,
)

/** POST /api/v1/auth/refresh -> {token}. */
@Serializable
data class TokenDto(
    @SerialName("token") val token: String,
)

/**
 * POST /api/v1/client/auth sign-in (Director ClientService.clientAuth). Two self-sovereign variants
 * share one endpoint, distinguished by which signature is present:
 *  - **Device sign-in:** a paired device proves possession of its device key over a fresh `nonce`
 *    (`deviceId` + `deviceSig`), used by the pairing flow. See spec 2.4, M6-4.
 *  - **User sign-in:** a device that holds the imported user identity key proves possession of it
 *    (`userSig`, no `deviceId`/`deviceSig`), yielding a CLIENT-scoped CWT with no OAuth and no
 *    pre-registered device - the permissionless returning-device path (`ClientService` user branch).
 * Exactly one of `userSig` / `deviceSig` is sent. `userId`/`deviceId` are Base58 Boson ids;
 * `nonce`/`userSig`/`deviceSig` are Base64URL-no-pad (the Director's Jackson byte[] codec). -> {token}.
 */
@Serializable
data class ClientAuthRequest(
    val userId: String,
    val nonce: String,
    val deviceId: String? = null,
    val userSig: String? = null,
    val deviceSig: String? = null,
)

/**
 * POST /api/v1/client/devices (authenticated; Director ClientService.addDevice). Registers THIS device
 * under the signed-in user so the messaging service will authorize its mqtts session - the Director's
 * `authenticateDevice` rejects any device absent from the user's device table. Called after identity is
 * acquired on this device (create / import) since neither the OAuth bind nor a raw-key import registers
 * a device (only the pairing flow and self-registration do). `deviceId` is a Base58 Boson id;
 * `nonce`/`deviceSig` are Base64URL-no-pad (client-generated nonce, device-key signature). -> 201 {token};
 * 409 if the device is already registered (treated as success). `passphrase` only when the account has one.
 */
@Serializable
data class AddDeviceRequest(
    val deviceId: String,
    val deviceName: String,
    val appName: String,
    val nonce: String,
    val deviceSig: String,
    val passphrase: String? = null,
)

/**
 * GET /api/v1/client/users/challenge -> a registration proof-of-work challenge (spec
 * director/docs/RegistrationPoW.md section 5). Byte fields are Base64URL-no-pad. `challenge` and
 * `challengeSig` are opaque - relayed back verbatim on the registration request; `nonce` is decoded to
 * bytes to seed the solver; `n`/`k`/`effort` parameterize the Equihash puzzle and are read from the
 * (authenticated) challenge, never hardcoded. A 404 on this endpoint means the node is OAuth-only.
 */
@Serializable
data class ChallengeDto(
    val challenge: String,
    val challengeSig: String,
    val alg: String,
    val n: Int,
    val k: Int,
    val effort: Int,
    val nonce: String,
    val expiresAt: Long = 0,
)

/**
 * POST /api/v1/client/usersAndInitialDevice (self-contained, non-OAuth registration; spec 2.5).
 * Creates the user AND its initial device in one call, returning {token}. Byte fields are
 * Base64URL-no-pad (the Director's Jackson byte[] codec uses the URL-safe variant both directions).
 *
 * Under a proof-of-work registration policy (`pow`/`either`) the five PoW fields are required and
 * `nonce` is omitted: `challenge`/`challengeSig` are relayed verbatim from [ChallengeDto], `powNonce`
 * and `userSig`/`deviceSig` come from RegistrationPowClient, and `solution` is the Equihash index array.
 * Under the legacy `open` policy the request instead carries `nonce` and nonce-signatures (headless
 * harness only). `userId`/`deviceId` are Base58 Boson ids.
 */
@Serializable
data class SelfRegisterRequest(
    val userId: String,
    /** Optional at signup; when set the account becomes passphrase-protected (Director change). */
    val passphrase: String? = null,
    val userName: String? = null,
    val email: String? = null,
    val bio: String? = null,
    val deviceId: String,
    val deviceName: String,
    val appName: String,
    val userSig: String,
    val deviceSig: String,
    /** Legacy `open`-policy nonce; omitted under a proof-of-work policy. */
    val nonce: String? = null,
    val challenge: String? = null,
    val challengeSig: String? = null,
    val powNonce: String? = null,
    val solution: List<Int>? = null,
)
