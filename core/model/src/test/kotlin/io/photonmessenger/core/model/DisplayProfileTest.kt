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

package io.photonmessenger.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The single identity policy shared by every surface: name preference, avatar/bio, short-id fallback. */
class DisplayProfileTest {

    private val longId = "8Hk3RtqABCDEFGHIJKLMNOP9fQ2"

    @Test
    fun `shortId passes short ids through and abbreviates long ones`() {
        assertEquals("abc", shortId("abc"))
        // head 8 + "..." + tail 4
        assertEquals("8Hk3Rtqa...9fQ2", shortId("8Hk3RtqaXXXXXXXXXXXXX9fQ2"))
    }

    @Test
    fun `local name wins over the resolved name`() {
        val resolved = ResolvedProfile("u", name = "Resolved", bio = null, avatarUrl = null)
        val d = resolved.toDisplay("u", localName = "Alias")
        assertEquals("Alias", d.displayName)
        assertFalse(d.nameIsFallback)
    }

    @Test
    fun `resolved name is used when there is no local name`() {
        val resolved = ResolvedProfile("u", name = "Resolved", bio = "hi", avatarUrl = "http://a/u")
        val d = resolved.toDisplay("u", localName = null)
        assertEquals("Resolved", d.displayName)
        assertEquals("hi", d.bio)
        assertEquals("http://a/u", d.avatarUrl)
        assertFalse(d.nameIsFallback)
    }

    @Test
    fun `falls back to the short id and flags it when no name is known`() {
        val d = (null as ResolvedProfile?).toDisplay(longId, localName = null)
        assertEquals(shortId(longId), d.displayName)
        assertTrue(d.nameIsFallback)
        assertNull(d.avatarUrl)
        assertNull(d.bio)
    }

    @Test
    fun `blank local and resolved names are ignored`() {
        val resolved = ResolvedProfile("u", name = "  ", bio = null, avatarUrl = null)
        val d = resolved.toDisplay(longId, localName = "   ")
        assertEquals(shortId(longId), d.displayName)
        assertTrue(d.nameIsFallback)
    }

    @Test
    fun `avatar comes from the resolved profile even when a local name wins`() {
        val resolved = ResolvedProfile("u", name = "Resolved", bio = null, avatarUrl = "http://a/u")
        val d = resolved.toDisplay("u", localName = "Alias")
        assertEquals("Alias", d.displayName)
        assertEquals("http://a/u", d.avatarUrl)
    }
}
