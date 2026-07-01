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

package io.photonmessenger.core.network.model

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
 * POST /api/v1/client/auth device sign-in (Director ClientService.clientAuth). A paired device proves
 * possession of its device key over a fresh `nonce` to obtain a CWT, without OAuth. `userId`/`deviceId`
 * are Base58 Boson ids; `nonce`/`deviceSig` are Base64URL-no-pad (the Director's Jackson byte[] codec).
 * See spec 2.4, M6-4. -> {token}.
 */
@Serializable
data class ClientAuthRequest(
    val userId: String,
    val deviceId: String,
    val nonce: String,
    val deviceSig: String,
)

/**
 * POST /api/v1/client/usersAndInitialDevice (self-contained, non-OAuth registration; spec 2.5).
 * `userId`/`deviceId` are Base58 Boson ids; `nonce`/`userSig`/`deviceSig` are Base64 (Jackson decodes
 * a JSON string into byte[] via Base64 on the Director). Used by the headless integration harness.
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
    val nonce: String,
    val userSig: String,
    val deviceSig: String,
)
