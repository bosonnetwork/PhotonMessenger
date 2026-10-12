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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** The Keystore sealer on a real device: what the JVM tests stand in for. */
@RunWith(AndroidJUnit4::class)
class KeystoreSealerTest {
    private val alias = "photon.identity.test-" + System.nanoTime()
    private val sealer = KeystoreSealer()
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, alias)

    @After
    fun tearDown() {
        sealer.delete(alias)
        dir.deleteRecursively()
    }

    @Test
    fun aKeySealedOpensUnderItsAliasOnly() {
        val key = ByteArray(64) { it.toByte() }
        val sealed = sealer.seal(alias, key)
        assertFalse(sealed.contains(android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)))
        assertArrayEquals(key, sealer.open(alias, sealed))

        sealer.delete(alias)
        assertThrows(Exception::class.java) { sealer.open(alias, sealed) }
    }

    @Test
    fun aVaultKeepsItsKeysAcrossInstances() {
        val key = ByteArray(64) { (it * 3).toByte() }
        IdentityVault(dir, alias, sealer).setUserKey(key, "alice")
        assertArrayEquals(key, IdentityVault(dir, alias, KeystoreSealer()).userKey())
    }
}
