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

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.utils.Base58

/**
 * Ed25519 key + signing helpers built on Boson's own `Signature` so key formats stay byte-compatible
 * with `Configuration.userKey/deviceKey` and the Director (M1-5/M1-6).
 *
 * Private keys are handled in the libsodium-style 64-byte form (seed || publicKey), per project
 * convention. The Director identity-binding flow (spec 2.2) requires signing the raw, base58-decoded
 * nonce bytes, then base58-encoding the 32-byte public key and 64-byte signature; `userId = Id.of(pk)`.
 */
object BosonCrypto {
    fun generateKeyPair(): Signature.KeyPair = Signature.KeyPair.random()

    fun keyPairFromPrivate64(privateKey64: ByteArray): Signature.KeyPair =
        Signature.KeyPair.fromPrivateKey(privateKey64)

    /** 64-byte libsodium-style private key (seed || publicKey). */
    fun privateKeyBytes64(kp: Signature.KeyPair): ByteArray = kp.privateKey().bytes()

    /** Base58-encoded 32-byte Ed25519 public key. */
    fun publicKeyBase58(kp: Signature.KeyPair): String = Base58.encode(kp.publicKey().bytes())

    /** Raw Ed25519 signature over [message]. */
    fun sign(kp: Signature.KeyPair, message: ByteArray): ByteArray = Signature.sign(message, kp.privateKey())

    /** The Boson user/device id derived from the public key. */
    fun idOf(kp: Signature.KeyPair): Id = Id.of(kp.publicKey().bytes())

    /** Base58 of the 64-byte private key (seed || publicKey), for QR/paste identity transfer (O4/O5). */
    fun privateKey64ToBase58(privateKey64: ByteArray): String = Base58.encode(privateKey64)

    /**
     * Decodes a pasted or scanned raw user private key. Accepts base58 or hex (optional `0x` prefix).
     * Returns the validated 64-byte libsodium form (seed || publicKey); throws
     * [IllegalArgumentException] if it is not a usable 64-byte Ed25519 private key.
     */
    fun decodePrivateKey64(text: String): ByteArray {
        val cleaned = text.trim()
        require(cleaned.isNotEmpty()) { "Empty key" }
        val bytes = parseKeyBytes(cleaned)
        require(bytes.size == 64) { "A user key must be 64 bytes (got ${bytes.size})" }
        // Validate the bytes form a real Ed25519 keypair (throws if seed/publicKey are inconsistent).
        Signature.KeyPair.fromPrivateKey(bytes)
        return bytes
    }

    private const val HEX_DIGITS = "0123456789abcdefABCDEF"

    // A 64-byte key is 128 hex chars; base58 of 64 bytes is ~88 chars, so length disambiguates cleanly.
    private fun parseKeyBytes(text: String): ByteArray {
        val hex = text.removePrefix("0x").removePrefix("0X")
        val looksHex = hex.length == 128 && hex.all { it in HEX_DIGITS }
        return if (looksHex) {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } else {
            Base58.decode(text)
        }
    }

    /**
     * Signs the Director-issued binding nonce. The nonce arrives base58-encoded; the signature must
     * cover the decoded raw bytes (Director verifies `Signature.verify(nonceBytes, sig, pk)`).
     *
     * @return the base58-encoded 64-byte signature
     */
    fun signNonceBase58(kp: Signature.KeyPair, nonceBase58: String): String {
        val nonceBytes = Base58.decode(nonceBase58)
        val signature = Signature.sign(nonceBytes, kp.privateKey())
        return Base58.encode(signature)
    }
}
