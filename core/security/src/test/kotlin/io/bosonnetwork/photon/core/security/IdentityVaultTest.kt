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

package io.bosonnetwork.photon.core.security

import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityVaultTest {
    private val dir: File = Files.createTempDirectory("vault").toFile()

    /** Stands in for the Keystore: "seals" by marking and reversing, so a test can tell sealed from plain. */
    private class FakeSealer : KeySealer {
        val deleted = mutableListOf<String>()
        override fun seal(alias: String, plain: ByteArray): String =
            alias + ":" + Base64.getEncoder().encodeToString(plain.reversedArray())
        override fun open(alias: String, sealed: String): ByteArray {
            check(sealed.startsWith("$alias:")) { "Sealed under another key" }
            return Base64.getDecoder().decode(sealed.substringAfter(':')).reversedArray()
        }
        override fun delete(alias: String) { deleted += alias }
    }

    private val sealer = FakeSealer()
    private val key = ByteArray(64) { it.toByte() }
    private val deviceKey = ByteArray(64) { (it + 100).toByte() }

    private fun vault() = IdentityVault(dir, "photon.identity.p1", sealer)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `an identity is kept, keys sealed, and read back by a new instance`() {
        val vault = vault()
        assertFalse(vault.exists())
        vault.setUserKey(key, "alice")
        vault.setDeviceKey(deviceKey, "phone")
        vault.update { it.copy(homeNode = HomeNode("https://node.example", "node1"), registeredNodeId = "node1", cardVersion = 3) }

        val again = vault()
        assertArrayEquals(key, again.userKey())
        assertArrayEquals(deviceKey, again.deviceKey())
        assertEquals(
            IdentityInfo("alice", "phone", HomeNode("https://node.example", "node1"), "node1", false, 3),
            again.info.value,
        )
        // The file holds the keys only sealed.
        val text = File(dir, IdentityVault.FILE_NAME).readText()
        assertFalse(text.contains(Base64.getEncoder().encodeToString(key)))
        assertTrue(text.contains("photon.identity.p1:"))
    }

    @Test
    fun `another profile's record is read without its keys`() {
        vault().apply {
            setUserKey(key, "alice")
            update { it.copy(homeNode = HomeNode("https://node.example")) }
        }
        val info = IdentityVault.readInfo(dir)!!
        assertEquals("alice", info.userId)
        assertEquals("https://node.example", info.homeNode?.url)
        assertNull(IdentityVault.readInfo(Files.createTempDirectory("empty").toFile()))
    }

    @Test
    fun `forgetting the identity keeps the home node`() {
        val vault = vault()
        vault.setUserKey(key, "alice")
        vault.setDeviceKey(deviceKey, "phone")
        vault.update { it.copy(homeNode = HomeNode("https://node.example"), backupPending = true) }
        vault.clear()
        assertNull(vault.userKey())
        assertNull(vault.deviceKey())
        assertEquals(IdentityInfo(homeNode = HomeNode("https://node.example")), vault().info.value)
    }

    @Test
    fun `a whole identity is stored at once, for a profile seeded before a relaunch`() {
        vault().store(IdentityInfo(userId = "alice", deviceId = "phone", registeredNodeId = "node1"), key, deviceKey)
        val again = vault()
        assertArrayEquals(key, again.userKey())
        assertEquals("node1", again.info.value.registeredNodeId)
        assertFalse(File(dir, "${IdentityVault.FILE_NAME}.tmp").exists())
    }
}
