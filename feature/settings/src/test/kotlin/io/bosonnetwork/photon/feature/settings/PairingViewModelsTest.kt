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

import android.content.Context
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.feature.settings.data.DevicePairingRepository
import io.bosonnetwork.photon.feature.settings.data.PairingInvite
import io.bosonnetwork.photon.feature.settings.data.PairingRequestInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingViewModelsTest {

    /**
     * Relaxed: resolves the user-facing strings the ViewModels build via [Context.getString], whose
     * exact text no test checks - except [R.string.settings_wrong_passphrase], pinned to its real text
     * since a test asserts on it exactly.
     */
    private fun fakeContext(): Context = mockk(relaxed = true) {
        every { getString(R.string.settings_wrong_passphrase) } returns "Wrong passphrase"
    }

    private class FakePairingRepo(
        var invite: Result<PairingInvite> = Result.success(PairingInvite("reg1", "pmpair:1:reg1:AAAA")),
        var approval: Result<String> = Result.success("user-xyz"),
        var request: Result<PairingRequestInfo> =
            Result.success(PairingRequestInfo("reg1", "dev1", "Pixel 9", "PhotonMessenger")),
        var approveResult: Result<Unit> = Result.success(Unit),
        var denyResult: Result<Unit> = Result.success(Unit),
    ) : DevicePairingRepository {
        var approvedQr: String? = null
        var approvedPassphrase: String? = null
        var deniedQr: String? = null

        override suspend fun createInvite(deviceName: String) = invite
        override suspend fun awaitApproval() = approval
        override suspend fun readRequest(qrText: String) = request
        override suspend fun approve(qrText: String, passphrase: String?) =
            approveResult.also { approvedQr = qrText; approvedPassphrase = passphrase }
        override suspend fun deny(qrText: String) = denyResult.also { deniedQr = qrText }
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
    fun `new device shows QR then reaches paired on approval`() {
        val vm = PairNewDeviceViewModel(FakePairingRepo(), fakeContext())
        // With an unconfined dispatcher the init chain runs to completion synchronously.
        val state = vm.uiState.value
        assertTrue(state is PairNewDeviceUiState.Paired)
        assertEquals("user-xyz", (state as PairNewDeviceUiState.Paired).userId)
    }

    @Test
    fun `new device surfaces a registration failure`() {
        val repo = FakePairingRepo(invite = Result.failure(IllegalStateException("offline")))
        val vm = PairNewDeviceViewModel(repo, fakeContext())
        val state = vm.uiState.value
        assertTrue(state is PairNewDeviceUiState.Failed)
        assertEquals("offline", (state as PairNewDeviceUiState.Failed).message)
    }

    @Test
    fun `new device surfaces an approval failure`() {
        val repo = FakePairingRepo(approval = Result.failure(IllegalStateException("denied by user")))
        val vm = PairNewDeviceViewModel(repo, fakeContext())
        val state = vm.uiState.value
        assertTrue(state is PairNewDeviceUiState.Failed)
    }

    @Test
    fun `approver scans then confirms request details`() {
        val vm = ApproveDeviceViewModel(FakePairingRepo(), fakeContext())
        vm.onScanned("pmpair:1:reg1:AAAA")
        val state = vm.uiState.value
        assertTrue(state is ApproveDeviceUiState.Confirm)
        assertEquals("Pixel 9", (state as ApproveDeviceUiState.Confirm).info.deviceName)
    }

    @Test
    fun `approver approves and reaches done`() {
        val repo = FakePairingRepo()
        val vm = ApproveDeviceViewModel(repo, fakeContext())
        vm.onScanned("pmpair:1:reg1:AAAA")
        vm.approve()
        val state = vm.uiState.value
        assertTrue(state is ApproveDeviceUiState.Done)
        assertTrue((state as ApproveDeviceUiState.Done).approved)
        assertEquals("pmpair:1:reg1:AAAA", repo.approvedQr)
    }

    @Test
    fun `approver is prompted for a passphrase when the account is protected`() {
        val repo = FakePairingRepo(approveResult = Result.failure(AppError.PassphraseRequired("Passphrase required")))
        val vm = ApproveDeviceViewModel(repo, fakeContext())
        vm.onScanned("pmpair:1:reg1:AAAA")
        vm.approve()
        val state = vm.uiState.value
        assertTrue(state is ApproveDeviceUiState.Confirm)
        assertTrue((state as ApproveDeviceUiState.Confirm).needsPassphrase)
    }

    @Test
    fun `approver retries approval with the supplied passphrase`() {
        val repo = FakePairingRepo()
        val vm = ApproveDeviceViewModel(repo, fakeContext())
        vm.onScanned("pmpair:1:reg1:AAAA")
        vm.approve("secret")
        assertTrue(vm.uiState.value is ApproveDeviceUiState.Done)
        assertEquals("secret", repo.approvedPassphrase)
    }

    @Test
    fun `wrong passphrase keeps the approver on the confirm step with an error`() {
        val repo = FakePairingRepo(approveResult = Result.failure(AppError.Forbidden("Wrong passphrase")))
        val vm = ApproveDeviceViewModel(repo, fakeContext())
        vm.onScanned("pmpair:1:reg1:AAAA")
        vm.approve("wrong")
        val state = vm.uiState.value
        assertTrue(state is ApproveDeviceUiState.Confirm)
        assertEquals("Wrong passphrase", (state as ApproveDeviceUiState.Confirm).passphraseError)
    }

    @Test
    fun `approver denies and reaches done not approved`() {
        val repo = FakePairingRepo()
        val vm = ApproveDeviceViewModel(repo, fakeContext())
        vm.onScanned("pmpair:1:reg1:AAAA")
        vm.deny()
        val state = vm.uiState.value
        assertTrue(state is ApproveDeviceUiState.Done)
        assertEquals(false, (state as ApproveDeviceUiState.Done).approved)
        assertEquals("pmpair:1:reg1:AAAA", repo.deniedQr)
    }

    @Test
    fun `second scan is ignored while loading`() {
        val repo = FakePairingRepo(request = Result.failure(IllegalStateException("bad code")))
        val vm = ApproveDeviceViewModel(repo, fakeContext())
        vm.onScanned("pmpair:1:reg1:AAAA")
        // Now in Failed; a stray scan should not start a new load until rescan().
        assertTrue(vm.uiState.value is ApproveDeviceUiState.Failed)
        vm.onScanned("pmpair:1:reg2:BBBB")
        assertTrue(vm.uiState.value is ApproveDeviceUiState.Failed)
        vm.rescan()
        assertTrue(vm.uiState.value is ApproveDeviceUiState.Scanning)
    }
}
