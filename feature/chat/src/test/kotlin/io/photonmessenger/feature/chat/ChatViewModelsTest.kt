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

package io.photonmessenger.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.photonmessenger.feature.chat.data.ChatRepository
import io.photonmessenger.feature.chat.model.ChatHeader
import io.photonmessenger.feature.chat.model.UiConversation
import io.photonmessenger.feature.chat.model.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override fun conversations() = convos
        override suspend fun header(conversationId: String) = headerResult
        override fun messages(conversationId: String) = msgs
        override suspend fun sendText(recipientId: String, text: String) = sendResult
        override suspend fun sendAttachment(recipientId: String, uriString: String) = sendResult
        override suspend fun downloadAttachment(attachment: io.photonmessenger.feature.chat.model.UiAttachment) =
            Result.failure<java.io.File>(UnsupportedOperationException("not used"))
        override suspend fun loadOlder(conversationId: String, before: Long, limit: Int) = olderResult
        override suspend fun removeConversation(conversationId: String) = Result.success(Unit)
    }

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
        val vm = ConversationsViewModel(FakeChatRepo(convosFlow, msgsFlow))

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
        val vm = ChatViewModel(repo, SavedStateHandle(mapOf("conversationId" to "abc")))

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
        val vm = ChatViewModel(repo, SavedStateHandle(mapOf("conversationId" to "chan1")))

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
        val vm = ChatViewModel(repo, SavedStateHandle(mapOf("conversationId" to "abc")))

        vm.sendFailures.test {
            vm.send("hi")
            val failure = awaitItem()
            assertTrue(failure.message.contains("nope"))
            assertEquals("hi", failure.text)
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
        val vm = ChatViewModel(repo, SavedStateHandle(mapOf("conversationId" to "abc")))

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
}
