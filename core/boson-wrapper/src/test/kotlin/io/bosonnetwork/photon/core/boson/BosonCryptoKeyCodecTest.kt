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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verifies the raw user-key codec used by onboarding import (paste/scan) and settings show-key
 * (O4/O5): base58 and hex both round-trip, and malformed / wrong-length input is rejected.
 */
class BosonCryptoKeyCodecTest {

    private val key64 = BosonCrypto.privateKeyBytes64(BosonCrypto.generateKeyPair())

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `base58 round-trips`() {
        val base58 = BosonCrypto.privateKey64ToBase58(key64)
        assertArrayEquals(key64, BosonCrypto.decodePrivateKey64(base58))
    }

    @Test
    fun `hex round-trips`() {
        assertArrayEquals(key64, BosonCrypto.decodePrivateKey64(hex(key64)))
    }

    @Test
    fun `hex accepts 0x prefix and surrounding whitespace`() {
        assertArrayEquals(key64, BosonCrypto.decodePrivateKey64("  0x${hex(key64)}\n"))
    }

    @Test
    fun `rejects empty input`() {
        assertThrows(IllegalArgumentException::class.java) { BosonCrypto.decodePrivateKey64("   ") }
    }

    @Test
    fun `rejects a wrong-length key`() {
        // Valid base58 but only a few bytes - not the 64-byte form.
        assertThrows(IllegalArgumentException::class.java) { BosonCrypto.decodePrivateKey64("abc") }
        // 32-byte hex (a bare seed) is likewise rejected.
        assertThrows(IllegalArgumentException::class.java) { BosonCrypto.decodePrivateKey64(hex(ByteArray(32))) }
    }
}
