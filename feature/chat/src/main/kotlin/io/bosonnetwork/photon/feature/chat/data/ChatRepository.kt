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

package io.bosonnetwork.photon.feature.chat.data

import android.content.Context
import io.bosonnetwork.photon.core.boson.BosonSessionManager
import io.bosonnetwork.photon.core.boson.awaitResult
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.ConnectionState
import io.bosonnetwork.photon.core.model.DisplayProfile
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.cachedDisplay
import io.bosonnetwork.photon.core.model.shortId
import io.bosonnetwork.photon.feature.chat.R
import io.bosonnetwork.photon.feature.chat.model.AttachmentCarrier
import io.bosonnetwork.photon.feature.chat.model.AttachmentKind
import io.bosonnetwork.photon.feature.chat.model.AttachmentSource
import io.bosonnetwork.photon.feature.chat.model.VOICE_MIME
import io.bosonnetwork.photon.feature.chat.model.VoiceHeaders
import io.bosonnetwork.photon.feature.chat.model.ChatHeader
import io.bosonnetwork.photon.feature.chat.model.UiAttachment
import io.bosonnetwork.photon.feature.chat.model.UiConversation
import io.bosonnetwork.photon.feature.chat.model.UiForwardTarget
import io.bosonnetwork.photon.feature.chat.model.UiMessage
import io.bosonnetwork.photon.feature.chat.model.chooseCarrier
import io.bosonnetwork.photon.feature.chat.model.kindOf
import io.bosonnetwork.photon.feature.chat.model.newAttachmentKey
import io.bosonnetwork.photon.feature.chat.model.remoteAttachmentToMap
import io.bosonnetwork.photon.feature.chat.model.toUi
import io.bosonnetwork.Id
import io.bosonnetwork.ionstore.IonStore
import io.bosonnetwork.photonmessaging.Channel
import io.bosonnetwork.photonmessaging.ContentDisposition
import io.bosonnetwork.photonmessaging.InviteTicket
import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageListener
import io.bosonnetwork.photonmessaging.MessagingClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Conversations + 1:1 messages over the Boson [MessagingClient] (spec screens 3-4, M3). Returns UI
 * models so the ViewModels stay decoupled and unit-testable.
 */
interface ChatRepository {
    fun conversations(): Flow<List<UiConversation>>

    /** Resolves the chat header (title + channel member count) for a conversation (M4-3). */
    suspend fun header(conversationId: String): Result<ChatHeader>

    /** Live message stream for a conversation: an initial page plus appended live messages. */
    fun messages(conversationId: String): Flow<List<UiMessage>>

    suspend fun sendText(recipientId: String, text: String): Result<Unit>

    /**
     * Joins a channel from the (CBOR) invite-ticket bytes carried in a received channel-invite message;
     * returns the joined channel id so the caller can open it. Runs entirely over the messaging client,
     * so the chat feature needs no dependency on the channels feature.
     */
    suspend fun joinChannel(ticket: ByteArray): Result<String>

    /**
     * Prepares (compresses) the picked media at [uriString] and sends it inline or via IonStore.
     *
     * [sendId] identifies this send across retries (the caller's optimistic bubble id). A send that
     * uploads to IonStore and then fails to deliver the message keeps its uploaded object under that
     * id, so retrying the same [sendId] re-sends the message without re-uploading the payload - see
     * [uploads].
     */
    suspend fun sendAttachment(recipientId: String, uriString: String, sendId: String): Result<Unit>

    /**
     * Sends a recorded voice note (the Opus/Ogg file at [filePath], [durationMs] long). It rides
     * inline in the message body with a duration header; only an oversized note (VBR spike) falls
     * back to IonStore. See [chooseCarrier] for [AttachmentKind.VOICE]. [sendId] carries an uploaded
     * payload across a retry exactly as in [sendAttachment].
     */
    suspend fun sendVoice(recipientId: String, filePath: String, durationMs: Long, sendId: String): Result<Unit>

    /** Forgets any payload [sendId] uploaded but never delivered (the user abandoned the send). */
    fun discardSend(sendId: String)

    /**
     * Builds the optimistic UI attachment (name/mime/kind) for a just-picked content uri, without
     * reading or compressing its bytes - used to render the immediate outgoing bubble while the send
     * is in flight. The source is [AttachmentSource.Local].
     */
    fun optimisticAttachment(uriString: String): UiAttachment

    /** Resolves a remote attachment to a local (integrity-verified, cached) file. */
    suspend fun downloadAttachment(attachment: UiAttachment): Result<File>

    /**
     * Resolves an attachment to a local file usable by an external app (Open / Share): a remote
     * attachment is downloaded (content-addressed cache), an inline attachment's bytes are
     * materialized into the same cache. A still-sending [AttachmentSource.Local] cannot be resolved.
     */
    suspend fun localFile(attachment: UiAttachment): Result<File>

    suspend fun loadOlder(conversationId: String, before: Long, limit: Int): Result<List<UiMessage>>
    suspend fun removeConversation(conversationId: String): Result<Unit>

    /** Removes a single message from the local store on this device (by its store-assigned rid). */
    suspend fun removeMessage(rid: Long): Result<Unit>

    /**
     * Forwards an existing attachment to [recipientId]. A remote (IonStore) attachment re-references
     * the object already stored there - the same ref is re-sent with NO re-upload, and the recipient
     * fetches it cross-peer by ref. Only an inline attachment (which has no IonStore object) re-sends
     * its (small) bytes.
     */
    suspend fun forwardAttachment(recipientId: String, attachment: UiAttachment): Result<Unit>

    /**
     * Destinations a message can be forwarded to: existing conversations (marked [UiForwardTarget.recent])
     * followed by any other non-blocked contacts. Titles/avatars are enriched in the ViewModel via the
     * shared profile resolver, mirroring the conversation list.
     */
    suspend fun forwardTargets(): Result<List<UiForwardTarget>>

    companion object {
        const val PAGE_SIZE = 50
    }
}

/**
 * An attachment payload already committed to IonStore, held so that a send which failed *after* the
 * upload can be retried without paying for the upload again (and without orphaning the object it
 * already stored). Everything the message needs to reference the object, and nothing else.
 */
private data class UploadedPayload(
    val uri: String,
    val contentId: String,
    val mime: String,
    val size: Long,
    val name: String,
    val key: ByteArray,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is UploadedPayload && uri == other.uri && contentId == other.contentId &&
            mime == other.mime && size == other.size && name == other.name && key.contentEquals(other.key) &&
            width == other.width && height == other.height && durationMs == other.durationMs)

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + contentId.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + (width ?: 0)
        result = 31 * result + (height ?: 0)
        result = 31 * result + (durationMs?.hashCode() ?: 0)
        return result
    }
}

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val session: BosonSessionManager,
    private val mediaPreparer: MediaPreparer,
    private val cache: AttachmentCache,
    private val profileResolver: ProfileResolver,
    @ApplicationContext private val context: Context,
) : ChatRepository {

    private fun client(): MessagingClient =
        session.messagingClient
            ?: throw AppError.Network(context.getString(R.string.chat_error_not_connected_messaging))

    private fun ionStore(): IonStore =
        session.ionStore
            ?: throw AppError.Network(context.getString(R.string.chat_error_not_connected_storage))

    private fun myId(): Id? = session.messagingClient?.userId

    // Per-object (content-id keyed) download coordination so parallel fetches of the same attachment
    // are serialized into a single write instead of racing on the cache file. See downloadAttachment.
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()

    private fun downloadLock(contentId: String): Mutex =
        downloadLocks.computeIfAbsent(contentId) { Mutex() }

    /**
     * Payloads uploaded to IonStore whose message has not been delivered yet, keyed by send id.
     *
     * A large attachment is sent in two legs - upload the bytes, then send a message referencing them
     * - and only the second leg is cheap. Losing the first leg's work every time the second one fails
     * means a retry re-uploads the whole payload and abandons the object it stored on the previous
     * attempt (an encrypted put is never deduplicated, so it really is a second copy). Holding the ref
     * here makes a retry re-send just the message.
     *
     * Bounded by [MAX_PENDING_UPLOADS] and evicted oldest-first: an entry is dropped on delivery or
     * when the caller abandons the send, but a bubble left sitting in its failed state forever would
     * otherwise keep one alive for the life of the process.
     */
    private val uploads = object : LinkedHashMap<String, UploadedPayload>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: Map.Entry<String, UploadedPayload>): Boolean =
            size > MAX_PENDING_UPLOADS
    }

    // Plain monitor rather than a coroutine Mutex: every access is a single map operation that never
    // suspends, and discardSend is called from a non-suspending path, so one lock serves them all.
    private fun rememberUpload(sendId: String, payload: UploadedPayload) {
        synchronized(uploads) { uploads[sendId] = payload }
    }

    private fun recallUpload(sendId: String): UploadedPayload? =
        synchronized(uploads) { uploads[sendId] }

    private fun forgetUpload(sendId: String) {
        synchronized(uploads) { uploads.remove(sendId) }
    }

    override fun discardSend(sendId: String) = forgetUpload(sendId)

    /**
     * Waits briefly for the session to be usable before publishing.
     *
     * The transport drops and re-establishes itself routinely - a mobile OS closes idle sockets, so a
     * reconnect every few minutes is normal, not a fault - and it is back within a couple of seconds.
     * A send that happens to land in that window would otherwise fail on the spot and ask the user to
     * press Retry for something that fixes itself. Attachments are the worst hit: their message goes
     * out seconds after the user pressed send, once the upload finishes, so the connection they were
     * shown when they pressed it says nothing about the connection the message actually meets.
     *
     * Bounded, and deliberately silent on timeout: if the link is genuinely down the send proceeds and
     * fails with its own error, which is the honest one to report.
     */
    private suspend fun awaitSendable() {
        // CONNECTED, not READY: publishing needs the transport, and nothing more. READY additionally
        // requires the startup contact sync, which a send does not depend on - waiting for it would
        // stall sends over a link that is perfectly able to carry them.
        if (isSendable(session.connectionState.value)) return
        // Nothing to wait for when there is no session at all (signed out, or the first connect has
        // not run yet) - only a live client can reconnect. Let the send fail with its own error now
        // rather than making the user watch a timeout first.
        if (session.messagingClient == null) return
        withTimeoutOrNull(SEND_READY_TIMEOUT_MS) {
            session.connectionState.first { isSendable(it) }
        }
    }

    private fun isSendable(state: ConnectionState): Boolean =
        state == ConnectionState.CONNECTED || state == ConnectionState.READY

    // Keyed off the session's client flow (not a one-shot read) so a cold start - which composes the
    // UI before connect() completes - fills the list as soon as the session comes up, instead of
    // parking on an empty list until the screen is re-subscribed.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun conversations(): Flow<List<UiConversation>> =
        session.client.flatMapLatest { client ->
            if (client == null) flowOf(emptyList()) else conversationsOf(client)
        }

    private fun conversationsOf(client: MessagingClient): Flow<List<UiConversation>> = callbackFlow {
        suspend fun refresh() {
            // Avatar (and any DM name upgrade) is enriched in the ViewModel via the shared
            // ProfileResolver; the repository emits the library-provided title only.
            val list = client.getConversations().awaitResult()
                .map { convo -> convo.toUi() }
                .sortedByDescending { it.updatedAt }
            trySend(list)
        }
        refresh()

        val listener = object : MessageListener {
            override fun onMessage(message: Message) {
                launch { refresh() }
            }

            override fun onSent(message: Message) {
                launch { refresh() }
            }
        }
        client.addMessageListener(listener)
        awaitClose { client.removeMessageListener(listener) }
    }

    override suspend fun header(conversationId: String): Result<ChatHeader> = runCatching {
        val client = client()
        val id = parseId(conversationId)
        val contact = client.getContact(id).awaitResult().orElse(null)
        if (contact is Channel) {
            contact.loadMembers().awaitResult()
            ChatHeader(
                title = contact.name.orElse(null)?.takeIf { it.isNotBlank() } ?: shortId(conversationId),
                subtitle = memberCountLabel(contact.members.size),
                isChannel = true,
            )
        } else {
            // DM: prefer a locally set name (remark) or the library profile name. The library's own
            // conversation/contact title otherwise falls back to an abbreviated id; we surface our
            // shortId fallback instead so the ViewModel can recognise the "no local name" case and
            // upgrade it (name + avatar) with the Director-resolved profile.
            val localName = contact?.remark?.orElse(null)?.takeIf { it.isNotBlank() }
                ?: contact?.name?.orElse(null)?.takeIf { it.isNotBlank() }
            ChatHeader(
                title = localName ?: shortId(conversationId),
                isChannel = false,
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun messages(conversationId: String): Flow<List<UiMessage>> =
        session.client.flatMapLatest { client ->
            if (client == null) flowOf(emptyList()) else messagesOf(client, conversationId)
        }

    private fun messagesOf(client: MessagingClient, conversationId: String): Flow<List<UiMessage>> = callbackFlow {
        val convo = parseId(conversationId)
        val me = myId()

        // Sender attribution for channel bubbles (M4): resolve member ids to display names once
        // per stream; unknown senders fall back to a short id.
        val resolveSender = channelSenderResolver(client, convo)

        // ordered oldest -> newest, de-duplicated by message id
        val byId = LinkedHashMap<String, UiMessage>()

        fun emit() = trySend(byId.values.sortedBy { it.createdAt })

        client.getMessagesBefore(convo, Long.MAX_VALUE, ChatRepository.PAGE_SIZE, 0).awaitResult()
            .forEach { val ui = it.toUi(me, resolveSender); byId[ui.id] = ui }
        emit()

        val listener = object : MessageListener {
            private fun accept(message: Message) {
                if (message.conversationId.orElse(null) != convo) return
                val ui = message.toUi(me, resolveSender)
                byId[ui.id] = ui
                emit()
            }

            override fun onMessage(message: Message) = accept(message)
            override fun onSent(message: Message) = accept(message)
        }
        client.addMessageListener(listener)
        awaitClose { client.removeMessageListener(listener) }
    }

    override suspend fun sendText(recipientId: String, text: String): Result<Unit> = runCatching {
        awaitSendable()
        client().message(parseId(recipientId)).contentText(text).send().awaitResult()
        Unit
    }

    override suspend fun joinChannel(ticket: ByteArray): Result<String> = runCatching {
        val parsed = try {
            InviteTicket.fromBytes(ticket)
        } catch (e: Exception) {
            throw AppError.InvalidInput(context.getString(R.string.chat_error_invalid_invite_ticket), e)
        }
        client().joinChannel(parsed).awaitResult().id.toString()
    }

    override fun optimisticAttachment(uriString: String): UiAttachment {
        val (name, mime) = mediaPreparer.probe(uriString)
        return UiAttachment(
            kind = kindOf(mime),
            mime = mime,
            name = name,
            size = 0L,
            source = AttachmentSource.Local(uriString),
        )
    }

    override suspend fun localFile(attachment: UiAttachment): Result<File> =
        when (val source = attachment.source) {
            is AttachmentSource.Remote -> downloadAttachment(attachment)
            is AttachmentSource.Inline -> runCatching {
                // Materialize inline bytes into the shared attachment cache so a FileProvider uri can
                // be handed to an external viewer / share target.
                val key = "inline${source.bytes.contentHashCode()}"
                val file = cache.fileFor(key, attachment.name)
                if (!(file.exists() && file.length().toInt() == source.bytes.size)) {
                    file.writeBytes(source.bytes)
                    cache.trim()
                }
                file
            }
            is AttachmentSource.Local ->
                Result.failure(AppError.InvalidInput(context.getString(R.string.chat_error_attachment_still_sending)))
        }

    override suspend fun sendAttachment(recipientId: String, uriString: String, sendId: String): Result<Unit> = runCatching {
        val to = parseId(recipientId)

        // A retry of a send that already got its payload into IonStore skips straight to the message.
        recallUpload(sendId)?.let { uploaded ->
            sendUploaded(to, uploaded)
            forgetUpload(sendId)
            return@runCatching
        }

        val media = mediaPreparer.prepare(uriString)
        val size = media.bytes.size.toLong()
        when (chooseCarrier(kindOf(media.mime), size)) {
            AttachmentCarrier.INLINE -> {
                awaitSendable()
                client().message(to)
                    .contentBinary(media.bytes)
                    .contentType(media.mime)
                    .contentDisposition(ContentDisposition.inline(media.name))
                    .send().awaitResult()
            }

            AttachmentCarrier.ION_STORE -> {
                val store = ionStore()
                // IonStore retrieval is permissionless, so the payload is encrypted with a one-time key
                // that only this ref carries - and the ref travels inside the encrypted message body.
                val key = newAttachmentKey()
                val obj = store.put()
                    .name(media.name)
                    .contentType(media.mime)
                    .encrypt(key)
                    .content(media.bytes)
                    .send()
                    .awaitResult()
                val uploaded = UploadedPayload(
                    uri = obj.uri ?: "ions://${store.servicePeerId}/${obj.id}",
                    contentId = obj.contentId.toString(),
                    mime = media.mime,
                    size = size,
                    name = media.name,
                    key = key,
                    width = media.width,
                    height = media.height,
                )
                // Recorded before the message goes out, so a failure on that leg leaves the uploaded
                // object reusable rather than orphaned.
                rememberUpload(sendId, uploaded)
                sendUploaded(to, uploaded)
                forgetUpload(sendId)
            }
        }
        Unit
    }

    override suspend fun sendVoice(recipientId: String, filePath: String, durationMs: Long, sendId: String): Result<Unit> = runCatching {
        val to = parseId(recipientId)

        recallUpload(sendId)?.let { uploaded ->
            sendUploaded(to, uploaded)
            forgetUpload(sendId)
            return@runCatching
        }

        val file = File(filePath)
        val bytes = file.readBytes()
        val size = bytes.size.toLong()
        val name = file.name
        when (chooseCarrier(AttachmentKind.VOICE, size)) {
            AttachmentCarrier.INLINE -> {
                awaitSendable()
                client().message(to)
                    .contentBinary(bytes)
                    .contentType(VOICE_MIME)
                    .contentDisposition(ContentDisposition.inline(name))
                    .header(VoiceHeaders.DURATION, durationMs)
                    .send().awaitResult()
            }

            AttachmentCarrier.ION_STORE -> {
                // Safety net for a VBR spike beyond the inline voice ceiling: store the bytes and send
                // the ref (with duration) exactly like a large attachment.
                val store = ionStore()
                val key = newAttachmentKey()
                val obj = store.put()
                    .name(name)
                    .contentType(VOICE_MIME)
                    .encrypt(key)
                    .content(bytes)
                    .send()
                    .awaitResult()
                val uploaded = UploadedPayload(
                    uri = obj.uri ?: "ions://${store.servicePeerId}/${obj.id}",
                    contentId = obj.contentId.toString(),
                    mime = VOICE_MIME,
                    size = size,
                    name = name,
                    key = key,
                    durationMs = durationMs,
                )
                rememberUpload(sendId, uploaded)
                sendUploaded(to, uploaded)
                forgetUpload(sendId)
            }
        }
        Unit
    }

    /** Sends the message that references an already-stored payload. The cheap, retryable leg. */
    private suspend fun sendUploaded(to: Id, uploaded: UploadedPayload) {
        val ref = remoteAttachmentToMap(
            uri = uploaded.uri,
            contentId = uploaded.contentId,
            mime = uploaded.mime,
            size = uploaded.size,
            name = uploaded.name,
            width = uploaded.width,
            height = uploaded.height,
            durationMs = uploaded.durationMs,
            key = uploaded.key,
        )
        awaitSendable()
        client().message(to)
            .contentObject(ref)
            .contentType(uploaded.mime)
            .contentDisposition(ContentDisposition.attachment(uploaded.name))
            .send().awaitResult()
    }

    override suspend fun downloadAttachment(attachment: UiAttachment): Result<File> = runCatching {
        val source = attachment.source
        require(source is AttachmentSource.Remote) { "Attachment is inline; nothing to download" }

        val cached = cache.fileFor(source.contentId, attachment.name)
        // Serialize concurrent fetches of the same object. The auto-render download and the Save/Open
        // paths all land here; without this they could stream into a shared temp file at the same time
        // and, because integrity is verified against each network stream (not the bytes on disk),
        // commit a corrupted-but-"verified" file. One fetch wins; the rest reuse its result.
        downloadLock(source.contentId).withLock {
            if (cached.exists() && cached.length() > 0)
                return@withLock cached
            // Not yet cached: fetch into a private temp file and only atomically publish it to the
            // cache once the download + integrity check succeed, so a failed fetch leaves the cache
            // untouched.
            val store = ionStore()
            val (peerId, refId) = parseIonUri(source.uri)
            val expected = try {
                Id.of(source.contentId)
            } catch (e: Exception) {
                throw AppError.Integrity(context.getString(R.string.chat_error_malformed_content_id), e)
            }

            // A private temp file per download (never a shared "<name>.part") so parallel fetches can
            // never interleave their bytes into one another's output.
            val part = File.createTempFile("dl-", ".part", cached.parentFile)
            try {
                // An own-peer ref is fetched directly; any other peer is fetched through this service,
                // which resolves and caches it. The key (when the ref carries one) decrypts the stream
                // on the way to disk, so the temp file already holds plaintext.
                val request = if (peerId == store.servicePeerId) store.get(refId) else store.get(peerId, refId)
                source.key?.let { request.decrypt(it) }
                val meta = request.toFile(part.toPath()).awaitResult()

                val obj = meta.orElse(null)
                    ?: throw AppError.NotFound(context.getString(R.string.chat_error_attachment_unavailable))
                // End-to-end integrity: the library verifies bytes against the server-advertised content
                // id; additionally pin it to the content id the sender committed to in the message. The
                // content id is over the stored form, so for an encrypted object this pins the
                // ciphertext - and the decryption above authenticates the plaintext behind it.
                if (obj.contentId != expected)
                    throw AppError.Integrity(context.getString(R.string.chat_error_content_id_mismatch))
                if (!part.renameTo(cached)) part.copyTo(cached, overwrite = true)
            } finally {
                part.delete()
            }
            cache.trim()
            cached
        }
    }

    override suspend fun loadOlder(conversationId: String, before: Long, limit: Int): Result<List<UiMessage>> =
        runCatching {
            val client = client()
            val me = myId()
            val convo = parseId(conversationId)
            // Older channel pages carry senders too; resolve them the same way as the live stream.
            val resolveSender = channelSenderResolver(client, convo)
            client.getMessagesBefore(convo, before, limit, 0).awaitResult()
                .map { it.toUi(me, resolveSender) }
                .sortedBy { it.createdAt }
        }

    override suspend fun removeConversation(conversationId: String): Result<Unit> = runCatching {
        client().removeConversation(parseId(conversationId)).awaitResult()
        Unit
    }

    override suspend fun removeMessage(rid: Long): Result<Unit> = runCatching {
        client().removeMessage(rid).awaitResult()
        Unit
    }

    override suspend fun forwardAttachment(recipientId: String, attachment: UiAttachment): Result<Unit> = runCatching {
        val to = parseId(recipientId)
        when (val source = attachment.source) {
            is AttachmentSource.Remote -> {
                // The bytes already live in IonStore; forwarding re-sends the SAME ref (no re-upload).
                // The new recipient fetches them cross-peer by ref exactly like downloadAttachment does.
                // A voice note carries its duration in the ref so it stays a voice bubble downstream.
                // The object's key rides along too - forwarding an attachment means sharing the one key
                // that opens it, which is what forwarding the bytes would have meant anyway.
                val ref = remoteAttachmentToMap(
                    uri = source.uri,
                    contentId = source.contentId,
                    mime = attachment.mime,
                    size = attachment.size,
                    name = attachment.name,
                    width = attachment.width,
                    height = attachment.height,
                    durationMs = attachment.durationMs,
                    key = source.key,
                )
                awaitSendable()
                client().message(to)
                    .contentObject(ref)
                    .contentType(attachment.mime)
                    .contentDisposition(ContentDisposition.attachment(attachment.name))
                    .send().awaitResult()
            }

            is AttachmentSource.Inline -> {
                // Inline attachments have no IonStore object to point at, so re-send the bytes. A voice
                // note re-sends its duration header so it stays a voice bubble for the new recipient.
                awaitSendable()
                client().message(to)
                    .contentBinary(source.bytes)
                    .contentType(attachment.mime)
                    .contentDisposition(ContentDisposition.inline(attachment.name))
                    .apply { attachment.durationMs?.let { header(VoiceHeaders.DURATION, it) } }
                    .send().awaitResult()
            }

            is AttachmentSource.Local ->
                throw AppError.InvalidInput(context.getString(R.string.chat_error_attachment_still_sending))
        }
        Unit
    }

    override suspend fun forwardTargets(): Result<List<UiForwardTarget>> = runCatching {
        val client = client()
        val conversations = client.getConversations().awaitResult()
        val contacts = client.getContacts().awaitResult()

        // Existing conversations first (most-recent first); the library-provided title is enriched in
        // the ViewModel just like the chat list.
        val recent = conversations.map { it.toUi() }.map { c ->
            UiForwardTarget(
                id = c.id,
                title = c.title,
                isChannel = c.isChannel,
                recent = true,
                updatedAt = c.updatedAt,
                peerName = c.peerName,
                remark = c.remark,
            )
        }.sortedByDescending { it.updatedAt }

        val recentIds = recent.mapTo(HashSet()) { it.id }

        // Remaining contacts (and channels) the user hasn't opened a conversation with yet; blocked
        // contacts are never a forward destination.
        val others = contacts
            .filter { !it.isBlocked && it.getId().toString() !in recentIds }
            .map { contact ->
                val id = contact.getId().toString()
                val isChannel = contact is Channel
                val remark = contact.getRemark().orElse(null)?.takeIf { it.isNotBlank() }
                val name = contact.getName().orElse(null)?.takeIf { it.isNotBlank() }
                UiForwardTarget(
                    id = id,
                    title = remark ?: name ?: shortId(id),
                    isChannel = isChannel,
                    recent = false,
                    peerName = if (isChannel) null else name,
                    remark = if (isChannel) null else remark,
                )
            }
            .sortedBy { it.title.lowercase() }

        recent + others
    }

    /**
     * For a channel conversation, returns a member-id -> display-name resolver; null for DMs, where
     * bubbles need no attribution. Members without a local display name are prefetched from the
     * Director so their resolved public names attach on the next mapping pass; until then (or when
     * the Director doesn't know them either) senders fall back to a short id.
     */
    private suspend fun channelSenderResolver(client: MessagingClient, convo: Id): ((Id) -> DisplayProfile?)? =
        runCatching {
            val contact = client.getContact(convo).awaitResult().orElse(null) as? Channel
                ?: return@runCatching null
            contact.loadMembers().awaitResult()
            // Members carry no local name (the library identifies them by id only), so prefetch every
            // member's profile; cachedDisplay then yields the Director-resolved name+avatar, or a short
            // id until it arrives.
            contact.members.forEach { member -> profileResolver.prefetch(member.id.toString()) }
            val resolver: (Id) -> DisplayProfile = { from ->
                profileResolver.cachedDisplay(from.toString())
            }
            resolver
        }.getOrNull()

    private fun parseId(text: String): Id =
        try {
            Id.of(text.trim())
        } catch (e: Exception) {
            throw AppError.InvalidInput("Invalid conversation ID", e)
        }

    private fun memberCountLabel(count: Int): String =
        context.resources.getQuantityString(R.plurals.chat_channel_member_count, count, count)

    /** Splits an `ions://<peerId>/<refId>` URI into (peerId, refId). */
    private fun parseIonUri(uri: String): Pair<Id, Id> {
        val rest = uri.removePrefix("ions://")
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.lastIndex)
            throw AppError.InvalidInput("Malformed attachment URI: $uri")
        return try {
            Id.of(rest.substring(0, slash)) to Id.of(rest.substring(slash + 1))
        } catch (e: Exception) {
            throw AppError.InvalidInput("Malformed attachment URI: $uri", e)
        }
    }

    private companion object {
        /**
         * How long a send waits for the session to come back before going ahead anyway. Sized for the
         * routine reconnect (a couple of seconds: backoff plus handshake plus contact sync), not for
         * an actual outage - a link that is really down should report that promptly.
         */
        const val SEND_READY_TIMEOUT_MS = 6_000L

        /** Undelivered uploads retained for retry; see [uploads]. */
        const val MAX_PENDING_UPLOADS = 16
    }
}
