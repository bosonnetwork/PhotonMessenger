/*
 * Copyright (c) 2026 -      bosonnetwork.io
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

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.bosonnetwork.Id
import io.bosonnetwork.photon.core.boson.UnreadTracker
import io.bosonnetwork.photon.core.designsystem.theme.PhotonTheme
import io.bosonnetwork.photon.core.model.ChannelInviteStore
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.feature.chat.data.ChatRepository
import io.bosonnetwork.photon.feature.chat.data.ForwardPayloadStore
import io.bosonnetwork.photon.feature.chat.data.MediaSaver
import io.bosonnetwork.photon.feature.chat.data.MessageUpdate
import io.bosonnetwork.photon.feature.chat.data.VoicePlayer
import io.bosonnetwork.photon.feature.chat.data.VoiceRecorder
import io.bosonnetwork.photon.feature.chat.model.ChatHeader
import io.bosonnetwork.photon.feature.chat.model.UiMessage
import io.bosonnetwork.photonmessaging.DeliveryStatus
import io.bosonnetwork.photonmessaging.MessageDeliveryReceipt
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises the actual screen with controlled outbox/ack ordering and no live services. */
class MessageHandoffUiTest {
    @get:Rule
    val compose = createComposeRule()

    /** Unused service operations fail immediately; the exercised operations are implemented below. */
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "Unused ${T::class.java.simpleName}"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected test service operation: ${method.name}")
        }
    } as T

    @Test
    fun replacingThePendingBubbleDoesNotFadeTheAcceptedMessage() = checkHandoff(historySize = 1)

    @Test
    fun confirmationAndReceiptKeepTheScrollableViewportStable() = checkHandoff(historySize = 30)

    private fun checkHandoff(historySize: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val events = MutableSharedFlow<MessageUpdate>(replay = 1)
        events.tryEmit(MessageUpdate.Initial(List(historySize) { index ->
            UiMessage("older-$index", "Earlier message $index", false, (index + 1L) * 1_000L)
        }))
        val ack = CompletableDeferred<Unit>()
        val acceptedId = Id.random()
        val repository = object : ChatRepository by unused<ChatRepository>() {
            override fun messages(conversationId: String) = events
            override suspend fun header(conversationId: String) = Result.success(ChatHeader("Alice"))
            override suspend fun loadOlder(conversationId: String, before: Long, limit: Int) = Result.success(emptyList<UiMessage>())
            override suspend fun sendText(recipientId: String, text: String): Result<Unit> {
                ack.await()
                return Result.success(Unit)
            }
        }
        val unread = object : UnreadTracker by unused<UnreadTracker>() {
            override fun setActiveConversation(conversationId: String?) = Unit
        }
        val profiles = object : ProfileResolver by unused<ProfileResolver>() {
            override fun profile(userId: String) = flowOf(null)
        }
        val recorder = object : VoiceRecorder by unused<VoiceRecorder>() {
            override val isSupported = false
            override fun cancel() = Unit
        }
        val invites = object : ChannelInviteStore by unused<ChannelInviteStore>() {
            override fun actions() = flowOf(emptyMap<String, io.bosonnetwork.photon.core.model.InviteAction>())
        }
        val models = ViewModelStore()
        lateinit var vm: ChatViewModel
        compose.runOnUiThread {
            vm = ChatViewModel(repository, unread, profiles, unused<MediaSaver>(), ForwardPayloadStore(),
                recorder, VoicePlayer(), invites, context, SavedStateHandle(mapOf("conversationId" to "peer")))
            models.put("chat", vm)
        }
        try {
            compose.setContent { PhotonTheme(darkTheme = false) { ChatScreen(onBack = {}, viewModel = vm) } }
            val lastHistoryText = "Earlier message ${historySize - 1}"
            compose.onNodeWithText(lastHistoryText).assertIsDisplayed()
            compose.runOnIdle { vm.send("Flicker check") }
            compose.onNodeWithText("Flicker check").assertIsDisplayed()
            val historyTop = compose.onNodeWithText(lastHistoryText).fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                events.tryEmit(MessageUpdate.Received(UiMessage(acceptedId.toString(), "Flicker check", true, System.currentTimeMillis() + 1_000L)))
                ack.complete(Unit)
            }
            compose.waitUntil(5_000L) {
                val messages = vm.uiState.value.messages
                messages.any { it.id == acceptedId.toString() } && messages.none { it.id.startsWith("pending-") }
            }
            compose.mainClock.advanceTimeByFrame()
            val accepted = compose.onNode(hasText("Flicker check") and hasContentDescription(context.getString(R.string.chat_status_sent)))
            accepted.assertIsDisplayed()
            val image = accepted.captureToImage()
            val background = image.toPixelMap()[image.width / 2, image.height - 3]
            assertEquals("Accepted bubble faded during the pending-to-confirmed handoff", 0xFF0E7490.toInt(), background.toArgb())
            val oldRow = compose.onNodeWithText(lastHistoryText).captureToImage()
            assertEquals("Existing history faded during confirmation", 0xFFE4E8F3.toInt(),
                oldRow.toPixelMap()[oldRow.width / 2, oldRow.height - 3].toArgb())
            compose.runOnIdle {
                events.tryEmit(MessageUpdate.Receipt(MessageDeliveryReceipt(acceptedId, DeliveryStatus.DELIVERED, null,
                    System.currentTimeMillis() + 2_000L)))
            }
            compose.waitUntil(5_000L) {
                vm.uiState.value.messages.lastOrNull()?.deliveryReceipt?.status == DeliveryStatus.DELIVERED
            }
            repeat(3) {
                compose.mainClock.advanceTimeByFrame()
                val delivered = compose.onNode(hasText("Flicker check") and hasContentDescription(context.getString(R.string.chat_status_delivered)))
                val frame = delivered.captureToImage()
                assertEquals("Receipt update faded the accepted bubble", 0xFF0E7490.toInt(),
                    frame.toPixelMap()[frame.width / 2, frame.height - 3].toArgb())
                if (historySize > 1) {
                    val top = compose.onNodeWithText(lastHistoryText).fetchSemanticsNode().boundsInRoot.top
                    assertEquals("Confirmation/receipt shifted the existing message list", historyTop, top, 0.5f)
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { models.clear() }
        }
    }
}
