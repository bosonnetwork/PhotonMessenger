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

package io.photonmessenger.feature.chat.model

/**
 * Media attachment model + the carrier/wire conventions shared by send and receive (spec 1.5, 4.6,
 * M5). Two carriers are supported:
 *
 *  - inline: small payloads sent directly in the message body via contentBinary, with
 *    Content-Disposition: inline. The bytes travel with the message (no IonStore round-trip).
 *  - IonStore: the payload is uploaded to IonStore and the message body carries a small
 *    [AttachmentRef] (CBOR object) with Content-Disposition: attachment. Recipients fetch the
 *    bytes permissionlessly via IonStore using the ref.
 *
 * The receiver discriminates purely on the Content-Disposition header: an attachment disposition
 * means the body is an [AttachmentRef] map; an inline disposition (with a non-text content type)
 * means the body is the raw bytes. Plain text messages carry no disposition at all.
 */

/** Broad rendering category derived from the MIME type. */
enum class AttachmentKind { IMAGE, FILE }

/** Where the attachment bytes live. */
sealed interface AttachmentSource {
    /** Bytes carried inline in the message body. */
    data class Inline(val bytes: ByteArray) : AttachmentSource {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Inline && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Bytes stored in IonStore; [uri] is `ions://<peerId>/<refId>`, [contentId] is the SHA-256. */
    data class Remote(val uri: String, val contentId: String) : AttachmentSource

    /**
     * A locally-picked content uri, shown optimistically while the attachment is still being sent
     * (before it is confirmed on the live stream). [uri] is an Android `content://` uri that Coil can
     * render directly. A Local attachment is not yet persisted or referenceable, so it is neither
     * forwardable nor saveable until it settles into an [Inline] or [Remote] confirmed message.
     */
    data class Local(val uri: String) : AttachmentSource
}

/** UI projection of a single attachment on a message bubble. */
data class UiAttachment(
    val kind: AttachmentKind,
    val mime: String,
    val name: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val source: AttachmentSource,
)

/** The carrier a given attachment should be sent over. */
enum class AttachmentCarrier { INLINE, ION_STORE }

/**
 * Picks the carrier: only small images go inline; everything else (larger images, files, video)
 * goes to IonStore. The inline target is kept well under the 32 KB MQTT payload cap (spec 4.6).
 */
fun chooseCarrier(kind: AttachmentKind, size: Long): AttachmentCarrier =
    if (kind == AttachmentKind.IMAGE && size <= INLINE_MAX_BYTES) AttachmentCarrier.INLINE
    else AttachmentCarrier.ION_STORE

/** Inline payload ceiling (~20 KB), safely below the MQTT 32 KB envelope cap. */
const val INLINE_MAX_BYTES: Long = 20 * 1024

fun kindOf(mime: String): AttachmentKind =
    if (mime.startsWith("image/")) AttachmentKind.IMAGE else AttachmentKind.FILE

/** Wire keys for the IonStore [AttachmentRef] CBOR object. Kept short for compact CBOR. */
object AttachmentRefKeys {
    const val URI = "uri"
    const val CONTENT_ID = "cid"
    const val MIME = "mime"
    const val SIZE = "size"
    const val NAME = "name"
    const val WIDTH = "w"
    const val HEIGHT = "h"
}

/** Encodes a remote attachment as the CBOR object map sent in the message body. */
fun remoteAttachmentToMap(
    uri: String,
    contentId: String,
    mime: String,
    size: Long,
    name: String,
    width: Int?,
    height: Int?,
): Map<String, Any> = buildMap {
    put(AttachmentRefKeys.URI, uri)
    put(AttachmentRefKeys.CONTENT_ID, contentId)
    put(AttachmentRefKeys.MIME, mime)
    put(AttachmentRefKeys.SIZE, size)
    put(AttachmentRefKeys.NAME, name)
    width?.let { put(AttachmentRefKeys.WIDTH, it) }
    height?.let { put(AttachmentRefKeys.HEIGHT, it) }
}

/** Decodes the CBOR object map from a received message body into a [UiAttachment]. */
fun remoteAttachmentFromMap(map: Map<*, *>): UiAttachment? {
    val uri = map[AttachmentRefKeys.URI] as? String ?: return null
    val contentId = map[AttachmentRefKeys.CONTENT_ID] as? String ?: return null
    val mime = map[AttachmentRefKeys.MIME] as? String ?: "application/octet-stream"
    val size = (map[AttachmentRefKeys.SIZE] as? Number)?.toLong() ?: 0L
    val name = map[AttachmentRefKeys.NAME] as? String ?: "attachment"
    val width = (map[AttachmentRefKeys.WIDTH] as? Number)?.toInt()
    val height = (map[AttachmentRefKeys.HEIGHT] as? Number)?.toInt()
    return UiAttachment(
        kind = kindOf(mime),
        mime = mime,
        name = name,
        size = size,
        width = width,
        height = height,
        source = AttachmentSource.Remote(uri, contentId),
    )
}
