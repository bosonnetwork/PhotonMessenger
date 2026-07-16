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

import io.bosonnetwork.photon.core.security.KeyStoreManager
import io.bosonnetwork.photon.core.security.SecretStore
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature

/**
 * Generates and persists the user and device Ed25519 keypairs (libsodium 64-byte form), wrapped at
 * rest by [SecretStore] (Android Keystore). The device key is created on first launch; the user key
 * is created during identity binding (spec 2.2-2.3, M1-5).
 *
 * Keys are returned as Boson `Signature.KeyPair` so they feed `Configuration.userKey/deviceKey` and
 * IonStore directly.
 */
class KeyManager(
    private val secrets: SecretStore,
) : KeyStoreManager {

    fun userKeyPair(): Signature.KeyPair? =
        secrets.getBytes(KEY_USER)?.let { BosonCrypto.keyPairFromPrivate64(it) }

    fun deviceKeyPair(): Signature.KeyPair? =
        secrets.getBytes(KEY_DEVICE)?.let { BosonCrypto.keyPairFromPrivate64(it) }

    fun userId(): Id? = userKeyPair()?.let { BosonCrypto.idOf(it) }

    /** The device id (derived from the device key), or null if no device key exists yet. */
    fun deviceId(): Id? = deviceKeyPair()?.let { BosonCrypto.idOf(it) }

    /** Creates and persists a fresh user keypair (identity binding). Overwrites any existing one. */
    fun generateUserKey(): Signature.KeyPair =
        BosonCrypto.generateKeyPair().also { secrets.putBytes(KEY_USER, BosonCrypto.privateKeyBytes64(it)) }

    /**
     * Persists a user keypair received from another device during multi-device pairing (spec 2.4,
     * M6-4). [privateKey64] is the libsodium 64-byte (seed || publicKey) form. Overwrites any existing.
     */
    fun storeUserKey(privateKey64: ByteArray): Signature.KeyPair =
        BosonCrypto.keyPairFromPrivate64(privateKey64).also { secrets.putBytes(KEY_USER, privateKey64) }

    /** Returns the device keypair, generating and persisting one on first use. */
    fun ensureDeviceKey(): Signature.KeyPair =
        deviceKeyPair() ?: BosonCrypto.generateKeyPair()
            .also { secrets.putBytes(KEY_DEVICE, BosonCrypto.privateKeyBytes64(it)) }

    /**
     * The base58 user id this device key was last REGISTERED under (with the Director), or null if
     * it has never been registered. Set after a successful registration; used to detect identity
     * changes so a device key is never reused across identities.
     */
    fun deviceKeyOwner(): String? = secrets.getBytes(KEY_DEVICE_OWNER)?.decodeToString()

    /** Records the identity the device key is now registered under (call after successful registration). */
    fun setDeviceKeyOwner(userId: String) {
        secrets.putBytes(KEY_DEVICE_OWNER, userId.encodeToByteArray())
    }

    /** Generates and persists a FRESH device keypair, clearing any recorded owner. */
    fun rotateDeviceKey(): Signature.KeyPair {
        secrets.remove(KEY_DEVICE_OWNER)
        return BosonCrypto.generateKeyPair()
            .also { secrets.putBytes(KEY_DEVICE, BosonCrypto.privateKeyBytes64(it)) }
    }

    /**
     * Returns a device key safe to register under [newUserId]: rotates first when the current key
     * was registered under a DIFFERENT identity. [newUserId] == null means the adopting identity is
     * not yet known (pairing invite), so any previously-registered key must be rotated too.
     */
    fun ensureDeviceKeyFor(newUserId: String?): Signature.KeyPair {
        val owner = deviceKeyOwner()
        return when {
            owner == null -> ensureDeviceKey()      // never registered: safe to (re)use
            owner == newUserId -> ensureDeviceKey() // same identity: keep
            else -> rotateDeviceKey()               // identity change (or unknown): rotate
        }
    }

    override fun hasUserKey(): Boolean = secrets.getBytes(KEY_USER) != null

    override fun clear() {
        secrets.remove(KEY_USER)
        secrets.remove(KEY_DEVICE)
        secrets.remove(KEY_DEVICE_OWNER)
    }

    private companion object {
        const val KEY_USER = "user_key_64"
        const val KEY_DEVICE = "device_key_64"
        const val KEY_DEVICE_OWNER = "device_key_owner"
    }
}
