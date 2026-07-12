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

package io.photonmessenger.feature.chat.data

import io.photonmessenger.core.boson.BosonSessionManager
import io.photonmessenger.core.boson.awaitResult
import io.photonmessenger.core.model.AppError
import io.photonmessenger.core.model.ProfileResolver
import io.photonmessenger.core.model.cachedDisplay
import io.photonmessenger.core.model.shortId
import io.photonmessenger.feature.chat.model.AttachmentCarrier
import io.photonmessenger.feature.chat.model.AttachmentKind
import io.photonmessenger.feature.chat.model.AttachmentSource
import io.photonmessenger.feature.chat.model.VOICE_MIME
import io.photonmessenger.feature.chat.model.VoiceHeaders
import io.photonmessenger.feature.chat.model.ChatHeader
import io.photonmessenger.feature.chat.model.UiAttachment
import io.photonmessenger.feature.chat.model.UiConversation
import io.photonmessenger.feature.chat.model.UiForwardTarget
import io.photonmessenger.feature.chat.model.UiMessage
import io.photonmessenger.feature.chat.model.chooseCarrier
import io.photonmessenger.feature.chat.model.kindOf
import io.photonmessenger.feature.chat.model.remoteAttachmentToMap
import io.photonmessenger.feature.chat.model.toUi
import io.bosonnetwork.Id
import io.bosonnetwork.ionstore.IonStore
import io.bosonnetwork.ionstore.PutOptions
import io.bosonnetwork.photonmessaging.Channel
import io.bosonnetwork.photonmessaging.ContentDisposition
import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageListener
import io.bosonnetwork.photonmessaging.MessagingClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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

    /** Prepares (compresses) the picked media at [uriString] and sends it inline or via IonStore. */
    suspend fun sendAttachment(recipientId: String, uriString: String): Result<Unit>

    /**
     * Sends a recorded voice note (the Opus/Ogg file at [filePath], [durationMs] long). It rides
     * inline in the message body with a duration header; only an oversized note (VBR spike) falls
     * back to IonStore. See [chooseCarrier] for [AttachmentKind.VOICE].
     */
    suspend fun sendVoice(recipientId: String, filePath: String, durationMs: Long): Result<Unit>

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

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val session: BosonSessionManager,
    private val mediaPreparer: MediaPreparer,
    private val cache: AttachmentCache,
    private val profileResolver: ProfileResolver,
) : ChatRepository {

    private fun client(): MessagingClient =
        session.messagingClient ?: throw AppError.Network("Not connected to the messaging service")

    private fun ionStore(): IonStore =
        session.ionStore ?: throw AppError.Network("Not connected to the storage service")

    private fun myId(): Id? = session.messagingClient?.userId

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
        client().message(parseId(recipientId)).contentText(text).send().awaitResult()
        Unit
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
                Result.failure(AppError.InvalidInput("Attachment is still sending"))
        }

    override suspend fun sendAttachment(recipientId: String, uriString: String): Result<Unit> = runCatching {
        val to = parseId(recipientId)
        val media = mediaPreparer.prepare(uriString)
        val size = media.bytes.size.toLong()
        when (chooseCarrier(kindOf(media.mime), size)) {
            AttachmentCarrier.INLINE ->
                client().message(to)
                    .contentBinary(media.bytes)
                    .contentType(media.mime)
                    .contentDisposition(ContentDisposition.inline(media.name))
                    .send().awaitResult()

            AttachmentCarrier.ION_STORE -> {
                val store = ionStore()
                val options = PutOptions.builder().name(media.name).contentType(media.mime).build()
                val obj = store.put(media.bytes, options).awaitResult()
                val uri = obj.uri ?: "ions://${store.servicePeerId}/${obj.id}"
                val ref = remoteAttachmentToMap(
                    uri = uri,
                    contentId = obj.contentId.toString(),
                    mime = media.mime,
                    size = size,
                    name = media.name,
                    width = media.width,
                    height = media.height,
                )
                client().message(to)
                    .contentObject(ref)
                    .contentType(media.mime)
                    .contentDisposition(ContentDisposition.attachment(media.name))
                    .send().awaitResult()
            }
        }
        Unit
    }

    override suspend fun sendVoice(recipientId: String, filePath: String, durationMs: Long): Result<Unit> = runCatching {
        val to = parseId(recipientId)
        val file = File(filePath)
        val bytes = file.readBytes()
        val size = bytes.size.toLong()
        val name = file.name
        when (chooseCarrier(AttachmentKind.VOICE, size)) {
            AttachmentCarrier.INLINE ->
                client().message(to)
                    .contentBinary(bytes)
                    .contentType(VOICE_MIME)
                    .contentDisposition(ContentDisposition.inline(name))
                    .header(VoiceHeaders.DURATION, durationMs)
                    .send().awaitResult()

            AttachmentCarrier.ION_STORE -> {
                // Safety net for a VBR spike beyond the inline voice ceiling: store the bytes and send
                // the ref (with duration) exactly like a large attachment.
                val store = ionStore()
                val options = PutOptions.builder().name(name).contentType(VOICE_MIME).build()
                val obj = store.put(bytes, options).awaitResult()
                val uri = obj.uri ?: "ions://${store.servicePeerId}/${obj.id}"
                val ref = remoteAttachmentToMap(
                    uri = uri,
                    contentId = obj.contentId.toString(),
                    mime = VOICE_MIME,
                    size = size,
                    name = name,
                    width = null,
                    height = null,
                    durationMs = durationMs,
                )
                client().message(to)
                    .contentObject(ref)
                    .contentType(VOICE_MIME)
                    .contentDisposition(ContentDisposition.attachment(name))
                    .send().awaitResult()
            }
        }
        Unit
    }

    override suspend fun downloadAttachment(attachment: UiAttachment): Result<File> = runCatching {
        val source = attachment.source
        require(source is AttachmentSource.Remote) { "Attachment is inline; nothing to download" }

        val cached = cache.fileFor(source.contentId, attachment.name)
        if (cached.exists() && cached.length() > 0) return@runCatching cached

        val store = ionStore()
        val (peerId, refId) = parseIonUri(source.uri)
        val expected = try {
            Id.of(source.contentId)
        } catch (e: Exception) {
            throw AppError.Integrity("Malformed content id", e)
        }

        val part = File(cached.parentFile, "${cached.name}.part")
        val meta = if (peerId == store.servicePeerId)
            store.get(refId, part.toPath()).awaitResult()
        else
            store.get(peerId, refId, part.toPath()).awaitResult()

        val obj = meta.orElse(null) ?: run {
            part.delete()
            throw AppError.NotFound("Attachment is no longer available")
        }
        // End-to-end integrity: the library verifies bytes against the server-advertised content id;
        // additionally pin it to the content id the sender committed to in the message.
        if (obj.contentId != expected) {
            part.delete()
            throw AppError.Integrity("Attachment content id mismatch")
        }
        if (!part.renameTo(cached)) {
            part.copyTo(cached, overwrite = true)
            part.delete()
        }
        cache.trim()
        cached
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
                val ref = remoteAttachmentToMap(
                    uri = source.uri,
                    contentId = source.contentId,
                    mime = attachment.mime,
                    size = attachment.size,
                    name = attachment.name,
                    width = attachment.width,
                    height = attachment.height,
                    durationMs = attachment.durationMs,
                )
                client().message(to)
                    .contentObject(ref)
                    .contentType(attachment.mime)
                    .contentDisposition(ContentDisposition.attachment(attachment.name))
                    .send().awaitResult()
            }

            is AttachmentSource.Inline ->
                // Inline attachments have no IonStore object to point at, so re-send the bytes. A voice
                // note re-sends its duration header so it stays a voice bubble for the new recipient.
                client().message(to)
                    .contentBinary(source.bytes)
                    .contentType(attachment.mime)
                    .contentDisposition(ContentDisposition.inline(attachment.name))
                    .apply { attachment.durationMs?.let { header(VoiceHeaders.DURATION, it) } }
                    .send().awaitResult()

            is AttachmentSource.Local ->
                throw AppError.InvalidInput("Attachment is still sending")
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
    private suspend fun channelSenderResolver(client: MessagingClient, convo: Id): ((Id) -> String?)? =
        runCatching {
            val contact = client.getContact(convo).awaitResult().orElse(null) as? Channel
                ?: return@runCatching null
            contact.loadMembers().awaitResult()
            // Members carry no local name (the library identifies them by id only), so prefetch every
            // member's profile; cachedDisplay then yields the Director-resolved name, or a short id
            // until it arrives.
            contact.members.forEach { member -> profileResolver.prefetch(member.id.toString()) }
            val resolver: (Id) -> String = { from ->
                profileResolver.cachedDisplay(from.toString()).displayName
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
        if (count == 1) "1 member" else "$count members"

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
}
