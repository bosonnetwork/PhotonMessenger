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
import io.bosonnetwork.director.client.SuperNodeCode
import io.bosonnetwork.photon.feature.onboarding.data.AuthRepository
import io.bosonnetwork.photon.feature.onboarding.data.SessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    // Boson Identity on this phone: what the view model asks it, as the screen would start them.
    private val identityApp = CoroutineScope(Dispatchers.Main)
    private val nodeId = Id.random()
    private val homeNode = BosonIdentityRequests.Answer.HomeNode("alice", SuperNodeCode.of(nodeId, "https://node.example.org"))

    private fun requestsOf(vm: OnboardingViewModel): List<IdentityAppRequest> {
        val requests = mutableListOf<IdentityAppRequest>()
        identityApp.launch { vm.identityAppRequests.collect { requests += it } }
        return requests
    }

    // An existing identity whose node and pairing come from Boson Identity, waiting for the node's answer.
    private fun pairingInIdentityApp(): Pair<OnboardingViewModel, List<IdentityAppRequest>> {
        coEvery { repository.reachNode() } returns nodeId.toString()
        coEvery { repository.startPairing(any()) } returns "boson:pair:1:reg:key"
        coEvery { repository.finishPairing() } coAnswers { CompletableDeferred<SessionState>().await() }
        val vm = OnboardingViewModel(repository, context)
        val requests = requestsOf(vm)
        vm.chooseExisting()
        vm.useIdentityApp()
        vm.onHomeNodeAnswer(homeNode)
        return vm to requests
    }

    @Test
    fun `Boson Identity's home node is checked, then the pairing is handed to it`() {
        val (vm, requests) = pairingInIdentityApp()
        assertEquals(IdentityAppRequest.HomeNode(null), requests[0])
        assertEquals("https://node.example.org", vm.uiState.value.serverUrl)
        coVerify { repository.setDirectorUrl("https://node.example.org", nodeId.toBase58String()) }
        assertEquals(OnboardingStep.Pairing, vm.uiState.value.step)
        assertTrue(vm.uiState.value.pairingInApp)
        assertEquals(
            IdentityAppRequest.Approve(BosonIdentityRequests.Approval("boson:pair:1:reg:key", "alice")),
            requests[1],
        )
        // Photon is in the background while Boson Identity approves: its answer could be lost there.
        coVerify(exactly = 0) { repository.finishPairing() }
        identityApp.cancel()
    }

    @Test
    fun `an approval in Boson Identity waits for the node, a denial ends the pairing`() {
        val (vm, _) = pairingInIdentityApp()
        vm.onApproveAnswer(BosonIdentityRequests.Answer.Approved("alice"))
        assertTrue(vm.uiState.value.pairingApproved)
        assertEquals(OnboardingStep.Pairing, vm.uiState.value.step)
        // Back in front: the node's answer is waited for now.
        coVerify(exactly = 1) { repository.finishPairing() }

        val (denied, _) = pairingInIdentityApp()
        denied.onApproveAnswer(BosonIdentityRequests.Answer.Refused(BosonIdentityRequests.ERROR_DENIED))
        assertEquals(OnboardingStep.ChooseWay, denied.uiState.value.step)
        assertNotNull(denied.uiState.value.error)
        verify { repository.cancelPairing() }
        identityApp.cancel()
    }

    @Test
    fun `leaving Boson Identity keeps the pairing, to open it again or show the code`() {
        val (vm, requests) = pairingInIdentityApp()
        vm.onApproveAnswer(BosonIdentityRequests.Answer.Refused(BosonIdentityRequests.ERROR_CANCELED))
        assertEquals(OnboardingStep.Pairing, vm.uiState.value.step)
        assertNull(vm.uiState.value.error)
        vm.reopenIdentityApp()
        assertEquals(3, requests.size)
        vm.showPairingCode()
        assertFalse(vm.uiState.value.pairingInApp)
        assertEquals("boson:pair:1:reg:key", vm.uiState.value.pairingCode)
        identityApp.cancel()
    }

    @Test
    fun `a wait the background interrupted is started over in front, an untouched one is not`() {
        coEvery { repository.reachNode() } returns nodeId.toString()
        coEvery { repository.startPairing(any()) } returns "boson:pair:1:reg:key"
        coEvery { repository.finishPairing() } coAnswers { CompletableDeferred<SessionState>().await() }
        val vm = OnboardingViewModel(repository, context)
        vm.chooseExisting()
        vm.onServerUrlChange("https://node.example.org")
        vm.confirmServer()
        vm.choosePairing()
        coVerify(exactly = 1) { repository.finishPairing() }

        vm.onForeground()
        coVerify(exactly = 1) { repository.finishPairing() }

        vm.onBackground()
        vm.onForeground()
        coVerify(exactly = 2) { repository.finishPairing() }
    }

    @Test
    fun `a home node Boson Identity can't give says why, on the node step`() {
        val vm = OnboardingViewModel(repository, context)
        vm.chooseExisting()
        vm.onHomeNodeAnswer(BosonIdentityRequests.Answer.Refused(BosonIdentityRequests.ERROR_CANCELED))
        assertNull(vm.uiState.value.error)
        vm.onHomeNodeAnswer(BosonIdentityRequests.Answer.Refused(BosonIdentityRequests.ERROR_NO_HOME_NODE))
        assertEquals(OnboardingStep.Server, vm.uiState.value.step)
        assertNotNull(vm.uiState.value.error)
        coVerify(exactly = 0) { repository.setDirectorUrl(any(), any()) }
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
