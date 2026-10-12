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

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.photon.core.security.HomeNode
import io.bosonnetwork.photon.core.security.IdentityInfo
import io.bosonnetwork.photon.core.security.IdentityVault
import io.bosonnetwork.photon.core.security.KeyStoreManager

/**
 * The profile's user and device Ed25519 key pairs (libsodium 64-byte form) and what goes with them,
 * kept in the profile's [IdentityVault]: sealed by the Android Keystore, in one record with the
 * identity's registration, backup and card state. The device key is made on first use; the user key
 * when the identity is made or brought in.
 *
 * Keys are returned as Boson `Signature.KeyPair` so they feed the messaging and IonStore clients
 * directly.
 */
class KeyManager(
    private val vault: IdentityVault,
) : KeyStoreManager {

    fun userKeyPair(): Signature.KeyPair? = vault.userKey()?.let { BosonCrypto.keyPairFromPrivate64(it) }

    fun deviceKeyPair(): Signature.KeyPair? = vault.deviceKey()?.let { BosonCrypto.keyPairFromPrivate64(it) }

    fun userId(): Id? = vault.info.value.userId?.let { Id.of(it) }

    /** The device id (derived from the device key), or null if no device key exists yet. */
    fun deviceId(): Id? = vault.info.value.deviceId?.let { Id.of(it) }

    /** Creates and stores a fresh user key pair. Replaces any existing one. */
    fun generateUserKey(): Signature.KeyPair = storeUserKey(BosonCrypto.privateKeyBytes64(BosonCrypto.generateKeyPair()))

    /**
     * Stores a user key received from another device or a backup. [privateKey64] is the libsodium
     * 64-byte (seed || publicKey) form. Replaces any existing one.
     */
    fun storeUserKey(privateKey64: ByteArray): Signature.KeyPair =
        BosonCrypto.keyPairFromPrivate64(privateKey64).also { vault.setUserKey(privateKey64, BosonCrypto.idOf(it).toString()) }

    /** Stores [privateKey64] (64-byte) as this profile's device key, as a pairing registered it. */
    fun storeDeviceKey(privateKey64: ByteArray): Signature.KeyPair =
        BosonCrypto.keyPairFromPrivate64(privateKey64).also { vault.setDeviceKey(privateKey64, BosonCrypto.idOf(it).toString()) }

    /** Returns the device key pair, making and storing one on first use. */
    fun ensureDeviceKey(): Signature.KeyPair =
        deviceKeyPair() ?: storeDeviceKey(BosonCrypto.privateKeyBytes64(BosonCrypto.generateKeyPair()))

    /**
     * The base58 id of the super node this device is currently REGISTERED with, or null if it has
     * never been registered (or the key was rotated since). Set after a successful registration; used
     * to detect a node change and, by its presence, whether the current device key is registered.
     */
    fun registeredNodeId(): String? = vault.info.value.registeredNodeId

    /** Records (or clears, when null) the super node this device is registered with. */
    fun setRegisteredNodeId(nodeId: String?) = vault.update { it.copy(registeredNodeId = nodeId) }

    /** Makes and stores a FRESH device key pair; a rotated key is by definition not registered. */
    fun rotateDeviceKey(): Signature.KeyPair {
        vault.update { it.copy(registeredNodeId = null) }
        return storeDeviceKey(BosonCrypto.privateKeyBytes64(BosonCrypto.generateKeyPair()))
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

    override fun hasUserKey(): Boolean = vault.info.value.userId != null

    /**
     * True while the backup of an identity created on this device hasn't been checked: the user saved it
     * (or said they would) but didn't yet show they can read it back. Settings reminds them until then.
     * An imported or paired identity has its backup elsewhere and never sets it.
     */
    fun isBackupPending(): Boolean = vault.info.value.backupPending

    /** Marks the backup of this profile's identity as waiting for its check, or as checked. */
    fun setBackupPending(pending: Boolean) = vault.update { it.copy(backupPending = pending) }

    /**
     * The version of the profile card this device last published for the identity, or null when it never
     * did. Kept after the card is unpublished: a later card must still be newer than any version resolvers
     * may remember.
     */
    fun cardVersion(): Int? = vault.info.value.cardVersion

    fun setCardVersion(version: Int) = vault.update { it.copy(cardVersion = version) }

    override fun clear() = vault.clear()

    companion object {
        /**
         * Writes an identity into ANOTHER profile's [vault], seeding it just before an app relaunch (the
         * handoff of an identity the target profile does not hold yet), with the node it uses. The record
         * is on disk when this returns. [devicePrivateKey64] and [registeredNodeId] are given only together:
         * a device that must register itself on the target passes null for both, so that no stale
         * registration is carried over.
         */
        fun seedInto(
            vault: IdentityVault,
            userPrivateKey64: ByteArray,
            devicePrivateKey64: ByteArray?,
            registeredNodeId: String?,
            homeNode: HomeNode?,
            backupPending: Boolean = false,
        ) {
            val userId = BosonCrypto.idOf(BosonCrypto.keyPairFromPrivate64(userPrivateKey64)).toString()
            val deviceId = devicePrivateKey64?.let { BosonCrypto.idOf(BosonCrypto.keyPairFromPrivate64(it)).toString() }
            vault.store(
                IdentityInfo(
                    userId = userId,
                    deviceId = deviceId,
                    homeNode = homeNode,
                    registeredNodeId = registeredNodeId.takeIf { devicePrivateKey64 != null },
                    backupPending = backupPending,
                ),
                userPrivateKey64,
                devicePrivateKey64,
            )
        }
    }
}
