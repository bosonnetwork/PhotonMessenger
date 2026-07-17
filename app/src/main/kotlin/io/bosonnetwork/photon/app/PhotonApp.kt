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

package io.bosonnetwork.photon.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import io.bosonnetwork.photon.app.image.DirectorImageCallFactory
import io.bosonnetwork.photon.app.notification.NotificationSettings
import io.bosonnetwork.photon.app.session.NetworkMonitor
import io.bosonnetwork.photon.app.session.SessionController
import io.bosonnetwork.photon.core.network.NotificationPreferencesStore
import io.bosonnetwork.photon.core.security.ProfileManager
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

@HiltAndroidApp
class PhotonApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var notificationPreferencesStore: NotificationPreferencesStore

    @Inject
    lateinit var imageCallFactory: DirectorImageCallFactory

    @Inject
    lateinit var notificationSettings: NotificationSettings

    @Inject
    lateinit var foregroundState: AppForegroundState

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var sessionController: SessionController

    @Inject
    lateinit var profileManager: ProfileManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Keep the synchronous notification snapshot current so the notification path (M6-6) can read
        // preferences without blocking on DataStore.
        notificationPreferencesStore.preferences
            .onEach { notificationSettings.update(it) }
            .launchIn(appScope)

        // Track foreground state so the message notifier can suppress notifications while the app is
        // visible (F3): the in-app UI already shows incoming messages live.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = foregroundState.activityStarted()
            override fun onActivityStopped(activity: Activity) = foregroundState.activityStopped()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // Retry a bring-up that failed while offline once the network returns (R1 / M1-19).
        networkMonitor.register { sessionController.onNetworkAvailable() }
    }

    /**
     * App-wide Coil loader: avatar fetches go through the Director-pinned, authenticated HTTP stack
     * (remote users' avatars require the CWT). Coil respects the endpoint's cache headers, so its
     * disk cache + conditional GETs handle avatar-image persistence. The disk cache is per-profile so
     * a signed-out profile's cached avatars never surface under a different active profile.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .callFactory(imageCallFactory)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(profileManager.activeCacheRoot(), "image_cache"))
                    .build()
            }
            .crossfade(true)
            .build()
}
