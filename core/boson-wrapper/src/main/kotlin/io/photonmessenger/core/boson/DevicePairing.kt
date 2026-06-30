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

/**
 * Zero-knowledge user-key transfer for multi-device pairing (spec 2.4, decision D-1, M6-4).
 *
 * The new device generates an ephemeral Curve25519 key pair and publishes its public key in a QR.
 * The existing device seals the 64-byte user private key to that public key using a libsodium sealed
 * box (anonymous sender), so the Director - which only relays the opaque blob - never sees the key and
 * cannot identify the sender. Only the new device, holding the matching ephemeral private key, can open
 * it. The ephemeral key pair lives only for the duration of one pairing.
 */
object DevicePairing {
    /** A fresh ephemeral Curve25519 key pair for one pairing session. */
    fun generateEphemeralKeyPair(): CryptoBox.KeyPair = CryptoBox.KeyPair.random()

    /** The 32-byte Curve25519 public key bytes (what the new device shows in its QR). */
    fun publicKeyBytes(keyPair: CryptoBox.KeyPair): ByteArray = keyPair.publicKey().bytes()

    /**
     * Seals [userPrivateKey64] (the libsodium 64-byte user key) to the new device's ephemeral public
     * key. Called on the existing (approving) device; the result is relayed opaquely by the Director.
     */
    fun sealUserKey(userPrivateKey64: ByteArray, recipientPublicKey: ByteArray): ByteArray =
        CryptoBox.encryptSealed(userPrivateKey64, CryptoBox.PublicKey.fromBytes(recipientPublicKey))

    /**
     * Opens a sealed user-key blob with the ephemeral key pair generated for this pairing. Called on
     * the new device after the Director returns the relayed blob.
     *
     * @throws io.bosonnetwork.crypto.CryptoException if the blob is corrupt or not sealed to this key
     */
    fun openUserKey(sealed: ByteArray, ephemeralKeyPair: CryptoBox.KeyPair): ByteArray =
        CryptoBox.decryptSealed(sealed, ephemeralKeyPair.publicKey(), ephemeralKeyPair.privateKey())
}
