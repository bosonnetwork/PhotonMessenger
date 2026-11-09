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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.bosonnetwork.Id
import io.bosonnetwork.json.Json
import io.bosonnetwork.photon.core.boson.BosonClientFactory
import io.bosonnetwork.photon.core.boson.ChannelInvite
import io.bosonnetwork.photon.core.database.MessagingStoreFactory
import io.bosonnetwork.photon.core.designsystem.theme.PhotonTheme
import io.bosonnetwork.photon.feature.chat.data.VoicePlayer
import io.bosonnetwork.photon.feature.chat.model.AttachmentKind
import io.bosonnetwork.photon.feature.chat.model.AttachmentSource
import io.bosonnetwork.photon.feature.chat.model.MessageStatus
import io.bosonnetwork.photon.feature.chat.model.UiAttachment
import io.bosonnetwork.photon.feature.chat.model.UiConversation
import io.bosonnetwork.photon.feature.chat.model.UiDeliveryReceipt
import io.bosonnetwork.photon.feature.chat.model.UiMessage
import io.bosonnetwork.photon.feature.chat.model.toUi
import io.bosonnetwork.photonmessaging.DeliveryStatus
import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageDeliveryReceipt
import io.bosonnetwork.photonmessaging.MessagingStore.StoredMessage
import io.bosonnetwork.photonmessaging.impl.MessageContent
import io.bosonnetwork.photonmessaging.impl.Notification
import io.bosonnetwork.photonmessaging.impl.PhotonMessage
import io.vertx.core.Future
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class DeliveryUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val message = UiMessage("message", "Hello", true, 1_000L)

    private fun localizedContext(locale: Locale): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }

    private fun content(locale: Locale = Locale.ENGLISH, dark: Boolean = false, body: @Composable () -> Unit) {
        val localized = localizedContext(locale)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
            ) {
                PhotonTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) { body() }
                }
            }
        }
    }

    @Composable
    private fun Bubble(value: UiMessage, channel: Boolean = false, onRetry: (UiMessage) -> Unit = {}) {
        MessageBubble(
            message = value, isChannel = channel, showSender = false,
            download = null, playback = VoicePlayer.Playback(), inviteAction = null,
            onDownload = {}, onJoinInvite = {}, onIgnoreInvite = {}, onRetry = onRetry,
            onForward = {}, onDelete = {}, onSaveAs = {}, onOpen = {}, onImageClick = {}, onToggleVoice = {},
        )
    }

    private fun receipt(status: DeliveryStatus) = UiDeliveryReceipt(status, "raw server diagnostics", 123L)
    private fun english(id: Int) = localizedContext(Locale.ENGLISH).getString(id)

    @Test
    fun submissionAndServiceAcceptanceHaveDistinctAccessibleIndicators() {
        var value by mutableStateOf(message.copy(status = MessageStatus.SENDING))
        content { Bubble(value) }
        compose.onNodeWithContentDescription(english(R.string.chat_status_sending), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { value = message }
        compose.onNodeWithContentDescription(english(R.string.chat_status_sent), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { value = message.copy(deliveryReceipt = receipt(DeliveryStatus.DELIVERED)) }
        compose.onNodeWithContentDescription(english(R.string.chat_status_delivered), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(english(R.string.chat_send_unconfirmed_tap_retry)).assertDoesNotExist()
    }

    @Test
    fun localSubmissionFailureKeepsItsRetryAction() {
        var retries = 0
        content { Bubble(message.copy(status = MessageStatus.FAILED), onRetry = { retries++ }) }
        compose.onNodeWithText(english(R.string.chat_send_unconfirmed_tap_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun terminalOutcomesUseLocalizedExplanationsWithoutRetryOrRawReasons() {
        var value by mutableStateOf(message)
        var retries = 0
        content { Bubble(value, onRetry = { retries++ }) }
        for (status in DeliveryStatus.entries.filter { it != DeliveryStatus.DELIVERED }) {
            compose.runOnIdle { value = message.copy(status = MessageStatus.FAILED, deliveryReceipt = receipt(status)) }
            val text = english(status.failureTextRes()!!)
            compose.onNodeWithText(text).assertIsDisplayed().assertHasNoClickAction().performTouchInput { click() }
            compose.onNodeWithContentDescription(text, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(english(R.string.chat_send_unconfirmed_tap_retry)).assertDoesNotExist()
            compose.onNodeWithText("raw server diagnostics").assertDoesNotExist()
        }
        assertEquals(0, retries)
    }

    @Test
    fun incomingMessagesHaveNoOutgoingIndicatorOrFailureText() {
        content { Bubble(message.copy(fromMe = false, status = MessageStatus.FAILED, deliveryReceipt = receipt(DeliveryStatus.EXPIRED))) }
        compose.onNodeWithText("Hello").assertIsDisplayed()
        compose.onNodeWithContentDescription(english(R.string.chat_delivery_expired), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(english(R.string.chat_delivery_expired)).assertDoesNotExist()
        compose.onNodeWithText(english(R.string.chat_send_unconfirmed_tap_retry)).assertDoesNotExist()
    }

    @Test
    fun channelAcceptanceDoesNotClaimDeliveryToEveryMember() {
        content { Bubble(message.copy(deliveryReceipt = receipt(DeliveryStatus.DELIVERED)), channel = true) }
        compose.onNodeWithContentDescription(english(R.string.chat_status_delivered_channel), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(english(R.string.chat_status_delivered), useUnmergedTree = true).assertDoesNotExist()
    }

    private fun imageBytes(): ByteArray = ByteArrayOutputStream().use { out ->
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF0E7490.toInt())
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }

    @Test
    fun textImageFileVoiceAndInviteShareTheReceiptIndicator() {
        val samples = listOf(
            message,
            message.copy(text = "", attachment = UiAttachment(AttachmentKind.IMAGE, "image/png", "photo.png", 16L,
                source = AttachmentSource.Inline(imageBytes()), width = 16, height = 16)),
            message.copy(text = "", attachment = UiAttachment(AttachmentKind.FILE, "application/pdf", "file.pdf", 1L,
                source = AttachmentSource.Inline(byteArrayOf(1)))),
            message.copy(text = "", attachment = UiAttachment(AttachmentKind.VOICE, "audio/ogg", "voice.ogg", 1L,
                source = AttachmentSource.Inline(byteArrayOf(1)), durationMs = 4_000L)),
            message.copy(text = "", invite = ChannelInvite(byteArrayOf(1), "Example channel", Long.MAX_VALUE)),
        )
        var value by mutableStateOf(message)
        content { Bubble(value) }
        for (sample in samples) {
            for (status in listOf(DeliveryStatus.DELIVERED, DeliveryStatus.EXPIRED)) {
                compose.runOnIdle { value = sample.copy(deliveryReceipt = receipt(status)) }
                val description = if (status == DeliveryStatus.DELIVERED) R.string.chat_status_delivered else R.string.chat_delivery_expired
                compose.onNodeWithContentDescription(english(description), useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText(english(R.string.chat_send_unconfirmed_tap_retry)).assertDoesNotExist()
            }
        }
    }

    @Test
    fun conversationKeepsItsPreviewAndShowsOnlyTheLatestOutgoingOutcome() {
        var value by mutableStateOf(UiConversation("peer", "Alice", "Keep this preview", false, 1_000L,
            lastMessageId = "message", lastMessageFromMe = true, deliveryReceipt = receipt(DeliveryStatus.EXPIRED)))
        content { ConversationRow(value, onClick = {}, onDelete = {}) }
        compose.onNodeWithText("Keep this preview").assertIsDisplayed()
        compose.onNodeWithContentDescription(english(R.string.chat_delivery_expired), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { value = value.copy(lastMessageFromMe = false, deliveryReceipt = null) }
        compose.onNodeWithContentDescription(english(R.string.chat_delivery_expired), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Keep this preview").assertIsDisplayed()
    }

    @Test
    fun simplifiedChineseUsesLocalizedExpiryWithoutRetry() {
        val chinese = localizedContext(Locale.SIMPLIFIED_CHINESE).getString(R.string.chat_delivery_expired)
        assertNotEquals(english(R.string.chat_delivery_expired), chinese)
        content(locale = Locale.SIMPLIFIED_CHINESE) { Bubble(message.copy(deliveryReceipt = receipt(DeliveryStatus.EXPIRED))) }
        compose.onNodeWithText(chinese).assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithContentDescription(chinese, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun <T> Future<T>.blockingResult(): T = toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)

    /** Uses the same SDK message constructor as its store adapter, then the app's public-API mapper. */
    private class RestoredMessage(row: StoredMessage) : PhotonMessage<MessageContent>(
        row.rid(), row.conversationId(), row.version(), row.id(), row.recipient(), Message.Type.valueOf(row.type()),
        row.from(), row.createdAt(), MessageContent.parse(requireNotNull(row.payload())), row.sentAt(), row.receivedAt(),
        row.deliveryStatus(), row.deliveryReason(), row.deliveryAt(),
    )

    @Test
    fun aPlainWireReceiptPersistsAcrossStoreReopenAndRendersWithoutRetry() {
        val vertx = BosonClientFactory.newVertx()
        val name = "receipt-ui-reopen-test.db"
        context.deleteDatabase(name)
        var store = MessagingStoreFactory.create(context, vertx, name)
        try {
            store.open().blockingResult()
            val id = Id.random()
            val user = Id.random()
            val peer = Id.random()
            store.putMessage(StoredMessage(0L, id, peer, 2, peer, Message.Type.CONTENT_MESSAGE.value(), user,
                1_000L, "text/plain", null, Json.toBytes(MessageContent.text("Persisted hello")), 1_000L,
                1_000L, DeliveryStatus.UNDELIVERED, null, 0L)).blockingResult()
            val notification = Notification.parse(Json.toBytes(mapOf(
                "id" to Id.random(), "s" to Id.random(), "t" to 123L, "e" to "dr",
                "b" to mapOf("id" to id, "s" to DeliveryStatus.EXPIRED.value(), "r" to "expired", "t" to 456L),
            )))
            val parsed: MessageDeliveryReceipt = notification.getBody()
            store.updateMessageDeliveryStatus(parsed.messageId, parsed.status, parsed.reason, parsed.timestamp).blockingResult()
            store.close().blockingResult()
            store = MessagingStoreFactory.create(context, vertx, name)
            store.open().blockingResult()
            val restored = RestoredMessage(store.getMessagesBefore(peer, Long.MAX_VALUE, 50, 0).blockingResult().single()).toUi(user)
            assertEquals(DeliveryStatus.EXPIRED, restored.deliveryReceipt?.status)
            content { Bubble(restored) }
            compose.onNodeWithText("Persisted hello").assertIsDisplayed()
            compose.onNodeWithText(english(R.string.chat_delivery_expired)).assertIsDisplayed().assertHasNoClickAction()
            compose.onNodeWithText(english(R.string.chat_send_unconfirmed_tap_retry)).assertDoesNotExist()
        } finally {
            store.close().blockingResult()
            vertx.close().blockingResult()
            context.deleteDatabase(name)
        }
    }

    private fun gallery(dark: Boolean, locale: Locale = Locale.ENGLISH) {
        content(locale = locale, dark = dark) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Delivery receipts", style = MaterialTheme.typography.titleMedium)
                ConversationRow(UiConversation("peer", "Alice", "Keep the conversation preview", false, 1_000L,
                    lastMessageId = "message", lastMessageFromMe = true, deliveryReceipt = receipt(DeliveryStatus.EXPIRED)),
                    onClick = {}, onDelete = {})
                Bubble(message.copy(text = "Accepted by home service"))
                Bubble(message.copy(text = "Delivered to service", deliveryReceipt = receipt(DeliveryStatus.DELIVERED)))
                Bubble(message.copy(text = "Are you there?", deliveryReceipt = receipt(DeliveryStatus.EXPIRED)))
                Bubble(message.copy(text = "Hello again", deliveryReceipt = receipt(DeliveryStatus.FEDERATION_REFUSED)))
                Bubble(message.copy(text = "Trying to send", status = MessageStatus.FAILED))
                Bubble(message.copy(text = "", attachment = UiAttachment(AttachmentKind.VOICE, "audio/ogg", "voice.ogg", 1L,
                    source = AttachmentSource.Inline(byteArrayOf(1)), durationMs = 4_000L), deliveryReceipt = receipt(DeliveryStatus.DELIVERED)))
            }
        }
        compose.waitForIdle()
        val suffix = if (locale.language == "zh") "zh" else if (dark) "dark" else "light"
        val file = File(context.getExternalFilesDir(null), "delivery-receipts-$suffix.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun lightStatusGallery() = gallery(dark = false)
    @Test fun darkStatusGallery() = gallery(dark = true)
    @Test fun chineseStatusGallery() = gallery(dark = false, locale = Locale.SIMPLIFIED_CHINESE)
}
