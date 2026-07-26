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

package io.bosonnetwork.photon.feature.chat.model

import io.bosonnetwork.crypto.Random
import io.bosonnetwork.crypto.SecretStream

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
 *
 * Inline bytes are confidential because the message body is: they are covered by the same end-to-end
 * encryption as the text. An IonStore object is not - retrieval is permissionless, so anyone holding
 * the ref could read it. The IonStore carrier therefore encrypts the payload with a one-time
 * [newAttachmentKey] and carries that key in the ref (see [AttachmentRefKeys.KEY]), which travels
 * inside the encrypted body: the service only ever holds ciphertext, and only the conversation can
 * read it.
 */

/** Broad rendering category derived from the MIME type. */
enum class AttachmentKind { IMAGE, FILE, VOICE }

/** Where the attachment bytes live. */
sealed interface AttachmentSource {
    /** Bytes carried inline in the message body. */
    data class Inline(val bytes: ByteArray) : AttachmentSource {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Inline && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * Bytes stored in IonStore; [uri] is `ions://<peerId>/<refId>`, [contentId] is the SHA-256 of the
     * object as stored (the ciphertext, when encrypted).
     *
     * [key] is the one-time secret the payload was encrypted with before upload, or null for an
     * object stored in the clear - which is what a ref written before attachment encryption existed
     * looks like, so those keep downloading unchanged.
     */
    data class Remote(val uri: String, val contentId: String, val key: ByteArray? = null) : AttachmentSource {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Remote && uri == other.uri && contentId == other.contentId &&
                key.contentEquals(other.key))

        override fun hashCode(): Int =
            (uri.hashCode() * 31 + contentId.hashCode()) * 31 + key.contentHashCode()
    }

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
    /** Playback length in milliseconds for a [AttachmentKind.VOICE] note; null otherwise. */
    val durationMs: Long? = null,
    val source: AttachmentSource,
)

/** The carrier a given attachment should be sent over. */
enum class AttachmentCarrier { INLINE, ION_STORE }

/**
 * Picks the carrier. Small images go inline; voice notes go inline up to a larger, voice-specific
 * ceiling (so a full one-minute Opus note travels in the message body); everything else (larger
 * images, files, video) goes to IonStore. Inline ceilings are kept under the client's MQTT message
 * cap (see PhotonMessagingClient); the IonStore branch is only a safety net for oversized voice.
 */
fun chooseCarrier(kind: AttachmentKind, size: Long): AttachmentCarrier = when (kind) {
    AttachmentKind.IMAGE -> if (size <= INLINE_MAX_BYTES) AttachmentCarrier.INLINE else AttachmentCarrier.ION_STORE
    AttachmentKind.VOICE -> if (size <= INLINE_VOICE_MAX_BYTES) AttachmentCarrier.INLINE else AttachmentCarrier.ION_STORE
    AttachmentKind.FILE -> AttachmentCarrier.ION_STORE
}

/** Inline image payload ceiling (~20 KB). */
const val INLINE_MAX_BYTES: Long = 20 * 1024

/**
 * Inline voice payload ceiling (~200 KB). A one-minute Opus note is ~120-150 KB, so voice notes ride
 * inline in practice; the ION_STORE fallback only triggers on a VBR spike beyond this. Stays under
 * the client's 256 KB MQTT message cap with envelope headroom.
 */
const val INLINE_VOICE_MAX_BYTES: Long = 200 * 1024

/** Wire content type for a recorded voice note (Opus in an Ogg container). */
const val VOICE_MIME: String = "audio/ogg; codecs=opus"

/** Message header names for voice metadata carried alongside an inline note. */
object VoiceHeaders {
    /** Playback length in milliseconds (Long). Mirrors [AttachmentRefKeys.DURATION] on the IonStore path. */
    const val DURATION = "dur"
}

fun kindOf(mime: String): AttachmentKind =
    if (mime.startsWith("image/")) AttachmentKind.IMAGE else AttachmentKind.FILE

/**
 * A received attachment is a voice note when it carries a duration and an audio MIME type. This
 * distinguishes a recorded note from an audio *file* shared via the picker (which has no duration).
 */
fun isVoice(mime: String, hasDuration: Boolean): Boolean = hasDuration && mime.startsWith("audio/")

/** Wire keys for the IonStore [AttachmentRef] CBOR object. Kept short for compact CBOR. */
object AttachmentRefKeys {
    const val URI = "uri"
    const val CONTENT_ID = "cid"
    const val MIME = "mime"
    const val SIZE = "size"
    const val NAME = "name"
    const val WIDTH = "w"
    const val HEIGHT = "h"
    const val DURATION = "dur"

    /**
     * The object's one-time encryption key, as CBOR binary. Absent means the object is stored in the
     * clear, so its presence is what tells the receiver to decrypt - which matches how IonStore reads
     * it back: a get must supply a key for an encrypted object and must not supply one otherwise.
     */
    const val KEY = "k"
}

/**
 * A fresh secret for one IonStore upload, sized for the store's stream cipher
 * (`PutRequest.encrypt`/`GetRequest.decrypt` both require exactly [SecretStream.KEY_BYTES]).
 *
 * One key per object, never reused and never derived from the conversation: it is only ever seen by
 * the conversation it is sent in, a compromised key exposes that one attachment, and because the key
 * rides in the ref rather than being recomputed, an object stays readable across channel session-key
 * rotation and when a ref is forwarded to another conversation.
 */
fun newAttachmentKey(): ByteArray = Random.randomBytesSecure(SecretStream.KEY_BYTES)

/** Encodes a remote attachment as the CBOR object map sent in the message body. */
fun remoteAttachmentToMap(
    uri: String,
    contentId: String,
    mime: String,
    size: Long,
    name: String,
    width: Int?,
    height: Int?,
    durationMs: Long? = null,
    key: ByteArray? = null,
): Map<String, Any> = buildMap {
    put(AttachmentRefKeys.URI, uri)
    put(AttachmentRefKeys.CONTENT_ID, contentId)
    put(AttachmentRefKeys.MIME, mime)
    put(AttachmentRefKeys.SIZE, size)
    put(AttachmentRefKeys.NAME, name)
    width?.let { put(AttachmentRefKeys.WIDTH, it) }
    height?.let { put(AttachmentRefKeys.HEIGHT, it) }
    durationMs?.let { put(AttachmentRefKeys.DURATION, it) }
    key?.let { put(AttachmentRefKeys.KEY, it) }
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
    val durationMs = (map[AttachmentRefKeys.DURATION] as? Number)?.toLong()
    // A key of the wrong length would only fail deep inside the download, so drop it here and let the
    // ref read as "not encrypted" - the get then fails with the store's own encrypted/plaintext
    // mismatch, which says what is actually wrong.
    val key = (map[AttachmentRefKeys.KEY] as? ByteArray)?.takeIf { it.size == SecretStream.KEY_BYTES }
    return UiAttachment(
        kind = if (isVoice(mime, durationMs != null)) AttachmentKind.VOICE else kindOf(mime),
        mime = mime,
        name = name,
        size = size,
        width = width,
        height = height,
        durationMs = durationMs,
        source = AttachmentSource.Remote(uri, contentId, key),
    )
}
