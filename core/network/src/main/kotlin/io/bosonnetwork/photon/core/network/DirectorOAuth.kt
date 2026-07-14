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

package io.bosonnetwork.photon.core.network

import android.net.Uri

/**
 * Builds the OAuth authorize URL opened in a Custom Tab (NOT a WebView - spec 4.8). The Director
 * redirects to the provider, then back to `redirectUri` with `?token=...` (or `?error=...`), which
 * the app captures as a deep link (spec 2.1, M1-7/M1-8).
 */
object DirectorOAuth {
    const val REDIRECT_URI = "io.bosonnetwork.photon://auth"
    const val DEFAULT_SCOPE = "client"

    /** GET /api/v1/auth/oauth/{provider}/authorize?redirect_uri=...&scope=... */
    fun authorizeUrl(
        config: DirectorConfig,
        provider: String,
        redirectUri: String = REDIRECT_URI,
        scope: String = DEFAULT_SCOPE,
    ): String =
        Uri.parse("${config.authPrefix}/oauth/$provider/authorize")
            .buildUpon()
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", scope)
            .build()
            .toString()
}
