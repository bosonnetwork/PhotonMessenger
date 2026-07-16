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

import io.bosonnetwork.photon.core.security.SecretStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Device-key ownership + rotation semantics (device registration management): a device key must
 * never be reused across identity changes.
 */
class KeyManagerTest {

    /** In-memory stand-in for the EncryptedSharedPreferences-backed store. */
    private fun inMemorySecrets(): SecretStore {
        val map = mutableMapOf<String, ByteArray>()
        val store = mockk<SecretStore>()
        every { store.getBytes(any()) } answers { map[firstArg()] }
        every { store.putBytes(any(), any()) } answers {
            val value = secondArg<ByteArray?>()
            if (value == null) map.remove(firstArg())
            else map[firstArg<String>()] = value
        }
        every { store.remove(any()) } answers { map.remove(firstArg<String>()) }
        return store
    }

    private fun keyId(kp: io.bosonnetwork.crypto.Signature.KeyPair): String =
        BosonCrypto.idOf(kp).toString()

    @Test
    fun `ensureDeviceKey is stable across calls`() {
        val km = KeyManager(inMemorySecrets())
        assertEquals(keyId(km.ensureDeviceKey()), keyId(km.ensureDeviceKey()))
    }

    @Test
    fun `deviceId is null until a device key exists, then matches the device key`() {
        val km = KeyManager(inMemorySecrets())
        assertNull(km.deviceId())
        val key = km.ensureDeviceKey()
        assertEquals(keyId(key), km.deviceId()?.toString())
    }

    @Test
    fun `rotateDeviceKey produces a fresh key and clears the owner`() {
        val km = KeyManager(inMemorySecrets())
        val before = keyId(km.ensureDeviceKey())
        km.setDeviceKeyOwner("USER-A")

        val after = keyId(km.rotateDeviceKey())

        assertNotEquals(before, after)
        assertNull(km.deviceKeyOwner())
        assertEquals(after, keyId(km.ensureDeviceKey())) // rotated key is persisted
    }

    @Test
    fun `ensureDeviceKeyFor keeps an unregistered key`() {
        val km = KeyManager(inMemorySecrets())
        val original = keyId(km.ensureDeviceKey())

        assertEquals(original, keyId(km.ensureDeviceKeyFor("USER-A")))
        assertEquals(original, keyId(km.ensureDeviceKeyFor(null)))
    }

    @Test
    fun `ensureDeviceKeyFor keeps the key for the same owner`() {
        val km = KeyManager(inMemorySecrets())
        val original = keyId(km.ensureDeviceKey())
        km.setDeviceKeyOwner("USER-A")

        assertEquals(original, keyId(km.ensureDeviceKeyFor("USER-A")))
        assertEquals("USER-A", km.deviceKeyOwner())
    }

    @Test
    fun `ensureDeviceKeyFor rotates on identity change`() {
        val km = KeyManager(inMemorySecrets())
        val original = keyId(km.ensureDeviceKey())
        km.setDeviceKeyOwner("USER-A")

        val rotated = keyId(km.ensureDeviceKeyFor("USER-B"))

        assertNotEquals(original, rotated)
        assertNull(km.deviceKeyOwner())
    }

    @Test
    fun `ensureDeviceKeyFor rotates a registered key when the adopting identity is unknown`() {
        val km = KeyManager(inMemorySecrets())
        val original = keyId(km.ensureDeviceKey())
        km.setDeviceKeyOwner("USER-A")

        assertNotEquals(original, keyId(km.ensureDeviceKeyFor(null)))
    }

    @Test
    fun `clear removes user key, device key, and owner`() {
        val km = KeyManager(inMemorySecrets())
        km.generateUserKey()
        km.ensureDeviceKey()
        km.setDeviceKeyOwner("USER-A")

        km.clear()

        assertNull(km.userKeyPair())
        assertNull(km.deviceKeyPair())
        assertNull(km.deviceKeyOwner())
    }
}
