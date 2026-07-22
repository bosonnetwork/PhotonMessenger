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

package io.bosonnetwork.photon.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import io.bosonnetwork.photon.app.R

/** Top-level navigation destinations (design spec section 5 navigation). */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.app_nav_chats, Icons.Outlined.Chat),
    CONTACTS("contacts", R.string.app_nav_contacts, Icons.Outlined.People),
    SETTINGS("settings", R.string.app_nav_settings, Icons.Outlined.Settings),
}

/** Non-top-level routes. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val CREATE_CHANNEL = "createChannel"
    const val ACCOUNTS = "accounts"
    /** Accounts list opened from onboarding as a returning-user sign-in picker (no delete). */
    const val SIGN_IN_ACCOUNTS = "signInAccounts"
    const val SESSIONS = "sessions"
    const val DEVICES = "devices"
    const val ADD_DEVICE = "addDevice"
    const val APPROVE_DEVICE = "approveDevice"
    const val SHOW_KEY = "showKey"
    const val SHOW_IDENTITY_KEY = "showIdentityKey"
    const val LANGUAGE = "language"
    const val FORWARD = "forward"
}
