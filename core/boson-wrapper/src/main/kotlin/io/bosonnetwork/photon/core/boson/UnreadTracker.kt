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

package io.bosonnetwork.photon.core.boson

import io.bosonnetwork.photonmessaging.Message
import io.bosonnetwork.photonmessaging.MessageListener
import io.bosonnetwork.photonmessaging.MessagingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * App-wide unread-message state for conversation-row and bottom-tab badges. The messaging library
 * exposes no unread primitive, so the app counts them itself: an inbound message for a conversation
 * that is not currently on screen bumps that conversation's counter; opening the conversation clears
 * it. Counts are in-memory (they reset on process death; messages queued while the app was offline
 * are re-delivered and re-counted on the next sync).
 */
interface UnreadTracker {
    /** conversationId (base58) -> unread inbound message count. */
    val unread: StateFlow<Map<String, Int>>

    /** Total unread across all conversations (drives the Chats tab badge). */
    val totalUnread: StateFlow<Int>

    /** Clears a conversation's unread count (e.g. when the user opens it). */
    fun markRead(conversationId: String)

    /**
     * Sets the conversation currently on screen; its inbound messages are treated as read (never
     * counted) and its existing count is cleared. Pass null when leaving the chat.
     */
    fun setActiveConversation(conversationId: String?)
}

/**
 * Default [UnreadTracker]: keeps a single always-on [MessageListener] attached to the live
 * [BosonSessionManager] client. A transient reconnect reuses the same client instance so the
 * listener is never doubled; a full disconnect / sign-out builds a new client (or none) and the
 * counts are reset.
 */
class DefaultUnreadTracker(
    private val session: BosonSessionManager,
) : UnreadTracker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    override val totalUnread: StateFlow<Int> =
        _unread.map { counts -> counts.values.sum() }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    @Volatile
    private var activeConversation: String? = null

    @Volatile
    private var attached: MessagingClient? = null

    private val listener = object : MessageListener {
        override fun onMessage(message: Message) {
            val conversationId = message.conversationId.orElse(null)?.toString() ?: return
            // Skip our own messages (multi-device echoes) and the conversation currently on screen.
            val from = message.from.orElse(null)
            if (from != null && from == session.messagingClient?.userId) return
            if (conversationId == activeConversation) return
            _unread.update { it + (conversationId to ((it[conversationId] ?: 0) + 1)) }
        }

        // Outgoing messages are never unread.
        override fun onSent(message: Message) = Unit
    }

    init {
        scope.launch {
            session.connectionState.collect {
                val client = session.messagingClient
                when {
                    client == null -> {
                        // Session torn down (sign-out / full disconnect): drop stale counts.
                        attached = null
                        activeConversation = null
                        _unread.value = emptyMap()
                    }
                    client !== attached -> {
                        // Freshly built client (new sign-in): attach once.
                        client.addMessageListener(listener)
                        attached = client
                    }
                }
            }
        }
    }

    override fun markRead(conversationId: String) {
        _unread.update { if (it.containsKey(conversationId)) it - conversationId else it }
    }

    override fun setActiveConversation(conversationId: String?) {
        activeConversation = conversationId
        if (conversationId != null) markRead(conversationId)
    }
}
