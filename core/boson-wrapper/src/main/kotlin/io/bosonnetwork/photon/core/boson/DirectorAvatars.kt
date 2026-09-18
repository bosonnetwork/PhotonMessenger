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

package io.bosonnetwork.photon.core.boson

import android.net.Uri
import io.bosonnetwork.Id

/**
 * Avatar image models. An avatar is loaded through the Director client, which authenticates the request -
 * the Director fetches another node's user's avatar only for a signed-in user of its own - so the image
 * loader is handed this app-private URI rather than an HTTP URL: `director-avatar://<userId>[?v=<n>]`.
 * The optional version changes the URI, and so the image cache key, when the avatar is known to have
 * changed.
 */
object DirectorAvatars {
    const val SCHEME = "director-avatar"

    /** The avatar model for [userId]; pass [version] (e.g. the profile's update time) to bust caches. */
    fun uri(userId: String, version: Long? = null): String =
        "$SCHEME://$userId" + (version?.let { "?v=$it" } ?: "")

    /** The user whose avatar [uri] names, or null when it is not an avatar model. */
    fun userIdOf(uri: Uri): Id? {
        if (uri.scheme != SCHEME) return null
        val userId = uri.host?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Id.of(userId) }.getOrNull()
    }
}
