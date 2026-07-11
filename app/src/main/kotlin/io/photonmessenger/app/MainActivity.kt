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

package io.photonmessenger.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.photonmessenger.app.navigation.PhotonNavHost
import io.photonmessenger.core.designsystem.theme.PhotonTheme
import io.photonmessenger.core.model.ThemeMode
import io.photonmessenger.core.model.ThemePreferences
import io.photonmessenger.core.network.ThemePreferencesStore
import io.photonmessenger.feature.onboarding.data.AuthCallback
import io.photonmessenger.feature.onboarding.data.AuthDeepLinkBus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var deepLinkBus: AuthDeepLinkBus

    @Inject
    lateinit var themeStore: ThemePreferencesStore

    // Registered unconditionally (contract must be created before the activity is STARTED). The result
    // needs no handling: if the user declines, the OS simply withholds notifications - honouring their
    // choice - and posting stays a no-op.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()
        handleAuthDeepLink(intent)
        setContent {
            val prefs by themeStore.preferences.collectAsStateWithLifecycle(ThemePreferences())
            val darkTheme = when (prefs.mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            PhotonTheme(darkTheme = darkTheme, dynamicColor = prefs.dynamicColor) {
                PhotonNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthDeepLink(intent)
    }

    /**
     * Asks for POST_NOTIFICATIONS on Android 13+ (the permission does not exist below that, where the
     * manifest grant suffices). Without it the OS drops every notification - message, friend request,
     * and the foreground-service banner alike - so this gates all notification delivery.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Captures photonmessenger://auth?token=... (or ?error=...) from the OAuth Custom Tab (M1-8). */
    private fun handleAuthDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "photonmessenger" || data.host != "auth") return
        val token = data.getQueryParameter("token")
        val error = data.getQueryParameter("error")
        when {
            !token.isNullOrEmpty() -> deepLinkBus.post(AuthCallback.Token(token))
            !error.isNullOrEmpty() -> deepLinkBus.post(AuthCallback.Error(error))
        }
    }
}
