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

import android.content.Context
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
import java.security.SecureRandom
import java.util.Base64
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
    private lateinit var repoTokenStore: FakeTokenStore

    /** Relaxed: only used to resolve user-facing error strings, whose exact text no test checks. */
    private fun fakeContext(): Context = mockk(relaxed = true)

    /** The super node id the Director's GET /client/id currently reports. */
    private var nodeId = "NODE-1"

    /** Mirrors EncryptedAuthTokenStore: an in-memory-only session token takes precedence over the
     *  persisted [token]; setToken persists (and drops the override); clear wipes both. */
    private class FakeTokenStore(@Volatile var token: String? = "cwt") : AuthTokenStore {
        @Volatile private var session: String? = null
        override fun currentToken(): String? = session ?: token
        override suspend fun setToken(token: String?) { this.token = token; session = null }
        override suspend fun clear() { token = null; session = null }
        override fun setSessionOnly(token: String?) { session = token }
        override fun clearSessionOnly() { session = null }
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
        repoTokenStore = FakeTokenStore()
        repository = AuthRepository(
            apiFactory = DirectorApiFactory(FakeTokenStore()),
            configStore = configStore,
            tokenStore = repoTokenStore,
            keyManager = keyManager,
            profileManager = profileManager,
            context = fakeContext(),
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

    /**
     * Serves auth/me with the given bound identity (null = no bound identity), plus client/auth (the
     * Authenticated branch of currentSession re-mints a Boson-identity session from the local key).
     */
    private fun serveMe(userId: String?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/auth/me") -> {
                    val body = if (userId != null) """{"sessionId":"s1","userId":"$userId"}"""
                    else """{"sessionId":"s1"}"""
                    MockResponse().setBody(body)
                }
                request.path!!.endsWith("/client/auth") -> MockResponse().setBody("""{"token":"cwt-user"}""")
                else -> MockResponse().setResponseCode(404)
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
    fun `currentSession imports the key in-context when a bound account's identity is not on this device`() = runTest {
        givenSignedInUser() // active profile key = some identity U (no profile for the account's identity)
        serveMe("USER-OTHER") // the account is bound to a different identity
        // No pre-key handoff: import in the current context and let the commit resolve the profile.
        val state = repository.currentSession()
        assertEquals("USER-OTHER", (state as? SessionState.NeedsKey)?.userId)
    }

    @Test
    fun `currentSession creates an identity in-context for an unbound account`() = runTest {
        givenSignedInUser()
        serveMe(null) // the account has no bound identity
        assertTrue(repository.currentSession() is SessionState.NeedsIdentity)
    }

    // --- Permissionless proof-of-work registration + self-sovereign key import ---

    private fun realNodeId(): String = BosonCrypto.idOf(BosonCrypto.generateKeyPair()).toString()

    private fun randomNonceB64(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** A challenge body with the fast (48,3)/effort-0 params the client PoW tests use. */
    private fun challengeJson(nonceB64: String, n: Int = 48, k: Int = 3, effort: Int = 0): String =
        """{"challenge":"Y2g","challengeSig":"c2ln","alg":"equihash","n":$n,"k":$k,""" +
            """"effort":$effort,"nonce":"$nonceB64","expiresAt":9999999999}"""

    @Test
    fun `powAvailable is false when the challenge endpoint 404s`() = runTest {
        configStore.setBaseUrl(baseUrl())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith("/client/users/challenge")) MockResponse().setResponseCode(404)
                else MockResponse().setResponseCode(500)
        }
        assertEquals(false, repository.powAvailable())
    }

    @Test
    fun `powAvailable is true when the challenge endpoint answers`() = runTest {
        configStore.setBaseUrl(baseUrl())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody(challengeJson(randomNonceB64()))
        }
        assertTrue(repository.powAvailable())
    }

    @Test
    fun `createAccountWithPow solves the challenge and persists identity, token and node`() = runTest {
        val node = realNodeId()
        configStore.setBaseUrl(baseUrl())
        val nonceB64 = randomNonceB64()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/client/id") -> MockResponse().setBody("""{"id":"$node"}""")
                request.path!!.endsWith("/client/users/challenge") ->
                    MockResponse().setBody(challengeJson(nonceB64))
                request.path!!.endsWith("/client/usersAndInitialDevice") ->
                    MockResponse().setResponseCode(201).setBody("""{"token":"cwt-pow"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        // A fresh-scratch active profile hosts the new identity: it is persisted locally.
        val state = repository.createAccountWithPow("Alice", "hello", null) as SessionState.Authenticated
        val userId = state.userId

        assertTrue(keyManager.hasUserKey())
        assertEquals(userId, keyManager.userId()?.toString())
        assertEquals("cwt-pow", repoTokenStore.token)
        assertEquals(node, keyManager.registeredNodeId())

        val body = drainRequests().first { it.path!!.endsWith("/client/usersAndInitialDevice") }.body.readUtf8()
        assertTrue(
            "the request carries the solved PoW fields",
            body.contains("solution") && body.contains("powNonce") &&
                body.contains("userSig") && body.contains("deviceSig"),
        )
    }

    @Test
    fun `importing a key without a session signs in with the user key and never calls auth-me`() = runTest {
        configStore.setBaseUrl(baseUrl())
        repoTokenStore.token = null // no OAuth session: the permissionless returning-device path
        val userKp = BosonCrypto.generateKeyPair()
        val keyText = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(userKp))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith("/client/auth")) MockResponse().setBody("""{"token":"cwt-user"}""")
                else MockResponse().setResponseCode(404)
        }

        // A fresh-scratch active profile hosts the imported identity.
        val state = repository.importUserKeyText(keyText) as SessionState.Authenticated

        assertEquals(BosonCrypto.idOf(userKp).toString(), state.userId)
        assertEquals("cwt-user", repoTokenStore.token)
        assertTrue(keyManager.hasUserKey())

        val reqs = drainRequests()
        val authBody = reqs.first { it.path!!.endsWith("/client/auth") }.body.readUtf8()
        assertTrue("user sign-in carries userSig", authBody.contains("userSig"))
        assertTrue("no getMe on the self-sovereign path", reqs.none { it.path!!.endsWith("/auth/me") })
    }

    private fun idOfKey64(privateKey64: ByteArray): String =
        BosonCrypto.idOf(BosonCrypto.keyPairFromPrivate64(privateKey64)).toString()

    @Test
    fun `createAccountWithPow hands off to a fresh profile without touching a foreign active profile`() = runTest {
        val node = realNodeId()
        configStore.setBaseUrl(baseUrl())
        val existingId = givenSignedInUser() // active profile already holds a DIFFERENT identity's key
        val nonceB64 = randomNonceB64()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/client/id") -> MockResponse().setBody("""{"id":"$node"}""")
                request.path!!.endsWith("/client/users/challenge") ->
                    MockResponse().setBody(challengeJson(nonceB64))
                request.path!!.endsWith("/client/usersAndInitialDevice") ->
                    MockResponse().setResponseCode(201).setBody("""{"token":"cwt-pow"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val state = repository.createAccountWithPow("Bob", null, null) as SessionState.NewProfileForIdentity

        // The seed carries the brand-new identity, its registered initial device, node and token.
        val seed = state.seed
        assertNotNull(seed)
        assertEquals(state.userId, seed!!.userId)
        assertEquals(state.userId, idOfKey64(seed.userPrivateKey64))
        assertNotNull(seed.devicePrivateKey64)
        assertEquals(node, seed.registeredNodeId)
        assertEquals("cwt-pow", seed.token)

        // The foreign active profile is untouched: its key, token and (absent) registration all stand.
        assertEquals(existingId, keyManager.userId()?.toString())
        assertNull(keyManager.registeredNodeId())
        assertEquals("cwt", repoTokenStore.token)
    }

    @Test
    fun `importing a new identity into a foreign active profile hands off to a fresh profile`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val existingId = givenSignedInUser() // active profile already holds a DIFFERENT identity's key
        repoTokenStore.token = null // self-sovereign returning-device path
        val importedKp = BosonCrypto.generateKeyPair()
        val importedId = BosonCrypto.idOf(importedKp).toString()
        val keyText = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(importedKp))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith("/client/auth")) MockResponse().setBody("""{"token":"cwt-user"}""")
                else MockResponse().setResponseCode(404)
        }

        val state = repository.importUserKeyText(keyText) as SessionState.NewProfileForIdentity

        // The seed carries the imported key + token; the device registers itself on the target later.
        val seed = state.seed
        assertNotNull(seed)
        assertEquals(importedId, state.userId)
        assertEquals(importedId, idOfKey64(seed!!.userPrivateKey64))
        assertNull(seed.devicePrivateKey64)
        assertNull(seed.registeredNodeId)
        assertEquals("cwt-user", seed.token)

        // The foreign active profile keeps its own identity and no session was written to it.
        assertEquals(existingId, keyManager.userId()?.toString())
        assertNull(repoTokenStore.token)
    }

    @Test
    fun `importing an identity that already has a profile reuses it`() = runTest {
        configStore.setBaseUrl(baseUrl())
        givenSignedInUser() // active profile holds identity U
        repoTokenStore.token = null // self-sovereign path
        val importedKp = BosonCrypto.generateKeyPair()
        val importedId = BosonCrypto.idOf(importedKp).toString()
        val keyText = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(importedKp))
        // A different on-device profile already belongs to the imported identity.
        val other = profileManager.createProfile()
        profileManager.bindUserId(other, importedId, displayName = null, homeNodeId = null)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith("/client/auth")) MockResponse().setBody("""{"token":"cwt-user"}""")
                else MockResponse().setResponseCode(404)
        }

        val state = repository.importUserKeyText(keyText) as SessionState.ReuseProfile

        assertEquals(other, state.profileId)
        assertEquals(importedId, state.userId)
        // The target already holds this identity's key, so only a fresh token is seeded (no device/node).
        val seed = state.seed
        assertNotNull(seed)
        assertNull(seed!!.devicePrivateKey64)
        assertNull(seed.registeredNodeId)
        assertEquals("cwt-user", seed.token)
    }

    // --- OAuth create (bindIdentity): commit-at-end + persist ONLY a clientAuth session ---

    /** Answers the OAuth bind flow (nonce, bind, profile write) and the clientAuth finalize. */
    private fun serveBind() {
        val nonceB58 = realNodeId() // any valid base58 string works as the binding nonce
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/auth/user-identity/nonce") ->
                    MockResponse().setBody("""{"nonce":"$nonceB58"}""")
                request.path!!.endsWith("/auth/user-identity") ->
                    MockResponse().setBody("""{"token":"bound-cwt","userId":"IGNORED"}""")
                request.path!!.endsWith("/client/auth") -> MockResponse().setBody("""{"token":"cwt-user"}""")
                request.path!!.endsWith("/client/profile") -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `bindIdentity on a fresh profile stores the key and persists a clientAuth session`() = runTest {
        configStore.setBaseUrl(baseUrl())
        repoTokenStore.setSessionOnly("oauth-token") // OAuth session held in memory only
        serveBind()

        val state = repository.bindIdentity("Alice", "hi") as SessionState.Authenticated

        assertTrue(keyManager.hasUserKey())
        assertEquals(state.userId, keyManager.userId()?.toString())
        // The PERSISTED session is the clientAuth token, never the OAuth/bind token.
        assertEquals("cwt-user", repoTokenStore.token)
        assertTrue("finalize re-mints via client/auth", drainRequests().any { it.path!!.endsWith("/client/auth") })
    }

    @Test
    fun `bindIdentity into a foreign active profile seeds a fresh profile and leaves the active untouched`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val existingId = givenSignedInUser() // active profile already holds identity U
        repoTokenStore.token = null
        repoTokenStore.setSessionOnly("oauth-token")
        serveBind()

        val state = repository.bindIdentity("Bob", null) as SessionState.NewProfileForIdentity

        val seed = state.seed
        assertNotNull(seed)
        assertEquals(state.userId, idOfKey64(seed!!.userPrivateKey64))
        assertEquals("Bob", seed.displayName)
        assertEquals("cwt-user", seed.token) // Boson-identity session, never the OAuth token
        assertNull(seed.devicePrivateKey64)
        assertNull(seed.registeredNodeId)
        // The foreign active profile is untouched: its key stands and nothing was persisted to its slot.
        assertEquals(existingId, keyManager.userId()?.toString())
        assertNull(repoTokenStore.token)
    }
}
