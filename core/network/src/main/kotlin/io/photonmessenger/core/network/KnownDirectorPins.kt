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

package io.photonmessenger.core.network

import java.net.URI

/**
 * Static registry of SHA-256 SPKI certificate pins per Director host (X-S4 / M1-4).
 *
 * The Director is reached over standard HTTPS (unlike the Boson messaging/ion-store services, which
 * are identity-pinned by peerId through the Boson TLS trust manager - see the security review). To
 * defend the Director channel against a rogue/compromised CA, production hosts are pinned to the
 * SHA-256 hash of their SubjectPublicKeyInfo.
 *
 * HOW TO POPULATE (do this once a production TLS Director endpoint exists):
 *   1. Obtain the leaf (and ideally an intermediate/backup) SPKI pin:
 *        openssl s_client -connect director.example.com:443 -servername director.example.com < /dev/null \
 *          | openssl x509 -pubkey -noout \
 *          | openssl pkey -pubin -outform der \
 *          | openssl dgst -sha256 -binary | openssl enc -base64
 *   2. Add an entry below: "director.example.com" to listOf("sha256/<primary>", "sha256/<backup>").
 *      ALWAYS include at least one backup pin (a spare key or the issuing CA) so key rotation does
 *      not brick installed clients.
 *
 * The dev default host is cleartext HTTP (D-2), so it is intentionally absent - pins are only
 * meaningful, and only applied, over HTTPS.
 */
object KnownDirectorPins {
    private val pinsByHost: Map<String, List<String>> = emptyMap()

    /** Pins configured for the host of [baseUrl], or empty if none / the URL is malformed. */
    fun pinsFor(baseUrl: String): List<String> {
        val host = runCatching { URI(baseUrl).host }.getOrNull() ?: return emptyList()
        return pinsByHost[host].orEmpty()
    }
}
