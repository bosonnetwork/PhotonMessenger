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
import io.photonmessenger.core.boson.UnreadTracker
import io.photonmessenger.core.boson.awaitResult
import io.photonmessenger.core.model.ProfileResolver
import io.photonmessenger.core.model.cachedDisplay
import io.photonmessenger.core.model.displayProfile
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageListener
import io.bosonnetwork.photonmessaging.MessagingClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private val unreadTracker: UnreadTracker,
    private val profileResolver: ProfileResolver,
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
            val name = resolveSenderName(current, from)
            // Badge count from the synchronous unread map (not the async-derived totalUnread, whose
            // .value can lag). Read here, inside the coroutine: by now every synchronous MessageListener
            // - including UnreadTracker's, which counts this message - has already run.
            val badgeCount = unreadTracker.unread.value.values.sum()
            gateway.showMessage(conversationKey, name, body, badgeCount)
        }
    }

    /**
     * Sender display name via the shared identity policy: remark > library profile name >
     * Director-resolved name > short id. A local name resolves synchronously; otherwise the Director
     * lookup is bounded so a slow or unknown profile still notifies promptly (falling back to the
     * short id).
     */
    private suspend fun resolveSenderName(client: MessagingClient, from: Id): String {
        val contact = runCatching { client.getContact(from).awaitResult().orElse(null) }.getOrNull()
        val localName = contact?.remark?.orElse(null)?.takeIf { it.isNotBlank() }
            ?: contact?.name?.orElse(null)?.takeIf { it.isNotBlank() }

        val id = from.toString()
        val cached = profileResolver.cachedDisplay(id, localName)
        if (!cached.nameIsFallback) return cached.displayName

        // No local or cached name yet: wait briefly for a resolved (non-fallback) name, else short id.
        return withTimeoutOrNull(PROFILE_RESOLVE_TIMEOUT_MS) {
            profileResolver.displayProfile(id, localName).first { !it.nameIsFallback }.displayName
        } ?: cached.displayName
    }

    private fun previewOf(message: Message): String {
        val content = runCatching { message.payloadAsContent }.getOrNull() ?: return "New message"
        val hasAttachment = content.contentDisposition.orElse(null) != null
        if (hasAttachment) return "Sent an attachment"
        return runCatching { content.asText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "New message"
    }

    private companion object {
        const val PROFILE_RESOLVE_TIMEOUT_MS = 2_000L
    }
}
