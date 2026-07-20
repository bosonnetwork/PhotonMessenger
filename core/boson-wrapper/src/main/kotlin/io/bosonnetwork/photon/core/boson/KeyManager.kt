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
     * The base58 id of the super node this device is currently REGISTERED with, or null if it has
     * never been registered (or the key was rotated since). Set after a successful registration; used
     * to detect a node change and, by its presence, whether the current device key is registered.
     */
    fun registeredNodeId(): String? = secrets.getBytes(KEY_REG_NODE)?.decodeToString()

    /** Records (or clears, when null) the super node this device is registered with. */
    fun setRegisteredNodeId(nodeId: String?) {
        if (nodeId == null) secrets.remove(KEY_REG_NODE)
        else secrets.putBytes(KEY_REG_NODE, nodeId.encodeToByteArray())
    }

    /** Generates and persists a FRESH device keypair; a rotated key is by definition not registered. */
    fun rotateDeviceKey(): Signature.KeyPair {
        secrets.remove(KEY_REG_NODE)
        return BosonCrypto.generateKeyPair()
            .also { secrets.putBytes(KEY_DEVICE, BosonCrypto.privateKeyBytes64(it)) }
    }

    /**
     * Returns a device key safe to register under [newUserId]: rotates first when the current key is
     * already registered under a DIFFERENT identity. A device key is owned by exactly one user (the
     * current user key), so "different identity" is derived from the current user key rather than a
     * stored owner. [newUserId] == null means the adopting identity is not yet known (pairing invite),
     * so any previously-registered key must be rotated too.
     */
    fun ensureDeviceKeyFor(newUserId: String?): Signature.KeyPair = when {
        registeredNodeId() == null -> ensureDeviceKey()        // never registered: safe to (re)use
        userId()?.toString() == newUserId -> ensureDeviceKey() // same identity: keep
        else -> rotateDeviceKey()                              // different (or unknown) identity: rotate
    }

    override fun hasUserKey(): Boolean = secrets.getBytes(KEY_USER) != null

    override fun clear() {
        secrets.remove(KEY_USER)
        secrets.remove(KEY_DEVICE)
        secrets.remove(KEY_REG_NODE)
    }

    companion object {
        private const val KEY_USER = "user_key_64"
        private const val KEY_DEVICE = "device_key_64"
        private const val KEY_REG_NODE = "reg_node_id"

        /**
         * Durably writes identity material into an ARBITRARY profile's [secrets] store, for seeding a
         * target profile just before an app relaunch (multi-profile handoff of a self-sovereign identity
         * whose key the target profile does not yet hold). Mirrors the token seeding in
         * [io.bosonnetwork.photon.core.security.EncryptedAuthTokenStore.seedDurably]. [devicePrivateKey64]
         * and [registeredNodeId] are seeded only when non-null: a fresh device that must register itself
         * on the target passes null for both so no stale registration marker is carried over.
         */
        fun seedInto(
            secrets: SecretStore,
            userPrivateKey64: ByteArray,
            devicePrivateKey64: ByteArray?,
            registeredNodeId: String?,
        ) {
            secrets.putBytes(KEY_USER, userPrivateKey64, commit = true)
            devicePrivateKey64?.let { secrets.putBytes(KEY_DEVICE, it, commit = true) }
            registeredNodeId?.let { secrets.putBytes(KEY_REG_NODE, it.encodeToByteArray(), commit = true) }
        }

        /**
         * Reads the 64-byte user private key from an ARBITRARY profile's [secrets] store (null when that
         * profile has no identity yet). Used to mint a fresh session from a reused profile's own key.
         */
        fun readUserKey(secrets: SecretStore): ByteArray? = secrets.getBytes(KEY_USER)
    }
}
