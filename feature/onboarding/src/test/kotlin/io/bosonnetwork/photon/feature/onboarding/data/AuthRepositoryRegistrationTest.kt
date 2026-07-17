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

package io.bosonnetwork.photon.feature.onboarding.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.model.AuthTokenStore
import io.bosonnetwork.photon.core.network.DirectorApiFactory
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.security.ProfileManager
import io.bosonnetwork.photon.core.security.SecretStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Device registration management semantics: the connect path probes the Director's super node id
 * (public GET /client/id) and skips re-registration while the stored registered-node id matches; a
 * node change re-registers. (Rotation of the device key on identity change lives in KeyManager's
 * ensureDeviceKeyFor / AuthRepository.adoptIdentity and is unit-tested in KeyManagerTest.)
 */
class AuthRepositoryRegistrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var keyManager: KeyManager
    private lateinit var configStore: DirectorConfigStore
    private lateinit var profileManager: ProfileManager
    private lateinit var repository: AuthRepository

    /** The super node id the Director's GET /client/id currently reports. */
    private var nodeId = "NODE-1"

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
        profileManager = ProfileManager(tmp.newFolder("files"), tmp.newFolder("cache"))
        repository = AuthRepository(
            apiFactory = DirectorApiFactory(FakeTokenStore()),
            configStore = configStore,
            tokenStore = FakeTokenStore(),
            keyManager = keyManager,
            profileManager = profileManager,
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

    /** Responds to the node-id probe, profile lookups (not passphrase protected), and registrations. */
    private fun serveDirector(addDeviceCode: Int = 201) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/client/id") ->
                    MockResponse().setBody("""{"id":"$nodeId"}""")
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
    fun `ensureDeviceRegistered registers once then skips re-registering on the same node`() = runTest {
        givenSignedInUser()
        serveDirector()

        repository.ensureDeviceRegistered()
        assertTrue(server.requestCount > 0)
        assertEquals(nodeId, keyManager.registeredNodeId())

        repository.ensureDeviceRegistered()

        // The node-id probe runs each bring-up, but the device is registered only once.
        assertEquals(1, countDeviceRegistrations())
    }

    @Test
    fun `a node change re-registers and records the new node`() = runTest {
        givenSignedInUser()
        serveDirector()
        repository.ensureDeviceRegistered()
        assertEquals("NODE-1", keyManager.registeredNodeId())

        // The configured Director now reports a different super node.
        nodeId = "NODE-2"
        repository.ensureDeviceRegistered()

        assertEquals(2, countDeviceRegistrations())
        assertEquals("NODE-2", keyManager.registeredNodeId())
    }

    @Test
    fun `a 409 already-registered response still records the node`() = runTest {
        givenSignedInUser()
        serveDirector(addDeviceCode = 409)

        repository.ensureDeviceRegistered()

        assertEquals(nodeId, keyManager.registeredNodeId())
    }

    @Test
    fun `a server failure leaves no record so the next bring-up retries`() = runTest {
        givenSignedInUser()
        serveDirector(addDeviceCode = 500)

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repository.registerDevice(null) }
        }

        assertNull(keyManager.registeredNodeId())
    }

    @Test
    fun `ensureDeviceRegistered is a no-op without a local identity`() = runTest {
        configStore.setBaseUrl(baseUrl())
        repository.ensureDeviceRegistered()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unreachable node-id probe skips registration`() = runTest {
        givenSignedInUser()
        // No dispatcher configured to answer /client/id successfully.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }

        repository.ensureDeviceRegistered()

        assertEquals(0, countDeviceRegistrations())
        assertNull(keyManager.registeredNodeId())
    }

    @Test
    fun `signOut ends the session but keeps the identity and registration`() = runTest {
        givenSignedInUser()
        serveDirector()
        repository.ensureDeviceRegistered()
        assertNotNull(keyManager.registeredNodeId())

        repository.signOut()

        // Sign-out ends the session only: the profile's key + registered node persist for re-auth.
        assertNotNull(keyManager.registeredNodeId())
        assertTrue(keyManager.hasUserKey())
    }

    @Test
    fun `checkNodeMigration returns null when the device was never registered`() = runTest {
        givenSignedInUser()
        serveDirector()
        assertNull(repository.checkNodeMigration())
    }

    @Test
    fun `checkNodeMigration detects a deliberate node change`() = runTest {
        givenSignedInUser()
        serveDirector()
        repository.ensureDeviceRegistered() // registers on NODE-1
        assertNull("same node is not a migration", repository.checkNodeMigration())

        nodeId = "NODE-2" // the configured Director now reports a different super node
        val migration = repository.checkNodeMigration()
        assertEquals("NODE-1", migration?.fromNodeId)
        assertEquals("NODE-2", migration?.toNodeId)
    }

    /** Serves auth/me with the given bound identity (null = the account has no bound identity). */
    private fun serveMe(userId: String?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith("/auth/me")) {
                    val body = if (userId != null) """{"sessionId":"s1","userId":"$userId"}"""
                    else """{"sessionId":"s1"}"""
                    MockResponse().setBody(body)
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
    }

    @Test
    fun `currentSession is Authenticated when the account matches the local key`() = runTest {
        val userId = givenSignedInUser()
        serveMe(userId)
        assertTrue(repository.currentSession() is SessionState.Authenticated)
    }

    @Test
    fun `currentSession reuses an existing profile bound to the signed-in identity`() = runTest {
        givenSignedInUser() // active profile key = some identity U
        // Another on-device profile is already bound to the account's identity.
        val other = profileManager.createProfile()
        profileManager.bindUserId(other, "USER-OTHER", displayName = null, homeNodeId = null)
        serveMe("USER-OTHER")
        val state = repository.currentSession()
        assertEquals(other, (state as? SessionState.ReuseProfile)?.profileId)
        assertEquals("USER-OTHER", (state as? SessionState.ReuseProfile)?.userId)
    }

    @Test
    fun `currentSession needs a new profile when a bound profile signs in as a different identity`() = runTest {
        givenSignedInUser() // active profile key = some identity U (no profile for the account's identity)
        serveMe("USER-OTHER") // the account is bound to a different identity
        val state = repository.currentSession()
        assertEquals("USER-OTHER", (state as? SessionState.NewProfileForIdentity)?.userId)
    }

    @Test
    fun `currentSession needs a new profile when a bound profile signs in with an unbound account`() = runTest {
        givenSignedInUser()
        serveMe(null) // the account has no bound identity
        val state = repository.currentSession()
        assertTrue(state is SessionState.NewProfileForIdentity)
        assertNull((state as SessionState.NewProfileForIdentity).userId)
    }
}
