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

package io.bosonnetwork.photon.feature.onboarding

import android.content.Context
import io.bosonnetwork.Id
import io.bosonnetwork.crypto.KeyBackup
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.photon.feature.onboarding.data.AuthRepository
import io.bosonnetwork.photon.feature.onboarding.data.SessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val key = KeyBackup.encode(Signature.KeyPair.random().privateKey().bytes())
    private val userId = Id.random().toString()
    private val repository = mockk<AuthRepository>(relaxed = true) {
        every { newIdentity() } returns userId
        every { pendingIdentityKeyBase58() } returns key
        coEvery { currentDirectorUrl() } returns ""
        coEvery { currentDirectorNodeId() } returns ""
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getString(any()) } returns "message"
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun groupsOf(challenge: KeyBackup.Challenge): Pair<String, String> {
        val groups = KeyBackup.groups(key)
        return groups[challenge.first] to groups[challenge.second]
    }

    @Test
    fun `a new identity goes through its backup and check to the home node`() {
        val vm = OnboardingViewModel(repository, context)
        vm.chooseCreate()
        assertEquals(OnboardingStep.NewIdentity, vm.uiState.value.step)
        assertEquals(userId, vm.uiState.value.newUserId)

        vm.continueToBackup()
        assertEquals(key, vm.uiState.value.backupKeyBase58)
        vm.continueFromBackup()
        val challenge = vm.uiState.value.challenge
        assertNotNull(challenge)

        vm.checkBackup("nope", "nope")
        assertTrue(vm.uiState.value.checkFailed)
        assertEquals(OnboardingStep.CheckBackup, vm.uiState.value.step)

        val (first, second) = groupsOf(challenge!!)
        vm.checkBackup(first, second)
        assertEquals(OnboardingStep.Server, vm.uiState.value.step)
        assertTrue(vm.uiState.value.backupChecked)
        assertNull("the key leaves the screen state once checked", vm.uiState.value.backupKeyBase58)
    }

    @Test
    fun `checking later goes on, and the registration says the backup is unchecked`() {
        coEvery { repository.powAvailable() } returns true
        coEvery { repository.createAccountWithPow(any(), any(), any()) } returns SessionState.Authenticated(userId)
        val vm = OnboardingViewModel(repository, context)
        vm.chooseCreate()
        vm.continueToBackup()
        vm.continueFromBackup()
        vm.checkLater()
        assertFalse(vm.uiState.value.backupChecked)

        vm.onServerUrlChange("https://node.example.org")
        vm.confirmServer()
        assertEquals(OnboardingStep.CreateProfile, vm.uiState.value.step)
        vm.onDisplayNameChange("Alice")
        vm.createAccount()
        coVerify { repository.createAccountWithPow("Alice", "", false) }
        assertEquals(OnboardingStep.Authenticated, vm.uiState.value.step)
    }

    @Test
    fun `a node that takes no new accounts keeps the user on the node step`() {
        coEvery { repository.powAvailable() } returns false
        val vm = OnboardingViewModel(repository, context)
        vm.chooseCreate()
        vm.checkLater()
        vm.onServerUrlChange("https://node.example.org")
        vm.confirmServer()
        assertEquals(OnboardingStep.Server, vm.uiState.value.step)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `an existing identity reaches the ways in once its node answers`() {
        coEvery { repository.reachNode() } returns Id.random().toString()
        val vm = OnboardingViewModel(repository, context)
        vm.chooseExisting()
        vm.onServerUrlChange("https://node.example.org")
        vm.confirmServer()
        assertEquals(OnboardingStep.ChooseWay, vm.uiState.value.step)
    }

    @Test
    fun `a code of another kind scanned at the node step is named, not taken`() {
        val vm = OnboardingViewModel(repository, context)
        vm.chooseExisting()
        vm.scanServerQr()
        vm.onServerQrScanned("did:boson:$userId")
        assertEquals(OnboardingStep.ScanServer, vm.uiState.value.step)
        assertNotNull(vm.uiState.value.error)

        vm.onServerQrScanned("boson:supernode:1:$userId:https://node.example.org")
        assertEquals(OnboardingStep.Server, vm.uiState.value.step)
        assertEquals("https://node.example.org", vm.uiState.value.serverUrl)
        assertEquals(userId, vm.uiState.value.directorNodeId)
    }

    @Test
    fun `a backup that is no key is refused before it reaches the repository`() {
        val vm = OnboardingViewModel(repository, context)
        vm.chooseImport()
        vm.scanBackup()
        vm.onBackupScanned("boson:supernode:1:$userId:https://node.example.org")
        assertNotNull(vm.uiState.value.error)
        coVerify(exactly = 0) { repository.importBackup(any()) }
    }

    @Test
    fun `back leaves a new identity and forgets it`() {
        val vm = OnboardingViewModel(repository, context)
        vm.chooseCreate()
        assertTrue(vm.canGoBack())
        vm.back()
        assertEquals(OnboardingStep.Welcome, vm.uiState.value.step)
        verify { repository.dropPendingIdentity() }
        assertFalse(vm.canGoBack())
    }
}
