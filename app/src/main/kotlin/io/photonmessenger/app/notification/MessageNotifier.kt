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

package io.photonmessenger.app.notification

import io.photonmessenger.app.AppForegroundState
import io.photonmessenger.core.boson.awaitResult
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageListener
import io.bosonnetwork.photonmessaging.MessagingClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridges live incoming messages to local notifications (F3 / M6-6). [attach] is called once the
 * session is connected and [detach] on sign-out; while attached, every inbound message from another
 * user raises a notification via [NotificationGateway] (which honours the master + preview settings).
 * Notifications are suppressed while the app is in the foreground - the in-app UI shows them live.
 */
@Singleton
class MessageNotifier @Inject constructor(
    private val gateway: NotificationGateway,
    private val foreground: AppForegroundState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var client: MessagingClient? = null

    private val listener = object : MessageListener {
        override fun onMessage(message: Message) = notify(message)
        override fun onSent(message: Message) = Unit
    }

    /** Starts notifying for [client]'s inbound messages. Replaces any previous attachment. */
    fun attach(client: MessagingClient) {
        detach()
        this.client = client
        client.addMessageListener(listener)
    }

    fun detach() {
        client?.removeMessageListener(listener)
        client = null
    }

    private fun notify(message: Message) {
        val current = client ?: return
        val from = message.from.orElse(null) ?: return
        // Skip our own echoes and anything while the user is actively in the app.
        if (from == current.userId || foreground.isForeground) return

        val conversationKey = message.conversationId.orElse(from).toString()
        val body = previewOf(message)

        // Resolve a friendly sender name off the event loop; fall back to a short id.
        scope.launch {
            val name = runCatching { current.getContact(from).awaitResult().orElse(null) }
                .getOrNull()?.name?.orElse(null)?.takeIf { it.isNotBlank() }
                ?: shortId(from)
            gateway.showMessage(conversationKey, name, body)
        }
    }

    private fun previewOf(message: Message): String {
        val content = runCatching { message.payloadAsContent }.getOrNull() ?: return "New message"
        val hasAttachment = content.contentDisposition.orElse(null) != null
        if (hasAttachment) return "Sent an attachment"
        return runCatching { content.asText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "New message"
    }

    private fun shortId(id: Id): String {
        val s = id.toString()
        return if (s.length <= 14) s else s.take(8) + "..." + s.takeLast(4)
    }
}
