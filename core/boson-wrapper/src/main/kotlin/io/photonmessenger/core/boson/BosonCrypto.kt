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
