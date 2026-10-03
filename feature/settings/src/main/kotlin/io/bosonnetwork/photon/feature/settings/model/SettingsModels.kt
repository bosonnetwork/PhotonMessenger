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

package io.bosonnetwork.photon.feature.settings.model

/** The signed-in user's profile (spec 2.6). [avatarUrl] is a fetchable HTTPS URL, null when unset. */
data class UiProfile(
    val id: String,
    val name: String,
    val bio: String,
    val email: String,
    val avatarUrl: String?,
    val plan: String?,
    /** Whether the account is protected by a passphrase (second factor); drives the security UI (M6). */
    val passphraseProtected: Boolean = false,
    /** The ways a forgotten passphrase can be reset; null when the Director did not say. */
    val recovery: UiRecoveryMethods? = null,
)

/**
 * The ways the user can reset a forgotten passphrase without the user key: [linkedAccounts] (OAuth),
 * [passkeys] and unused [recoveryCodes]. The first two work through the web portal, the codes here.
 */
data class UiRecoveryMethods(
    val linkedAccounts: Int,
    val passkeys: Int,
    val recoveryCodes: Int,
) {
    /** Whether any way exists; with none, a forgotten passphrase cannot be reset. */
    val any: Boolean get() = linkedAccounts > 0 || passkeys > 0 || recoveryCodes > 0

    /** Whether the web portal can reset it: with a linked account or a passkey. */
    val viaPortal: Boolean get() = linkedAccounts > 0 || passkeys > 0
}

/**
 * A live messaging session, optionally enriched with its device registration (spec screen 6, M6-2).
 * The list is driven by the messaging service's sessions (each session belongs to a device);
 * online/lastActive/lastAddress come from the session, while name/app/registeredAt are joined in from
 * the Director device registry when available (falling back to a short device id).
 */
data class UiDevice(
    val deviceId: String,
    val name: String,
    val app: String?,
    val online: Boolean,
    val lastActive: Long,
    val lastAddress: String?,
    val registeredAt: Long,
    /** This is the device the app is currently running on; its session cannot be revoked from here. */
    val isCurrent: Boolean,
)

/**
 * A pending device removal that the server gated on the account passphrase (M6). Shared by the
 * sessions view (via the "also remove device" option) and the account-level devices view.
 */
data class PassphrasePrompt(
    val deviceId: String,
    val error: String? = null,
)
