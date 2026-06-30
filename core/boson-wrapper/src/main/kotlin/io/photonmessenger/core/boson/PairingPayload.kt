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

package io.photonmessenger.core.boson

import io.bosonnetwork.crypto.CryptoBox
import java.util.Base64

/**
 * The content of the pairing QR shown by a new device (spec 2.4, M6-4): the Director registration id
 * plus the device's ephemeral Curve25519 public key. Encoded as a compact, scheme-prefixed string so a
 * scan can be unambiguously recognized as a PhotonMessenger pairing code.
 */
class PairingPayload(
    val registrationId: String,
    val ephemeralPublicKey: ByteArray,
) {
    fun encode(): String =
        "$SCHEME:$VERSION:$registrationId:${B64URL.encodeToString(ephemeralPublicKey)}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingPayload) return false
        return registrationId == other.registrationId &&
            ephemeralPublicKey.contentEquals(other.ephemeralPublicKey)
    }

    override fun hashCode(): Int =
        31 * registrationId.hashCode() + ephemeralPublicKey.contentHashCode()

    companion object {
        private const val SCHEME = "pmpair"
        private const val VERSION = "1"
        private val B64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val B64URL_DEC: Base64.Decoder = Base64.getUrlDecoder()

        /** Parses a scanned QR string, or returns null when it is not a valid v1 pairing code. */
        fun decode(text: String): PairingPayload? {
            val parts = text.trim().split(':', limit = 4)
            if (parts.size != 4) return null
            if (parts[0] != SCHEME || parts[1] != VERSION) return null
            val registrationId = parts[2]
            if (registrationId.isEmpty()) return null
            val key = runCatching { B64URL_DEC.decode(parts[3]) }.getOrNull() ?: return null
            if (key.size != CryptoBox.PublicKey.BYTES) return null
            return PairingPayload(registrationId, key)
        }
    }
}
