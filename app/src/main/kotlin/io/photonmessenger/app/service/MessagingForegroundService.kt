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

package io.photonmessenger.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import io.photonmessenger.app.notification.LocalNotificationGateway
import io.photonmessenger.app.notification.NotificationGateway
import io.photonmessenger.core.boson.BosonSessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Holds the live MessagingClient connection while the app is backgrounded (spec 4.7, M1-17). v1 uses
 * a foreground service + local notifications; an FCM data-push path can replace it later via
 * [NotificationGateway]. START_STICKY so the OS restarts it after kills.
 *
 * The actual connect(coords) is driven by the post-login session bring-up; this service keeps the
 * hosting process alive and shows the ongoing notification.
 */
@AndroidEntryPoint
class MessagingForegroundService : Service() {

    @Inject
    lateinit var notificationGateway: NotificationGateway

    @Inject
    lateinit var sessionManager: BosonSessionManager

    override fun onCreate() {
        super.onCreate()
        notificationGateway.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationGateway.foregroundNotification("Connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                LocalNotificationGateway.FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(LocalNotificationGateway.FOREGROUND_NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, MessagingForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MessagingForegroundService::class.java))
        }
    }
}
