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

package io.bosonnetwork.photon.feature.settings

import app.cash.turbine.test
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.NotificationPreferences
import io.bosonnetwork.photon.core.model.ThemeMode
import io.bosonnetwork.photon.core.model.ThemePreferences
import io.bosonnetwork.photon.feature.settings.data.SettingsRepository
import io.bosonnetwork.photon.feature.settings.model.UiDevice
import io.bosonnetwork.photon.feature.settings.model.UiProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsViewModelsTest {

    private val themeFlow = MutableStateFlow(ThemePreferences())

    private class FakeSettingsRepo(
        private val theme: Flow<ThemePreferences>,
        var profile: Result<UiProfile> = Result.success(SAMPLE_PROFILE),
        var devices: Result<List<UiDevice>> = Result.success(emptyList()),
        var actionResult: Result<Unit> = Result.success(Unit),
        var removeResult: Result<Unit> = Result.success(Unit),
        var passphraseResult: Result<Unit> = Result.success(Unit),
    ) : SettingsRepository {
        var lastThemeMode: ThemeMode? = null
        var notificationsEnabled: Boolean? = null
        var signedOut = false
        var revoked: String? = null
        var removed: String? = null
        var removedPassphrase: String? = null
        var setPassphraseArgs: Pair<String, String?>? = null
        var clearedPassphrase: String? = null

        override val themePreferences = theme
        override val notificationPreferences = MutableStateFlow(NotificationPreferences())
        override suspend fun loadProfile() = profile
        override suspend fun updateProfile(name: String?, bio: String?, email: String?, passphrase: String?) =
            actionResult
        override suspend fun updateAvatar(uriString: String) = actionResult
        override suspend fun removeAvatar() = actionResult
        override suspend fun setPassphrase(newPassphrase: String, currentPassphrase: String?) =
            passphraseResult.also { setPassphraseArgs = newPassphrase to currentPassphrase }
        override suspend fun clearPassphrase(currentPassphrase: String) =
            passphraseResult.also { clearedPassphrase = currentPassphrase }
        override suspend fun loadDevices() = devices
        override suspend fun revokeSession(deviceId: String) = actionResult.also { revoked = deviceId }
        override suspend fun removeDevice(deviceId: String, passphrase: String?) =
            removeResult.also { removed = deviceId; removedPassphrase = passphrase }
        override suspend fun setThemeMode(mode: ThemeMode) = actionResult.also { lastThemeMode = mode }
        override suspend fun setDynamicColor(enabled: Boolean) = actionResult
        override suspend fun setNotificationsEnabled(enabled: Boolean) =
            actionResult.also { notificationsEnabled = enabled }
        override suspend fun setNotificationPreview(showPreview: Boolean) = actionResult
        override suspend fun signOut() = actionResult.also { signedOut = true }

        companion object {
            val SAMPLE_PROFILE = UiProfile("id1", "Alice", "hi there", "a@b.co", null, "Free")
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `profile loads into state`() = runTest {
        val vm = SettingsViewModel(FakeSettingsRepo(themeFlow))
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertFalse(state.loading)
            assertEquals("Alice", state.profile?.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `theme changes are forwarded to the repository`() = runTest {
        val repo = FakeSettingsRepo(themeFlow)
        val vm = SettingsViewModel(repo)
        vm.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repo.lastThemeMode)
    }

    @Test
    fun `notification toggle is forwarded to the repository`() = runTest {
        val repo = FakeSettingsRepo(themeFlow)
        val vm = SettingsViewModel(repo)
        vm.setNotificationsEnabled(false)
        assertEquals(false, repo.notificationsEnabled)
    }

    @Test
    fun `sign out emits a signed-out event`() = runTest {
        val repo = FakeSettingsRepo(themeFlow)
        val vm = SettingsViewModel(repo)
        vm.signedOut.test {
            vm.signOut()
            awaitItem()
            assertTrue(repo.signedOut)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed profile save emits a message`() = runTest {
        val repo = FakeSettingsRepo(themeFlow, actionResult = Result.failure(IllegalStateException("nope")))
        val vm = SettingsViewModel(repo)
        vm.messages.test {
            vm.saveProfile("a", "b", "c")
            assertTrue(awaitItem().contains("nope"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `devices load into state`() = runTest {
        val device = UiDevice("d1", "Pixel", "PhotonMessenger", true, 100, null, 50, isCurrent = true)
        val repo = FakeSettingsRepo(themeFlow, devices = Result.success(listOf(device)))
        val vm = DevicesViewModel(repo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(listOf("Pixel"), state.devices.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `revoke session calls the repository`() = runTest {
        val repo = FakeSettingsRepo(themeFlow)
        val vm = DevicesViewModel(repo)
        vm.revokeSession("d9")
        assertEquals("d9", repo.revoked)
    }

    @Test
    fun `set passphrase forwards args and signals success`() = runTest {
        val repo = FakeSettingsRepo(themeFlow)
        val vm = SettingsViewModel(repo)
        vm.passphraseUpdated.test {
            vm.setPassphrase("newpass", "oldpass")
            awaitItem()
            assertEquals("newpass" to "oldpass", repo.setPassphraseArgs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `set passphrase failure emits a message`() = runTest {
        val repo = FakeSettingsRepo(
            themeFlow,
            passphraseResult = Result.failure(AppError.Forbidden("Wrong passphrase")),
        )
        val vm = SettingsViewModel(repo)
        vm.messages.test {
            vm.setPassphrase("newpass", "bad")
            assertTrue(awaitItem().contains("Wrong passphrase"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clear passphrase forwards the current passphrase`() = runTest {
        val repo = FakeSettingsRepo(themeFlow)
        val vm = SettingsViewModel(repo)
        vm.passphraseUpdated.test {
            vm.clearPassphrase("oldpass")
            awaitItem()
            assertEquals("oldpass", repo.clearedPassphrase)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `remove device prompts for a passphrase when the server gates it`() = runTest {
        val repo = FakeSettingsRepo(
            themeFlow,
            removeResult = Result.failure(AppError.PassphraseRequired("Passphrase required")),
        )
        val vm = DevicesViewModel(repo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            vm.removeDevice("d1")
            var prompted = awaitItem()
            while (prompted.passphrasePrompt == null) prompted = awaitItem()
            assertEquals("d1", prompted.passphrasePrompt?.deviceId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirm removal with passphrase retries and clears the prompt`() = runTest {
        val repo = FakeSettingsRepo(
            themeFlow,
            removeResult = Result.failure(AppError.PassphraseRequired("Passphrase required")),
        )
        val vm = DevicesViewModel(repo)
        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            vm.removeDevice("d1")
            var prompted = awaitItem()
            while (prompted.passphrasePrompt == null) prompted = awaitItem()

            repo.removeResult = Result.success(Unit)
            vm.confirmRemoveWithPassphrase("secret")
            var cleared = awaitItem()
            while (cleared.passphrasePrompt != null) cleared = awaitItem()
            assertEquals("secret", repo.removedPassphrase)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
