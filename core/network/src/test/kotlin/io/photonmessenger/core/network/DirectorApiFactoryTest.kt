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

package io.photonmessenger.core.network

import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorApiFactoryTest {

    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test
    fun `no pinner when no pins are configured`() {
        val config = DirectorConfig(baseUrl = "https://director.example.com")
        assertNull(DirectorApiFactory.certificatePinner(config))
    }

    @Test
    fun `no pinner over cleartext even when pins are configured`() {
        // Pinning a cleartext host is a silent no-op, so we refuse it rather than imply protection.
        val config = DirectorConfig(baseUrl = "http://10.0.2.2:9000", certificatePins = listOf(pin))
        assertNull(DirectorApiFactory.certificatePinner(config))
    }

    @Test
    fun `pins the configured https host`() {
        val config = DirectorConfig(baseUrl = "https://director.example.com", certificatePins = listOf(pin))
        val pinner = DirectorApiFactory.certificatePinner(config)
        assertTrue(pinner != null)
        // With pins in effect for the host, an empty (non-matching) chain must be rejected.
        assertThrows(SSLPeerUnverifiedException::class.java) {
            pinner!!.check("director.example.com", emptyList())
        }
    }

    @Test
    fun `pins do not constrain a different host`() {
        val config = DirectorConfig(baseUrl = "https://director.example.com", certificatePins = listOf(pin))
        val pinner = DirectorApiFactory.certificatePinner(config)!!
        // A host with no configured pins is not constrained, so check() passes.
        pinner.check("evil.example.com", emptyList())
    }

    @Test
    fun `known pins registry is empty for the dev default`() {
        // No production TLS endpoint yet, so nothing is pinned; the dev host is cleartext anyway.
        assertTrue(KnownDirectorPins.pinsFor(DirectorConfigStore.DEFAULT_DIRECTOR_URL).isEmpty())
    }
}
