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

import kotlinx.serialization.Serializable

/**
 * GET /api/v1/client/profile -> the signed-in user record (Director DefaultClientUser JSON).
 * `id` is a Base58 Boson id; `avatar` is a `bnr://<nodeId>/api/v1/client/avatar/<userId>` URI when
 * set (spec 2.6). Empty optional fields are omitted by the Director.
 */
@Serializable
data class ProfileDto(
    val id: String,
    val name: String? = null,
    val avatar: String? = null,
    val email: String? = null,
    val bio: String? = null,
    val admin: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val planName: String? = null,
    /**
     * Whether the account currently has a passphrase configured (Director computed field; the hash
     * itself is never returned). When true, passphrase-gated operations require the passphrase.
     */
    val passphraseProtected: Boolean = false,
)

/**
 * GET /api/v1/client/profile/{userId} -> the PUBLIC profile of any user (Director UserPublicProfile
 * JSON). All ids are Base58; `avatar` is a `bnr://` URI whose presence signals the user has an
 * avatar (fetch the image via `GET /api/v1/client/avatar/{userId}`). No timestamps are exposed.
 * 404 when the user is unknown; resolving a REMOTE (non-local) user requires the Bearer CWT.
 */
@Serializable
data class UserPublicProfileDto(
    val id: String,
    val name: String? = null,
    val avatar: String? = null,
    val bio: String? = null,
    val homeNode: String? = null,
    val messagingHomePeer: String? = null,
)

/**
 * PUT /api/v1/client/profile body: any subset of {name, bio, email}. Null fields are omitted by the
 * converter (explicitNulls = false), so only provided keys are updated (Director updateProfile).
 * [passphrase] is required only when the account has a passphrase configured, otherwise ignored.
 */
@Serializable
data class UpdateProfileRequest(
    val name: String? = null,
    val bio: String? = null,
    val email: String? = null,
    val passphrase: String? = null,
)

/**
 * GET /api/v1/client/devices -> the persistent device registry (Director DefaultClientDevice JSON).
 * `id`/`userId` are Base58 Boson ids; `app` is the registering app name. `lastSeen` is 0 when never
 * seen. This is the account registry, distinct from live messaging sessions (see SessionInfo, M6-2).
 */
@Serializable
data class DeviceDto(
    val id: String,
    val userId: String? = null,
    val name: String? = null,
    val app: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val lastSeen: Long = 0,
    val lastAddress: String? = null,
)

/** PUT /api/v1/client/avatar -> {uri} (the new `bnr://` avatar URI). */
@Serializable
data class AvatarUriDto(
    val uri: String,
)
