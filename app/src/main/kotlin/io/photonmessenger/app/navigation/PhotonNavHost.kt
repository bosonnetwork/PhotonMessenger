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

package io.photonmessenger.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.photonmessenger.feature.chat.ChatScreen
import io.photonmessenger.feature.chat.ConversationsScreen
import io.photonmessenger.feature.contacts.ChannelDetailScreen
import io.photonmessenger.feature.contacts.ContactsScreen
import io.photonmessenger.feature.contacts.CreateChannelScreen
import io.photonmessenger.feature.onboarding.OnboardingScreen
import io.photonmessenger.feature.settings.ApproveDeviceScreen
import io.photonmessenger.feature.settings.DevicesScreen
import io.photonmessenger.feature.settings.PairNewDeviceScreen
import io.photonmessenger.feature.settings.SettingsScreen

/**
 * Root navigation. For M0 the app starts at Home with an empty Conversations list (M0 DoD).
 * The onboarding route exists and becomes the gated start destination in M1 once auth state
 * controls entry.
 */
@Composable
fun PhotonNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val topLevel = TopLevelDestination.entries
    val currentRoute = currentDestination?.route
    val showBottomBar = topLevel.any { it.route == currentRoute }

    Scaffold(
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
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onAuthenticated = {
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
                )
            }
            composable(TopLevelDestination.CONTACTS.route) {
                ContactsScreen(
                    onOpenChannel = { id -> navController.navigate("channel/$id") },
                    onCreateChannel = { navController.navigate(Routes.CREATE_CHANNEL) },
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onOpenDevices = { navController.navigate(Routes.DEVICES) },
                    onSignedOut = {
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
                )
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
                ChatScreen(onBack = { navController.popBackStack() })
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
                ChannelDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
