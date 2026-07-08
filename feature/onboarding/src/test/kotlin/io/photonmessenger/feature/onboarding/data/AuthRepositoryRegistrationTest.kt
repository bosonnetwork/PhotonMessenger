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

package io.photonmessenger.feature.onboarding.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.photonmessenger.core.boson.BosonCrypto
import io.photonmessenger.core.boson.KeyManager
import io.photonmessenger.core.model.AuthTokenStore
import io.photonmessenger.core.network.DeviceRegistrationStore
import io.photonmessenger.core.network.DirectorApiFactory
import io.photonmessenger.core.network.DirectorConfigStore
import io.photonmessenger.core.security.SecretStore
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Device registration management semantics: the persisted (userId, node, deviceId) record lets the
 * connect path skip re-registration, invalidates on user/node change, and identity changes always
 * rotate the device key.
 */
class AuthRepositoryRegistrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var keyManager: KeyManager
    private lateinit var configStore: DirectorConfigStore
    private lateinit var registrationStore: DeviceRegistrationStore
    private lateinit var repository: AuthRepository

    private class FakeTokenStore(@Volatile var token: String? = "cwt") : AuthTokenStore {
        override fun currentToken(): String? = token
        override suspend fun setToken(token: String?) { this.token = token }
        override suspend fun clear() { token = null }
    }

    private fun inMemorySecrets(): SecretStore {
        val map = mutableMapOf<String, ByteArray>()
        val store = mockk<SecretStore>()
        every { store.getBytes(any()) } answers { map[firstArg()] }
        every { store.putBytes(any(), any()) } answers {
            val value = secondArg<ByteArray?>()
            if (value == null) map.remove(firstArg())
            else map[firstArg<String>()] = value
        }
        every { store.remove(any()) } answers { map.remove(firstArg<String>()) }
        return store
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "auth_test.preferences_pb")
        }
        keyManager = KeyManager(inMemorySecrets())
        configStore = DirectorConfigStore(dataStore)
        registrationStore = DeviceRegistrationStore(dataStore)
        repository = AuthRepository(
            apiFactory = DirectorApiFactory(FakeTokenStore()),
            configStore = configStore,
            tokenStore = FakeTokenStore(),
            keyManager = keyManager,
            registrationStore = registrationStore,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private suspend fun givenSignedInUser(): String {
        configStore.setBaseUrl(baseUrl())
        val userKey = keyManager.generateUserKey()
        return BosonCrypto.idOf(userKey).toString()
    }

    /** Responds to profile lookups (not passphrase protected) and device registrations. */
    private fun serveDirector(addDeviceCode: Int = 201) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/client/profile") ->
                    MockResponse().setBody("""{"id":"USER","passphraseProtected":false}""")
                request.path!!.endsWith("/client/devices") ->
                    MockResponse().setResponseCode(addDeviceCode).setBody("""{"token":"cwt2"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    /** requestCount is cumulative, so track how many recorded requests were already consumed. */
    private var taken = 0

    private fun drainRequests(): List<RecordedRequest> {
        val drained = mutableListOf<RecordedRequest>()
        while (taken < server.requestCount) {
            drained += server.takeRequest()
            taken++
        }
        return drained
    }

    private fun countDeviceRegistrations(): Int =
        drainRequests().count { it.path!!.endsWith("/client/devices") }

    @Test
    fun `ensureDeviceRegistered registers once and then skips with zero network calls`() = runTest {
        val userId = givenSignedInUser()
        serveDirector()

        repository.ensureDeviceRegistered()
        val afterFirst = server.requestCount
        assertTrue(afterFirst > 0)
        assertEquals(userId, registrationStore.get()?.userId)
        assertEquals(userId, keyManager.deviceKeyOwner())

        repository.ensureDeviceRegistered()
        assertEquals("second bring-up must be a pure cache hit", afterFirst, server.requestCount)
    }

    @Test
    fun `a node change invalidates the record and re-registers`() = runTest {
        givenSignedInUser()
        serveDirector()
        repository.ensureDeviceRegistered()

        // Simulate a node switch by rewriting the recorded registration for another base URL.
        val stale = registrationStore.get()!!
        registrationStore.set(stale.copy(baseUrl = "https://other.node"))

        repository.ensureDeviceRegistered()

        assertEquals(2, countDeviceRegistrations())
        assertEquals("record rewritten for the current node", baseUrl(), registrationStore.get()?.baseUrl)
    }

    @Test
    fun `a 409 already-registered response still writes the record`() = runTest {
        val userId = givenSignedInUser()
        serveDirector(addDeviceCode = 409)

        repository.ensureDeviceRegistered()

        assertEquals(userId, registrationStore.get()?.userId)
        assertEquals(userId, keyManager.deviceKeyOwner())
    }

    @Test
    fun `a server failure leaves no record so the next bring-up retries`() = runTest {
        givenSignedInUser()
        serveDirector(addDeviceCode = 500)

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repository.registerDevice(null) }
        }

        assertNull(registrationStore.get())
        assertNull(keyManager.deviceKeyOwner())
    }

    @Test
    fun `ensureDeviceRegistered is a no-op without a local identity`() = runTest {
        configStore.setBaseUrl(baseUrl())
        repository.ensureDeviceRegistered()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `registering under a different identity rotates the device key`() = runTest {
        givenSignedInUser()
        serveDirector()
        repository.ensureDeviceRegistered()
        val firstDeviceId = registrationStore.get()!!.deviceId

        // The account identity changes (new user key) without a sign-out.
        val secondUserId = BosonCrypto.idOf(keyManager.generateUserKey()).toString()
        repository.ensureDeviceRegistered()

        val record = registrationStore.get()
        assertNotNull(record)
        assertEquals(secondUserId, record!!.userId)
        assertNotEquals("device key must never straddle two identities", firstDeviceId, record.deviceId)
    }

    @Test
    fun `signOut clears the registration record`() = runTest {
        givenSignedInUser()
        serveDirector()
        repository.ensureDeviceRegistered()
        assertNotNull(registrationStore.get())

        repository.signOut()

        assertNull(registrationStore.get())
        assertNull(keyManager.deviceKeyOwner())
    }

    @Test
    fun `registration request carries the rotated device id`() = runTest {
        givenSignedInUser()
        serveDirector()
        repository.ensureDeviceRegistered()

        // Drain recorded requests, keeping the registration body for comparison.
        val firstBody = drainRequests()
            .first { it.path!!.endsWith("/client/devices") }.body.readUtf8()

        keyManager.generateUserKey() // identity change
        repository.ensureDeviceRegistered()

        val secondBody = drainRequests()
            .first { it.path!!.endsWith("/client/devices") }.body.readUtf8()

        fun deviceIdOf(body: String): String =
            Regex(""""deviceId"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
        assertNotEquals(deviceIdOf(firstBody), deviceIdOf(secondBody))
    }
}
