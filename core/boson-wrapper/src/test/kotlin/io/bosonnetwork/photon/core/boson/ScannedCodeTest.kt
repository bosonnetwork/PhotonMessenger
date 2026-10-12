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
import io.bosonnetwork.crypto.KeyBackup
import io.bosonnetwork.crypto.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannedCodeTest {
    private val id = Id.random()
    private val key32 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun `super node codes are recognized, in the Boson string and the older JSON`() {
        val code = ScannedCode.classify("boson:supernode:1:$id:https://node.example.org")
        assertTrue(code is ScannedCode.SuperNode)
        assertEquals("https://node.example.org", (code as ScannedCode.SuperNode).code.url)
        assertTrue(ScannedCode.classify("""{"url": "https://node.example.org"}""") is ScannedCode.SuperNode)
    }

    @Test
    fun `pairing codes are recognized, in the Boson string and the older form`() {
        assertTrue(ScannedCode.classify(" boson:pair:1:$id:$key32 ") is ScannedCode.Pairing)
        assertTrue(ScannedCode.classify("bosonpair:1:$id:$key32") is ScannedCode.Pairing)
    }

    @Test
    fun `user ids are recognized as a DID, Base58 or hex`() {
        assertEquals(ScannedCode.UserId(id), ScannedCode.classify("did:boson:$id"))
        assertEquals(ScannedCode.UserId(id), ScannedCode.classify(id.toString()))
        assertEquals(ScannedCode.UserId(id), ScannedCode.classify(id.toHexString()))
    }

    @Test
    fun `a private key is recognized in every backup form, and never taken for an id`() {
        val keyPair = Signature.KeyPair.random()
        val base58 = KeyBackup.encode(keyPair.privateKey().bytes())
        assertEquals(ScannedCode.PrivateKey, ScannedCode.classify(base58))
        assertEquals(ScannedCode.PrivateKey, ScannedCode.classify(KeyBackup.gridLines(base58).joinToString("\n")))
        assertEquals(ScannedCode.PrivateKey, ScannedCode.classify(KeyBackup.keyFileText(base58, id.toString())))
    }

    @Test
    fun `Boson Identity codes are told apart from codes Photon has no use for`() {
        assertEquals(ScannedCode.ForBosonIdentity("move"), ScannedCode.classify("boson:move:1:c2FsdA:ZGF0YQ"))
        assertEquals(ScannedCode.ForBosonIdentity("enroll"), ScannedCode.classify("boson:enroll:1:$id:$id:https://node.example.org"))
        assertEquals(ScannedCode.ForBosonIdentity("signin"), ScannedCode.classify("boson:signin:1:$id"))
        assertEquals(ScannedCode.OtherBosonCode("certbind"), ScannedCode.classify("boson:certbind:1:a:b"))
    }

    @Test
    fun `anything else is unknown, including a malformed known code`() {
        assertEquals(ScannedCode.Unknown, ScannedCode.classify(""))
        assertEquals(ScannedCode.Unknown, ScannedCode.classify("https://example.org"))
        assertEquals(ScannedCode.Unknown, ScannedCode.classify("boson:supernode:1:not-an-id"))
        assertEquals(ScannedCode.Unknown, ScannedCode.classify("boson:pair:1:$id"))
    }
}
