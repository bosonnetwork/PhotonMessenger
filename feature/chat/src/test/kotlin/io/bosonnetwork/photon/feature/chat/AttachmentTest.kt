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

package io.bosonnetwork.photon.feature.chat

import io.bosonnetwork.photon.feature.chat.model.AttachmentCarrier
import io.bosonnetwork.photon.feature.chat.model.AttachmentKind
import io.bosonnetwork.photon.feature.chat.model.AttachmentSource
import io.bosonnetwork.photon.feature.chat.model.INLINE_MAX_BYTES
import io.bosonnetwork.photon.feature.chat.model.chooseCarrier
import io.bosonnetwork.photon.feature.chat.model.kindOf
import io.bosonnetwork.photon.feature.chat.model.remoteAttachmentFromMap
import io.bosonnetwork.photon.feature.chat.model.remoteAttachmentToMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentTest {

    @Test
    fun `small images go inline, everything else to IonStore`() {
        assertEquals(AttachmentCarrier.INLINE, chooseCarrier(AttachmentKind.IMAGE, INLINE_MAX_BYTES))
        assertEquals(AttachmentCarrier.ION_STORE, chooseCarrier(AttachmentKind.IMAGE, INLINE_MAX_BYTES + 1))
        // Non-images never go inline, even when tiny.
        assertEquals(AttachmentCarrier.ION_STORE, chooseCarrier(AttachmentKind.FILE, 10))
    }

    @Test
    fun `kind is derived from mime`() {
        assertEquals(AttachmentKind.IMAGE, kindOf("image/jpeg"))
        assertEquals(AttachmentKind.IMAGE, kindOf("image/png"))
        assertEquals(AttachmentKind.FILE, kindOf("application/pdf"))
        assertEquals(AttachmentKind.FILE, kindOf("video/mp4"))
    }

    @Test
    fun `remote attachment map round-trips`() {
        val map = remoteAttachmentToMap(
            uri = "ions://peerABC/refXYZ",
            contentId = "cid123",
            mime = "image/jpeg",
            size = 4096,
            name = "photo.jpg",
            width = 800,
            height = 600,
        )
        val att = remoteAttachmentFromMap(map)!!
        assertEquals(AttachmentKind.IMAGE, att.kind)
        assertEquals("image/jpeg", att.mime)
        assertEquals("photo.jpg", att.name)
        assertEquals(4096L, att.size)
        assertEquals(800, att.width)
        assertEquals(600, att.height)
        assertEquals(AttachmentSource.Remote("ions://peerABC/refXYZ", "cid123"), att.source)
    }

    @Test
    fun `numeric fields decode from boxed Integer (CBOR widening)`() {
        // After a CBOR round-trip, numbers come back as Integer/Long; decoding must tolerate both.
        val map = mapOf(
            "uri" to "ions://p/r",
            "cid" to "c",
            "mime" to "application/pdf",
            "size" to 1234, // Int, not Long
            "name" to "doc.pdf",
        )
        val att = remoteAttachmentFromMap(map)!!
        assertEquals(1234L, att.size)
        assertEquals(AttachmentKind.FILE, att.kind)
        assertNull(att.width)
    }

    @Test
    fun `malformed map yields null`() {
        assertNull(remoteAttachmentFromMap(mapOf("mime" to "image/png")))
    }
}
