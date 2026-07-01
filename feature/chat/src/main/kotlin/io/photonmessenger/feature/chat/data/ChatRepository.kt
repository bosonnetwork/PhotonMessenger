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
import io.photonmessenger.feature.chat.model.AttachmentCarrier
import io.photonmessenger.feature.chat.model.AttachmentSource
import io.photonmessenger.feature.chat.model.ChatHeader
import io.photonmessenger.feature.chat.model.UiAttachment
import io.photonmessenger.feature.chat.model.UiConversation
import io.photonmessenger.feature.chat.model.UiMessage
import io.photonmessenger.feature.chat.model.chooseCarrier
import io.photonmessenger.feature.chat.model.kindOf
import io.photonmessenger.feature.chat.model.remoteAttachmentToMap
import io.photonmessenger.feature.chat.model.shortId
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

    /** Resolves a remote attachment to a local (integrity-verified, cached) file. */
    suspend fun downloadAttachment(attachment: UiAttachment): Result<File>

    suspend fun loadOlder(conversationId: String, before: Long, limit: Int): Result<List<UiMessage>>
    suspend fun removeConversation(conversationId: String): Result<Unit>

    companion object {
        const val PAGE_SIZE = 50
    }
}

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val session: BosonSessionManager,
    private val mediaPreparer: MediaPreparer,
    private val cache: AttachmentCache,
) : ChatRepository {

    private fun client(): MessagingClient =
        session.messagingClient ?: throw AppError.Network("Not connected to the messaging service")

    private fun ionStore(): IonStore =
        session.ionStore ?: throw AppError.Network("Not connected to the storage service")

    private fun myId(): Id? = session.messagingClient?.userId

    override fun conversations(): Flow<List<UiConversation>> = callbackFlow {
        val client = session.messagingClient
        if (client == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        suspend fun refresh() {
            val list = client.getConversations().awaitResult()
                .map { it.toUi() }
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
            val convoTitle = client.getConversation(id).awaitResult().orElse(null)?.title?.takeIf { it.isNotBlank() }
            val title = convoTitle
                ?: contact?.name?.orElse(null)?.takeIf { it.isNotBlank() }
                ?: shortId(conversationId)
            ChatHeader(title = title, isChannel = false)
        }
    }

    override fun messages(conversationId: String): Flow<List<UiMessage>> = callbackFlow {
        val client = session.messagingClient
        if (client == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val convo = parseId(conversationId)
        val me = myId()

        // ordered oldest -> newest, de-duplicated by message id
        val byId = LinkedHashMap<String, UiMessage>()

        fun emit() = trySend(byId.values.sortedBy { it.createdAt })

        client.getMessagesBefore(convo, Long.MAX_VALUE, ChatRepository.PAGE_SIZE, 0).awaitResult()
            .forEach { val ui = it.toUi(me); byId[ui.id] = ui }
        emit()

        val listener = object : MessageListener {
            private fun accept(message: Message) {
                if (message.conversationId.orElse(null) != convo) return
                val ui = message.toUi(me)
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
            val me = myId()
            client().getMessagesBefore(parseId(conversationId), before, limit, 0).awaitResult()
                .map { it.toUi(me) }
                .sortedBy { it.createdAt }
        }

    override suspend fun removeConversation(conversationId: String): Result<Unit> = runCatching {
        client().removeConversation(parseId(conversationId)).awaitResult()
        Unit
    }

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
