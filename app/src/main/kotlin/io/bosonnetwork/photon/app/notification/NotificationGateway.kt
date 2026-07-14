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

import android.app.Notification

/**
 * Abstraction over how the app surfaces notifications, so the v1 foreground-service + local
 * notifications path (spec 4.7) and a future FCM data-push path can share call sites (M1-18).
 */
interface NotificationGateway {
    /** Creates notification channels (idempotent). Call once on app start. */
    fun ensureChannels()

    /** The ongoing notification shown while the foreground messaging service holds the connection. */
    fun foregroundNotification(contentText: String): Notification

    /**
     * Posts a local notification for an incoming message (used now; the FCM path reuses this).
     * [number] is the app-icon badge count to carry (0 = no count, badge dot only).
     */
    fun showMessage(conversationKey: String, title: String, body: String, number: Int = 0)

    /** Posts a local notification for an incoming friend request. */
    fun showFriendRequest(requestKey: String, title: String, body: String)
}
