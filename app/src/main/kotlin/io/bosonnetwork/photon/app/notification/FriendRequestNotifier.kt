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

package io.bosonnetwork.photon.app.notification

import io.bosonnetwork.photon.app.AppForegroundState
import io.bosonnetwork.photon.app.R
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.cachedDisplay
import io.bosonnetwork.photon.core.model.displayProfile
import io.bosonnetwork.Id
import io.bosonnetwork.photonmessaging.FriendRequestListener
import io.bosonnetwork.photonmessaging.MessagingClient
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bridges live incoming friend requests to local notifications (companion to [MessageNotifier]).
 * [attach] runs once the session is connected and [detach] on sign-out; while attached, every inbound
 * friend request raises a notification via [NotificationGateway] (which honours the master + preview
 * settings). Notifications are suppressed while the app is in the foreground - the Contacts screen
 * shows pending requests live there.
 */
@Singleton
class FriendRequestNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: NotificationGateway,
    private val foreground: AppForegroundState,
    private val profileResolver: ProfileResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var client: MessagingClient? = null

    private val listener = object : FriendRequestListener {
        override fun onFriendRequest(userId: Id, hello: String) = notify(userId, hello)

        // Acceptance of a request WE sent is not a "new request" event; nothing to notify.
        override fun onFriendRequestAccepted(userId: Id) = Unit
    }

    /** Starts notifying for [client]'s inbound friend requests. Replaces any previous attachment. */
    fun attach(client: MessagingClient) {
        detach()
        this.client = client
        client.addFriendRequestListener(listener)
    }

    fun detach() {
        client?.removeFriendRequestListener(listener)
        client = null
    }

    private fun notify(userId: Id, hello: String) {
        // Suppress while the user is actively in the app; the Contacts screen surfaces requests live.
        if (foreground.isForeground) return

        val key = userId.toString()
        val body = hello.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.app_notif_friend_request_quoted, it) }
            ?: context.getString(R.string.app_notif_friend_request_default_body)

        // The sender is not a contact yet (no local name), so resolve a Director profile name via the
        // shared identity policy off the event loop; bounded so an unknown/slow profile still notifies
        // (falling back to the short id).
        scope.launch {
            val id = userId.toString()
            val cached = profileResolver.cachedDisplay(id)
            val title = if (!cached.nameIsFallback) {
                cached.displayName
            } else {
                withTimeoutOrNull(PROFILE_RESOLVE_TIMEOUT_MS) {
                    profileResolver.displayProfile(id).first { !it.nameIsFallback }.displayName
                } ?: cached.displayName
            }
            gateway.showFriendRequest(key, title, body)
        }
    }

    private companion object {
        const val PROFILE_RESOLVE_TIMEOUT_MS = 2_000L
    }
}
