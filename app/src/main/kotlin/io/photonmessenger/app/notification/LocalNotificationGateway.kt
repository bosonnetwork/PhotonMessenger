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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.photonmessenger.app.MainActivity
import io.photonmessenger.app.R

/** Local (on-device) NotificationGateway backed by NotificationManager (spec 4.7, M1-18). */
class LocalNotificationGateway(
    private val context: Context,
    private val settings: NotificationSettings,
) : NotificationGateway {

    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Tap action: bring the app to the foreground. */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun ensureChannels() {
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            "Connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps PhotonMessenger connected for new messages" }

        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "New message notifications" }

        manager.createNotificationChannel(service)
        manager.createNotificationChannel(messages)
    }

    override fun foregroundNotification(contentText: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("PhotonMessenger")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun showMessage(conversationKey: String, title: String, body: String) {
        val prefs = settings.current
        if (!prefs.enabled) return

        // When previews are off, hide the sender and content behind a generic message.
        val shownTitle = if (prefs.showPreview) title else "PhotonMessenger"
        val shownBody = if (prefs.showPreview) body else "You have a new message"

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(shownTitle)
            .setContentText(shownBody)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(conversationKey.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_SERVICE = "connection"
        const val CHANNEL_MESSAGES = "messages"
        const val FOREGROUND_NOTIFICATION_ID = 1001
    }
}
