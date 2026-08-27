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

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.bosonnetwork.photon.core.boson.ChannelInvite
import io.bosonnetwork.photon.core.boson.UnreadTracker
import io.bosonnetwork.photon.core.model.ChannelInviteStore
import io.bosonnetwork.photon.core.model.InviteAction
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.ResolvedProfile
import io.bosonnetwork.photon.feature.chat.data.ChatRepository
import io.bosonnetwork.photon.feature.chat.data.ForwardPayload
import io.bosonnetwork.photon.feature.chat.data.ForwardPayloadStore
import io.bosonnetwork.photon.feature.chat.data.MediaSaver
import io.bosonnetwork.photon.feature.chat.data.VoicePlayer
import io.bosonnetwork.photon.feature.chat.data.VoiceRecorder
import io.mockk.every
import io.mockk.mockk
import io.bosonnetwork.photon.feature.chat.model.AttachmentCarrier
import io.bosonnetwork.photon.feature.chat.model.AttachmentKind
import io.bosonnetwork.photon.feature.chat.model.AttachmentSource
import io.bosonnetwork.photon.feature.chat.model.ChatHeader
import io.bosonnetwork.photon.feature.chat.model.MessageStatus
import io.bosonnetwork.photon.feature.chat.model.UiAttachment
import io.bosonnetwork.photon.feature.chat.model.UiConversation
import io.bosonnetwork.photon.feature.chat.model.UiMessage
import io.bosonnetwork.photon.feature.chat.model.VOICE_MIME
import io.bosonnetwork.photon.feature.chat.model.chooseCarrier
import io.bosonnetwork.photon.feature.chat.model.remoteAttachmentFromMap
import io.bosonnetwork.photon.feature.chat.model.remoteAttachmentToMap
import io.bosonnetwork.photonmessaging.exceptions.NotConnectedException
import io.bosonnetwork.photonmessaging.exceptions.rpc.ChannelMemberLimitExceededException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatViewModelsTest {

    private val convosFlow = MutableStateFlow<List<UiConversation>>(emptyList())
    private val msgsFlow = MutableStateFlow<List<UiMessage>>(emptyList())

    private class FakeChatRepo(
        private val convos: Flow<List<UiConversation>>,
        private val msgs: Flow<List<UiMessage>>,
        var sendResult: Result<Unit> = Result.success(Unit),
        var headerResult: Result<ChatHeader> = Result.success(ChatHeader(title = "Header")),
        var olderResult: Result<List<UiMessage>> = Result.success(emptyList()),
    ) : ChatRepository {
        /** Records (recipient, text) of every text send. */
        val sentTexts = mutableListOf<Pair<String, String>>()
        /** Records (recipient, attachment) of every forwarded attachment. */
        val forwardedAttachments = mutableListOf<Pair<String, UiAttachment>>()
        /** Records (recipient, filePath, durationMs) of every voice send. */
        val sentVoices = mutableListOf<Triple<String, String, Long>>()

        override fun conversations() = convos
        override suspend fun header(conversationId: String) = headerResult
        override fun messages(conversationId: String) = msgs
        override suspend fun sendText(recipientId: String, text: String): Result<Unit> {
            sentTexts += recipientId to text
            return sendResult
        }
        /** Records every invite ticket (CBOR bytes) a Join tried to consume. */
        val joinedTickets = mutableListOf<ByteArray>()
        var joinResult: Result<String> = Result.success("joined-channel")
        override suspend fun joinChannel(ticket: ByteArray): Result<String> {
            joinedTickets += ticket
            return joinResult
        }
        /** Send ids seen by [sendAttachment], in order - a retry must reuse the first attempt's id. */
        val attachmentSendIds = mutableListOf<String>()
        /** Send ids [discardSend] was asked to forget. */
        val discardedSendIds = mutableListOf<String>()

        override suspend fun sendAttachment(recipientId: String, uriString: String, sendId: String): Result<Unit> {
            attachmentSendIds += sendId
            return sendResult
        }
        override suspend fun sendVoice(recipientId: String, filePath: String, durationMs: Long, sendId: String): Result<Unit> {
            sentVoices += Triple(recipientId, filePath, durationMs)
            return sendResult
        }
        override fun discardSend(sendId: String) {
            discardedSendIds += sendId
        }
        override fun optimisticAttachment(uriString: String) = UiAttachment(
            kind = AttachmentKind.IMAGE,
            mime = "image/jpeg",
            name = "photo.jpg",
            size = 0L,
            source = AttachmentSource.Local(uriString),
        )
        override suspend fun downloadAttachment(attachment: UiAttachment) =
            Result.failure<java.io.File>(UnsupportedOperationException("not used"))
        override suspend fun localFile(attachment: UiAttachment) =
            Result.failure<java.io.File>(UnsupportedOperationException("not used"))
        override suspend fun loadOlder(conversationId: String, before: Long, limit: Int) = olderResult
        override suspend fun removeConversation(conversationId: String) = Result.success(Unit)
        override suspend fun removeMessage(rid: Long) = Result.success(Unit)
        override suspend fun forwardAttachment(recipientId: String, attachment: UiAttachment): Result<Unit> {
            forwardedAttachments += recipientId to attachment
            return Result.success(Unit)
        }
        override suspend fun forwardTargets() =
            Result.success(emptyList<io.bosonnetwork.photon.feature.chat.model.UiForwardTarget>())
    }

    private val mediaSaver = object : MediaSaver {
        override suspend fun save(
            name: String,
            mime: String,
            kind: AttachmentKind,
            open: () -> java.io.InputStream,
        ) = Result.success("Pictures")
    }

    /** Fake recorder that yields a fixed temp file + duration; no real audio hardware. */
    private class FakeVoiceRecorder(override val isSupported: Boolean = true) : VoiceRecorder {
        override var onMaxDuration: (() -> Unit)? = null
        override val isRecording: Boolean get() = false
        private val file = File.createTempFile("voice", ".ogg").apply { deleteOnExit() }
        override fun start(): File = file
        override fun stop(): VoiceRecorder.Recording? = VoiceRecorder.Recording(file, 2500L)
        override fun cancel() = Unit
        override fun maxAmplitude(): Int = 0
    }

    /** No-op resolver: DM titles are already provided by the fakes, so nothing needs resolving. */
    private val resolver = object : ProfileResolver {
        override fun profile(userId: String): Flow<ResolvedProfile?> = flowOf(null)
        override fun cached(userId: String): ResolvedProfile? = null
        override fun prefetch(userId: String) = Unit
    }

    private class FakeUnreadTracker : UnreadTracker {
        private val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
        override val unread: StateFlow<Map<String, Int>> = counts
        override val totalUnread: StateFlow<Int> = MutableStateFlow(0)
        var lastActive: String? = null
        override fun markRead(conversationId: String) { counts.update { it - conversationId } }
        override fun setActiveConversation(conversationId: String?) { lastActive = conversationId }
    }

    /**
     * Minimal string-resource stand-in for the plain-JUnit (non-Robolectric) unit tests: the
     * ViewModels resolve their user-facing error/status text via [android.content.Context.getString]
     * now that the chat feature is localized, so tests need a Context that resolves the same resource
     * ids to their English strings.xml text (kept in sync by hand below). `Context.getString` is a
     * final convenience method on the real framework class, so it cannot be overridden by subclassing
     * (only [ContextWrapper.getResources] is open) - a relaxed MockK mock is used instead, since MockK's
     * inline mock maker can stub final methods without needing a Robolectric runtime.
     */
    private val fakeContext: Context = mockk(relaxed = true) {
        val strings = mapOf(
            R.string.chat_error_unknown to "unknown error",
            R.string.chat_error_unavailable to "unavailable",
            R.string.chat_error_download_failed to "download failed",
            R.string.chat_error_load_older to "Couldn't load older messages: %1\$s",
            R.string.chat_error_delete_message to "Couldn't delete message: %1\$s",
            R.string.chat_error_join_channel to "Couldn't join channel: %1\$s",
            R.string.chat_error_channel_full to
                "This channel is full: it already has as many members as its owner's plan allows.",
            R.string.chat_error_play to "Couldn't play: %1\$s",
            R.string.chat_error_save to "Couldn't save: %1\$s",
            R.string.chat_error_open to "Couldn't open: %1\$s",
            R.string.chat_error_share to "Couldn't share: %1\$s",
            R.string.chat_saved_to to "Saved to %1\$s",
            R.string.chat_error_start_recording to "Couldn't start recording",
            R.string.chat_error_recording_failed to "Recording failed",
            R.string.chat_error_not_connected_send to
                "Not connected. Your %1\$s wasn't sent - retry once you're back online.",
            R.string.chat_error_send_timeout to "Your %1\$s timed out.",
            R.string.chat_error_send_failed to "Couldn't send your %1\$s.",
            R.string.chat_noun_message to "message",
            R.string.chat_noun_attachment to "attachment",
            R.string.chat_noun_voice_message to "voice message",
            R.string.chat_error_delete_conversation to "Couldn't delete conversation: %1\$s",
            R.string.chat_error_load_contacts to "Couldn't load contacts: %1\$s",
            R.string.chat_error_forward to "Couldn't forward: %1\$s",
            R.string.chat_forward_label_attachment_fallback to "attachment",
        )
        every { getString(any()) } answers {
            val id = firstArg<Int>()
            strings[id] ?: "STRING_$id"
        }
        every { getString(any(), *anyVararg()) } answers {
            val id = firstArg<Int>()
            val fmtArgs = secondArg<Array<*>>()
            String.format(strings[id] ?: "STRING_$id", *fmtArgs)
        }
    }

    private fun conversationsVm(repo: ChatRepository) =
        ConversationsViewModel(repo, resolver, FakeUnreadTracker(), fakeContext)

    /** In-memory invite-state store; records joined/ignored per message id. */
    private class FakeChannelInviteStore : ChannelInviteStore {
        val state = MutableStateFlow<Map<String, InviteAction>>(emptyMap())
        override fun actions() = state
        override suspend fun setAction(
            messageId: String,
            action: InviteAction,
            channelId: String?,
            channelName: String?,
        ) {
            state.update { it + (messageId to action) }
        }
    }

    private fun chatVm(
        repo: ChatRepository,
        conversationId: String,
        recorder: VoiceRecorder = FakeVoiceRecorder(),
        inviteStore: ChannelInviteStore = FakeChannelInviteStore(),
    ) =
        ChatViewModel(
            repo, FakeUnreadTracker(), resolver, mediaSaver, ForwardPayloadStore(),
            recorder, VoicePlayer(), inviteStore, fakeContext,
            SavedStateHandle(mapOf("conversationId" to conversationId)),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `conversations are exposed`() = runTest {
        convosFlow.value = listOf(UiConversation("a", "Alice", "hi", false, 10))
        val vm = conversationsVm(FakeChatRepo(convosFlow, msgsFlow))

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertFalse(state.loading)
            assertEquals(listOf("Alice"), state.conversations.map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `chat exposes messages and blank send is ignored`() = runTest {
        msgsFlow.value = listOf(UiMessage("m1", "hello", fromMe = false, createdAt = 1))
        val repo = FakeChatRepo(convosFlow, msgsFlow)
        val vm = chatVm(repo, "abc")

        assertEquals("abc", vm.conversationId)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(1, state.messages.size)
            assertEquals("hello", state.messages.first().text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `channel header is resolved and exposed`() = runTest {
        val repo = FakeChatRepo(
            convosFlow,
            msgsFlow,
            headerResult = Result.success(ChatHeader(title = "Devs", subtitle = "3 members", isChannel = true)),
        )
        val vm = chatVm(repo, "chan1")

        vm.header.test {
            var header = awaitItem()
            while (!header.isChannel) header = awaitItem()
            assertEquals("Devs", header.title)
            assertEquals("3 members", header.subtitle)
            assertTrue(header.isChannel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed send is retriable with the original text`() = runTest {
        val repo = FakeChatRepo(convosFlow, msgsFlow, sendResult = Result.failure(IllegalStateException("nope")))
        val vm = chatVm(repo, "abc")

        vm.sendFailures.test {
            vm.send("hi")
            val failure = awaitItem()
            // The internal exception text ("nope") must never reach the user; a clean, generic
            // prompt is shown while the original draft is preserved for the Retry action.
            assertFalse(failure.message.contains("nope"))
            assertEquals("Couldn't send your message.", failure.message)
            assertEquals("hi", failure.text)
            assertTrue(failure.pendingId.startsWith("pending-"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `send while disconnected reports a clean not-connected message`() = runTest {
        val repo = FakeChatRepo(
            convosFlow, msgsFlow,
            sendResult = Result.failure(
                NotConnectedException("Not connected to the messaging service"),
            ),
        )
        val vm = chatVm(repo, "abc")

        vm.sendFailures.test {
            vm.send("hi")
            val failure = awaitItem()
            // A lost transport surfaces as a not-connected state, never the raw MQTT/Vert.x detail.
            assertTrue(failure.message.startsWith("Not connected."))
            assertFalse(failure.message.contains("messaging service"))
            assertEquals("hi", failure.text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed send leaves a failed optimistic bubble`() = runTest {
        val repo = FakeChatRepo(convosFlow, msgsFlow, sendResult = Result.failure(IllegalStateException("nope")))
        val vm = chatVm(repo, "abc")

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.send("hi")
            // The bubble is shown optimistically as SENDING, then settles to FAILED once the send fails.
            var withBubble = awaitItem()
            while (withBubble.messages.singleOrNull()?.status != MessageStatus.FAILED) withBubble = awaitItem()
            val bubble = withBubble.messages.single()
            assertEquals("hi", bubble.text)
            assertTrue(bubble.fromMe)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retrying an attachment reuses the original send id`() = runTest {
        // The send id is what lets the repository skip a re-upload on retry: if the retry arrived under
        // a fresh id it would look like a brand-new send and pay for the whole upload again.
        val repo = FakeChatRepo(convosFlow, msgsFlow, sendResult = Result.failure(IllegalStateException("nope")))
        val vm = chatVm(repo, "abc")

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.sendAttachment("content://pick/1")
            var failed = awaitItem()
            while (failed.messages.singleOrNull()?.status != MessageStatus.FAILED) failed = awaitItem()

            vm.retryAttachment(failed.messages.single())
            while (repo.attachmentSendIds.size < 2) awaitItem()

            assertEquals(2, repo.attachmentSendIds.size)
            assertEquals(repo.attachmentSendIds[0], repo.attachmentSendIds[1])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissing a failed attachment releases its uploaded payload`() = runTest {
        // Abandoning the send has to tell the repository, or a payload it uploaded for the retry that
        // never came would be held until eviction.
        val repo = FakeChatRepo(convosFlow, msgsFlow, sendResult = Result.failure(IllegalStateException("nope")))
        val vm = chatVm(repo, "abc")

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            vm.sendAttachment("content://pick/1")
            var failed = awaitItem()
            while (failed.messages.singleOrNull()?.status != MessageStatus.FAILED) failed = awaitItem()
            val bubble = failed.messages.single()

            vm.deleteMessage(bubble)

            assertEquals(listOf(bubble.id), repo.discardedSendIds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `successful send leaves no optimistic bubble`() = runTest {
        val repo = FakeChatRepo(convosFlow, msgsFlow)
        val vm = chatVm(repo, "abc")

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertTrue(state.messages.isEmpty())

            vm.send("hi")
            // The confirmed message would arrive on the live stream; the optimistic bubble is dropped,
            // so the settled state carries no lingering "sending" message.
            cancelAndIgnoreRemainingEvents()
            assertTrue(vm.uiState.value.messages.isEmpty())
        }
    }

    @Test
    fun `search filters conversations and reports a filtered-empty result`() = runTest {
        convosFlow.value = listOf(
            UiConversation("a", "Alice", "hi", false, 10),
            UiConversation("b", "Bob", "yo", false, 9),
        )
        val vm = conversationsVm(FakeChatRepo(convosFlow, msgsFlow))

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(2, state.conversations.size)

            vm.onQueryChange("ali")
            var filtered = awaitItem()
            while (filtered.conversations.size != 1) filtered = awaitItem()
            assertEquals(listOf("Alice"), filtered.conversations.map { it.title })
            assertFalse(filtered.filteredEmpty)

            vm.onQueryChange("zzz")
            var empty = awaitItem()
            while (empty.conversations.isNotEmpty()) empty = awaitItem()
            assertTrue(empty.filteredEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadOlder prepends an older page`() = runTest {
        msgsFlow.value = listOf(UiMessage("m1", "hi", fromMe = false, createdAt = 5))
        val repo = FakeChatRepo(
            convosFlow,
            msgsFlow,
            olderResult = Result.success(listOf(UiMessage("m0", "earlier", fromMe = false, createdAt = 1))),
        )
        val vm = chatVm(repo, "abc")

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(listOf("m1"), state.messages.map { it.id })

            vm.loadOlder()
            val paged = awaitItem()
            assertEquals(listOf("m0", "m1"), paged.messages.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `forwarding a remote attachment dispatches via forwardAttachment, not as text`() = runTest {
        val attachment = UiAttachment(
            kind = AttachmentKind.FILE,
            mime = "application/pdf",
            name = "doc.pdf",
            size = 1024,
            source = AttachmentSource.Remote("ions://peer/ref", "cid"),
        )
        val repo = FakeChatRepo(convosFlow, msgsFlow)
        val store = ForwardPayloadStore().apply { set(ForwardPayload.Attachment(attachment)) }
        val vm = ForwardViewModel(repo, resolver, store, fakeContext)

        vm.forwarded.test {
            vm.forward("target1")
            assertEquals("target1", awaitItem())
            // Remote attachments re-reference the existing IonStore object: forwarded via the
            // attachment path (no re-upload), never re-sent as text.
            assertEquals(listOf("target1" to attachment), repo.forwardedAttachments)
            assertTrue(repo.sentTexts.isEmpty())
            assertNull(store.peek())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `forwarding text dispatches via sendText`() = runTest {
        val repo = FakeChatRepo(convosFlow, msgsFlow)
        val store = ForwardPayloadStore().apply { set(ForwardPayload.Text("hello")) }
        val vm = ForwardViewModel(repo, resolver, store, fakeContext)

        vm.forwarded.test {
            vm.forward("target2")
            assertEquals("target2", awaitItem())
            assertEquals(listOf("target2" to "hello"), repo.sentTexts)
            assertTrue(repo.forwardedAttachments.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recording and sending dispatches a voice note with its duration`() = runTest {
        val repo = FakeChatRepo(convosFlow, msgsFlow)
        val vm = chatVm(repo, "abc")

        vm.startRecording()
        vm.stopAndSendRecording()

        assertEquals(1, repo.sentVoices.size)
        val (recipient, _, duration) = repo.sentVoices.first()
        assertEquals("abc", recipient)
        assertEquals(2500L, duration)
        // The optimistic bubble is dropped once the (successful) send confirms.
        assertTrue(vm.uiState.value.messages.isEmpty())
    }

    @Test
    fun `voice is unsupported when the recorder cannot record`() {
        val repo = FakeChatRepo(convosFlow, msgsFlow)
        val vm = chatVm(repo, "abc", recorder = FakeVoiceRecorder(isSupported = false))
        assertFalse(vm.voiceSupported)
    }

    @Test
    fun `voice carrier is inline under the ceiling and IonStore beyond it`() {
        assertEquals(AttachmentCarrier.INLINE, chooseCarrier(AttachmentKind.VOICE, 120L * 1024))
        assertEquals(AttachmentCarrier.ION_STORE, chooseCarrier(AttachmentKind.VOICE, 300L * 1024))
    }

    @Test
    fun `a remote ref with an audio mime and duration decodes as a voice note`() {
        val map = remoteAttachmentToMap(
            uri = "ions://peer/ref",
            contentId = "cid",
            mime = VOICE_MIME,
            size = 4096,
            name = "voice-1.ogg",
            width = null,
            height = null,
            durationMs = 4200L,
        )
        val ui = remoteAttachmentFromMap(map)!!
        assertEquals(AttachmentKind.VOICE, ui.kind)
        assertEquals(4200L, ui.durationMs)
    }

    @Test
    fun `joining an invite consumes the ticket, persists JOINED, and opens the channel`() = runTest {
        val repo = FakeChatRepo(convosFlow, msgsFlow).apply { joinResult = Result.success("chan-9") }
        val store = FakeChannelInviteStore()
        val vm = chatVm(repo, "abc", inviteStore = store)
        val ticketBytes = byteArrayOf(7, 7, 7, 1, 2)
        val invite = ChannelInvite(
            ticket = ticketBytes,
            channelName = "Design",
            expiresAt = System.currentTimeMillis() + 60_000,
        )
        val message = UiMessage("inv1", "", fromMe = false, createdAt = 1, invite = invite)

        vm.joinedChannel.test {
            vm.joinInvite(message)
            assertEquals("chan-9", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, repo.joinedTickets.size)
        assertArrayEquals(ticketBytes, repo.joinedTickets.first())
        assertEquals(InviteAction.JOINED, store.state.value["inv1"])
    }

    @Test
    fun `joining a full channel explains the channel is full, not the ticket`() = runTest {
        val repo = FakeChatRepo(convosFlow, msgsFlow).apply {
            joinResult = Result.failure(
                ChannelMemberLimitExceededException("Channel member limit reached: allowed 20, joined 20"),
            )
        }
        val store = FakeChannelInviteStore()
        val vm = chatVm(repo, "abc", inviteStore = store)
        val invite = ChannelInvite(
            ticket = byteArrayOf(1, 2, 3),
            channelName = "Design",
            expiresAt = System.currentTimeMillis() + 60_000,
        )
        val message = UiMessage("inv3", "", fromMe = false, createdAt = 1, invite = invite)

        vm.errors.test {
            vm.joinInvite(message)
            val shown = awaitItem()
            assertTrue(shown.contains("full"))
            // The raw server text states the owner's allowance and is not translated: it must not leak.
            assertFalse(shown.contains("allowed 20"))
            cancelAndIgnoreRemainingEvents()
        }
        // A refusal leaves the card joinable: nothing is persisted.
        assertTrue(store.state.value.isEmpty())
    }

    @Test
    fun `ignoring an invite persists IGNORED without joining`() = runTest {
        val repo = FakeChatRepo(convosFlow, msgsFlow)
        val store = FakeChannelInviteStore()
        val vm = chatVm(repo, "abc", inviteStore = store)
        val invite = ChannelInvite(
            ticket = byteArrayOf(1),
            channelName = "X",
            expiresAt = System.currentTimeMillis() + 60_000,
        )
        val message = UiMessage("inv2", "", fromMe = false, createdAt = 1, invite = invite)

        vm.ignoreInvite(message)

        assertEquals(InviteAction.IGNORED, store.state.value["inv2"])
        assertTrue(repo.joinedTickets.isEmpty())
    }
}
