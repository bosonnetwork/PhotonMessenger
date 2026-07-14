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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DevicePairingTest {

    @Test
    fun `sealed user key round-trips to the matching ephemeral key`() {
        val userKey = BosonCrypto.privateKeyBytes64(BosonCrypto.generateKeyPair())
        val ephemeral = DevicePairing.generateEphemeralKeyPair()

        val sealed = DevicePairing.sealUserKey(userKey, DevicePairing.publicKeyBytes(ephemeral))
        val opened = DevicePairing.openUserKey(sealed, ephemeral)

        assertArrayEquals(userKey, opened)
    }

    @Test
    fun `a different ephemeral key cannot open the sealed blob`() {
        val userKey = BosonCrypto.privateKeyBytes64(BosonCrypto.generateKeyPair())
        val recipient = DevicePairing.generateEphemeralKeyPair()
        val attacker = DevicePairing.generateEphemeralKeyPair()

        val sealed = DevicePairing.sealUserKey(userKey, DevicePairing.publicKeyBytes(recipient))

        assertThrows(Exception::class.java) { DevicePairing.openUserKey(sealed, attacker) }
    }

    @Test
    fun `pairing payload round-trips through its QR encoding`() {
        val ephemeral = DevicePairing.generateEphemeralKeyPair()
        val payload = PairingPayload("reg-123", DevicePairing.publicKeyBytes(ephemeral))

        val decoded = PairingPayload.decode(payload.encode())

        assertEquals(payload, decoded)
    }

    @Test
    fun `malformed pairing payloads decode to null`() {
        assertNull(PairingPayload.decode("not-a-pairing-code"))
        assertNull(PairingPayload.decode("pmpair:1:reg-only"))
        assertNull(PairingPayload.decode("pmpair:2:reg:AAAA")) // wrong version
        assertNull(PairingPayload.decode("pmpair:1:reg:!!!notbase64")) // bad key
        assertNull(PairingPayload.decode("pmpair:1::AAAA")) // empty id
    }
}
