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

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.bosonnetwork.photon.app.AppViewModel
import io.bosonnetwork.photon.core.designsystem.component.CountBadge
import io.bosonnetwork.photon.feature.chat.ChatScreen
import io.bosonnetwork.photon.feature.chat.ConversationsScreen
import io.bosonnetwork.photon.feature.chat.ForwardScreen
import io.bosonnetwork.photon.feature.contacts.ChannelDetailScreen
import io.bosonnetwork.photon.feature.contacts.InviteContactPickerScreen
import io.bosonnetwork.photon.feature.contacts.ContactDetailScreen
import io.bosonnetwork.photon.feature.contacts.ContactsScreen
import io.bosonnetwork.photon.feature.contacts.CreateChannelScreen
import io.bosonnetwork.photon.feature.onboarding.OnboardingScreen
import io.bosonnetwork.photon.feature.settings.ApproveDeviceScreen
import io.bosonnetwork.photon.feature.settings.DevicesScreen
import io.bosonnetwork.photon.feature.settings.PairNewDeviceScreen
import io.bosonnetwork.photon.feature.settings.SettingsScreen
import io.bosonnetwork.photon.feature.settings.ShowKeyScreen

/**
 * Root navigation. For M0 the app starts at Home with an empty Conversations list (M0 DoD).
 * The onboarding route exists and becomes the gated start destination in M1 once auth state
 * controls entry.
 */
@Composable
fun PhotonNavHost(
    navController: NavHostController = rememberNavController(),
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val sessionStatus by appViewModel.sessionStatus.collectAsStateWithLifecycle()
    val badges by appViewModel.badges.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val topLevel = TopLevelDestination.entries
    val currentRoute = currentDestination?.route
    val showBottomBar = topLevel.any { it.route == currentRoute }

    Scaffold(
        topBar = {
            ConnectionBanner(
                status = sessionStatus,
                onRetry = appViewModel::retryConnection,
            )
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevel.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == destination.route
                    } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                            icon = {
                                val count = when (destination) {
                                    TopLevelDestination.HOME -> badges.chats
                                    TopLevelDestination.CONTACTS -> badges.contacts
                                    else -> 0
                                }
                                BadgedBox(badge = { CountBadge(count) }) {
                                    Icon(destination.icon, contentDescription = destination.label)
                                }
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = appViewModel.startDestination,
            // Consume the shell padding as insets too, so screen-level TopAppBars don't re-apply
            // the status-bar inset when the connection banner already occupies that space.
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            enterTransition = {
                fadeIn(animationSpec = tween(220)) +
                    slideInHorizontally(animationSpec = tween(220)) { it / 8 }
            },
            exitTransition = { fadeOut(animationSpec = tween(180)) },
            popEnterTransition = { fadeIn(animationSpec = tween(220)) },
            popExitTransition = {
                fadeOut(animationSpec = tween(180)) +
                    slideOutHorizontally(animationSpec = tween(220)) { it / 8 }
            },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onAuthenticated = {
                        appViewModel.onSignedIn()
                        navController.navigate(TopLevelDestination.HOME.route) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(TopLevelDestination.HOME.route) {
                ConversationsScreen(
                    onOpenConversation = { id -> navController.navigate("chat/$id") },
                    onNewChat = {
                        navController.navigate(TopLevelDestination.CONTACTS.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(TopLevelDestination.CONTACTS.route) {
                ContactsScreen(
                    onOpenConversation = { id -> navController.navigate("chat/$id") },
                    onOpenChannel = { id -> navController.navigate("chat/$id") },
                    onOpenContactDetail = { id -> navController.navigate("contact/$id") },
                    onOpenChannelDetail = { id -> navController.navigate("channel/$id") },
                    onCreateChannel = { navController.navigate(Routes.CREATE_CHANNEL) },
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onOpenDevices = { navController.navigate(Routes.DEVICES) },
                    onSignedOut = {
                        appViewModel.onSignedOut()
                        navController.navigate(Routes.ONBOARDING) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.DEVICES) {
                DevicesScreen(
                    onBack = { navController.popBackStack() },
                    onAddDevice = { navController.navigate(Routes.ADD_DEVICE) },
                    onApproveDevice = { navController.navigate(Routes.APPROVE_DEVICE) },
                    onShowKey = { navController.navigate(Routes.SHOW_KEY) },
                )
            }
            composable(Routes.SHOW_KEY) {
                ShowKeyScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ADD_DEVICE) {
                PairNewDeviceScreen(
                    onBack = { navController.popBackStack() },
                    onPaired = { navController.popBackStack() },
                )
            }
            composable(Routes.APPROVE_DEVICE) {
                ApproveDeviceScreen(
                    onBack = { navController.popBackStack() },
                    onFinished = { navController.popBackStack() },
                )
            }
            composable(
                route = "chat/{conversationId}",
                arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
            ) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChannelDetail = { id -> navController.navigate("channel/$id") },
                    onOpenContactDetail = { id -> navController.navigate("contact/$id") },
                    onForwardMessage = { navController.navigate(Routes.FORWARD) },
                    onOpenChannel = { id ->
                        navController.navigate("chat/$id") { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.FORWARD) {
                ForwardScreen(
                    onBack = { navController.popBackStack() },
                    onForwarded = { targetId ->
                        navController.navigate("chat/$targetId") {
                            popUpTo(Routes.FORWARD) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                route = "contact/{contactId}",
                arguments = listOf(navArgument("contactId") { type = NavType.StringType }),
            ) {
                ContactDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { id ->
                        navController.navigate("chat/$id") {
                            popUpTo("contact/{contactId}") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.CREATE_CHANNEL) {
                CreateChannelScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { id ->
                        navController.popBackStack()
                        navController.navigate("channel/$id")
                    },
                )
            }
            composable(
                route = "channel/{channelId}",
                arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
            ) {
                ChannelDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { id ->
                        navController.navigate("chat/$id") {
                            popUpTo("channel/{channelId}") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onInviteContact = { id -> navController.navigate("inviteContact/$id") },
                )
            }
            composable(
                route = "inviteContact/{channelId}",
                arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
            ) {
                InviteContactPickerScreen(
                    onBack = { navController.popBackStack() },
                    onInvited = { navController.popBackStack() },
                )
            }
        }
    }
}
