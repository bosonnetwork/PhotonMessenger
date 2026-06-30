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

package io.photonmessenger.feature.settings.model

/** The signed-in user's profile (spec 2.6). [avatarUrl] is a fetchable HTTPS URL, null when unset. */
data class UiProfile(
    val id: String,
    val name: String,
    val bio: String,
    val email: String,
    val avatarUrl: String?,
    val plan: String?,
)

/**
 * A registered device joined with its live messaging session (spec screen 6, M6-2). The name/app come
 * from the Director device registry; online/lastActive/lastAddress come from the live session (if any).
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
