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
import io.bosonnetwork.Id
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.DirectorClients
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.SessionStore
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.security.ProfileManager
import io.bosonnetwork.photon.core.security.SecretStore
import io.bosonnetwork.utils.Base58
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
 * The account flows of [AuthRepository] against a stand-in Director, through the real Director client:
 * device registration management (the connect path probes the super node id and skips re-registration
 * while the stored registered-node id matches; a node change re-registers), the OAuth session, proof-of-
 * work registration, key import, and the profile each identity lands in. (Rotation of the device key on
 * identity change lives in KeyManager's ensureDeviceKeyFor / AuthRepository.adoptIdentity and is
 * unit-tested in KeyManagerTest.)
 */
class AuthRepositoryRegistrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A request as the stand-in Director received it. */
    private data class Received(val method: String, val path: String, val body: String)

    /** The stand-in Director's answer: a status and an optional JSON body. */
    private data class Answer(val status: Int, val json: String? = null)

    private lateinit var vertx: Vertx
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<Received>()

    /** Answers every request; each test sets what the Director does. */
    @Volatile
    private var director: (Received) -> Answer = { Answer(404) }

    private lateinit var scope: CoroutineScope
    private lateinit var keyManager: KeyManager
    private lateinit var configStore: DirectorConfigStore
    private lateinit var profileManager: ProfileManager
    private lateinit var repository: AuthRepository
    private lateinit var sessionStore: FakeSessionStore

    /** Relaxed: only used to resolve user-facing error strings, whose exact text no test checks. */
    private fun fakeContext(): Context = mockk(relaxed = true)

    /** The super node id the Director's GET /client/id currently reports. */
    private val node1 = Id.random().toString()
    private val node2 = Id.random().toString()
    private var nodeId = node1

    private class FakeSessionStore(@Volatile var session: String? = null) : SessionStore {
        override fun currentSession(): String? = session
        override suspend fun setSession(userId: String?) { session = userId }
        override suspend fun clear() { session = null }
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
        vertx = Vertx.vertx()
        server = vertx.createHttpServer().requestHandler { req ->
            req.body().onSuccess { body ->
                val request = Received(req.method().name(), req.path(), body.toString())
                received += request
                val answer = director(request)
                val response = req.response().setStatusCode(answer.status)
                if (answer.json != null) response.putHeader("Content-Type", "application/json").end(answer.json)
                else response.end()
            }
        }.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)

        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "auth_test.preferences_pb")
        }
        keyManager = KeyManager(inMemorySecrets())
        configStore = DirectorConfigStore(dataStore)
        profileManager = ProfileManager(tmp.newFolder("files"), tmp.newFolder("cache"))
        sessionStore = FakeSessionStore()
        repository = AuthRepository(
            directorClients = DirectorClients(vertx, configStore, keyManager),
            configStore = configStore,
            sessionStore = sessionStore,
            keyManager = keyManager,
            profileManager = profileManager,
            context = fakeContext(),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.actualPort()}"

    /** An active profile signed in to an identity of its own. */
    private suspend fun givenSignedInUser(): String {
        configStore.setBaseUrl(baseUrl())
        val userKey = keyManager.generateUserKey()
        return BosonCrypto.idOf(userKey).toString().also { sessionStore.session = it }
    }

    private fun Received.isTo(method: String, suffix: String) = this.method == method && path.endsWith(suffix)

    // The Director client reads the node id to address the tokens it issues, so every stand-in answers it.
    private fun nodeIdAnswer() = Answer(200, """{"id":"$nodeId"}""")

    private fun profileJson(passphraseProtected: Boolean = false) =
        """{"id":"${Id.random()}","planName":"Free","passphraseProtected":$passphraseProtected}"""

    /** Responds to the node-id probe, profile lookups (not passphrase protected), and registrations. */
    private fun serveDirector(addDeviceCode: Int = 201) {
        director = { r ->
            when {
                r.isTo("GET", "/client/id") -> nodeIdAnswer()
                r.isTo("GET", "/client/profile") -> Answer(200, profileJson())
                r.isTo("POST", "/client/devices") -> Answer(addDeviceCode)
                else -> Answer(404)
            }
        }
    }

    /** Requests already looked at, so each check sees only the new ones. */
    private var taken = 0

    private fun drainRequests(): List<Received> = received.drop(taken).also { taken = received.size }

    private fun countDeviceRegistrations(): Int = drainRequests().count { it.isTo("POST", "/client/devices") }

    @Test
    fun `ensureDeviceRegistered registers once then skips re-registering on the same node`() = runTest {
        givenSignedInUser()
        serveDirector()

        repository.ensureDeviceRegistered()
        assertTrue(received.isNotEmpty())
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
        assertEquals(node1, keyManager.registeredNodeId())

        // The configured Director now reports a different super node.
        nodeId = node2
        repository.ensureDeviceRegistered()

        assertEquals(2, countDeviceRegistrations())
        assertEquals(node2, keyManager.registeredNodeId())
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
        assertTrue(received.isEmpty())
    }

    @Test
    fun `an unreachable node-id probe skips registration`() = runTest {
        givenSignedInUser()
        // Nothing answers /client/id successfully.
        director = { Answer(404) }

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
        assertNull(sessionStore.session)
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
        repository.ensureDeviceRegistered() // registers on node1
        assertNull("same node is not a migration", repository.checkNodeMigration())

        nodeId = node2 // the configured Director now reports a different super node
        val migration = repository.checkNodeMigration()
        assertEquals(node1, migration?.fromNodeId)
        assertEquals(node2, migration?.toNodeId)
    }

    /** Serves auth/me with the given bound identity (null = no bound identity). */
    private fun serveMe(userId: String?) {
        val sessionId = Id.random()
        director = { r ->
            when {
                r.isTo("GET", "/auth/me") -> Answer(
                    200,
                    if (userId != null) """{"sessionId":"$sessionId","userId":"$userId"}"""
                    else """{"sessionId":"$sessionId"}""",
                )
                else -> Answer(404)
            }
        }
    }

    @Test
    fun `an OAuth session for the local identity is Authenticated and signs in`() = runTest {
        val userId = givenSignedInUser()
        sessionStore.session = null // signed out earlier; signing back in through OAuth
        serveMe(userId)
        assertTrue(repository.onAuthToken("oauth-token") is SessionState.Authenticated)
        // The session recorded is the identity, never the OAuth token.
        assertEquals(userId, sessionStore.session)
        assertTrue("the OAuth token authenticated the session lookup", received.any { it.isTo("GET", "/auth/me") })
    }

    @Test
    fun `an OAuth session reuses an existing profile bound to the signed-in identity`() = runTest {
        givenSignedInUser() // active profile key = some identity U
        // Another on-device profile is already bound to the account's identity.
        val otherUser = Id.random().toString()
        val other = profileManager.createProfile()
        profileManager.bindUserId(other, otherUser, displayName = null, homeNodeId = null)
        serveMe(otherUser)
        val state = repository.onAuthToken("oauth-token")
        assertEquals(other, (state as? SessionState.ReuseProfile)?.profileId)
        assertEquals(otherUser, (state as? SessionState.ReuseProfile)?.userId)
    }

    @Test
    fun `an OAuth session imports the key in-context when a bound account's identity is not on this device`() = runTest {
        givenSignedInUser() // active profile key = some identity U (no profile for the account's identity)
        val otherUser = Id.random().toString()
        serveMe(otherUser) // the account is bound to a different identity
        // No pre-key handoff: import in the current context and let the commit resolve the profile.
        val state = repository.onAuthToken("oauth-token")
        assertEquals(otherUser, (state as? SessionState.NeedsKey)?.userId)
    }

    @Test
    fun `an OAuth session creates an identity in-context for an unbound account`() = runTest {
        givenSignedInUser()
        serveMe(null) // the account has no bound identity
        assertTrue(repository.onAuthToken("oauth-token") is SessionState.NeedsIdentity)
    }

    @Test
    fun `currentSession without an OAuth session is unauthorized`() = runTest {
        givenSignedInUser()
        assertThrows(AppError.Unauthorized::class.java) {
            kotlinx.coroutines.runBlocking { repository.currentSession() }
        }
    }

    // --- Permissionless proof-of-work registration + self-sovereign key import ---

    private fun randomNonceB64(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** A challenge body with the fast (48,3)/effort-0 params the client PoW tests use. */
    private fun challengeJson(nonceB64: String, n: Int = 48, k: Int = 3, effort: Int = 0): String =
        """{"challenge":"Y2g","challengeSig":"c2ln","alg":"equihash","n":$n,"k":$k,""" +
            """"effort":$effort,"nonce":"$nonceB64","expiresAt":9999999999}"""

    private fun registrationOptions(policy: String, pow: Boolean) =
        Answer(200, """{"policy":"$policy","proofOfWork":$pow,"oauth":true}""")

    @Test
    fun `powAvailable is false on an OAuth-only node`() = runTest {
        configStore.setBaseUrl(baseUrl())
        director = { r -> if (r.isTo("GET", "/client/registration")) registrationOptions("oauth", false) else Answer(500) }
        assertEquals(false, repository.powAvailable())
    }

    @Test
    fun `powAvailable is true when the node accepts proof-of-work`() = runTest {
        configStore.setBaseUrl(baseUrl())
        director = { r -> if (r.isTo("GET", "/client/registration")) registrationOptions("either", true) else Answer(500) }
        assertTrue(repository.powAvailable())
        // Asked without touching the challenge endpoint, whose challenges raise the next one's effort.
        assertTrue(received.none { it.path.endsWith("/client/users/challenge") })
    }

    @Test
    fun `powAvailable is asked once per Director`() = runTest {
        configStore.setBaseUrl(baseUrl())
        director = { r -> if (r.isTo("GET", "/client/registration")) registrationOptions("pow", true) else Answer(500) }
        repeat(3) { assertTrue(repository.powAvailable()) }
        assertEquals(1, received.count { it.isTo("GET", "/client/registration") })

        // Another Director is asked again.
        configStore.setNodeId(Id.random().toString())
        assertTrue(repository.powAvailable())
        assertEquals(2, received.count { it.isTo("GET", "/client/registration") })
    }

    @Test
    fun `powAvailable assumes proof-of-work on a Director too old to say`() = runTest {
        configStore.setBaseUrl(baseUrl())
        director = { Answer(404) }
        assertTrue(repository.powAvailable())
    }

    /** Answers a proof-of-work registration of a user with its initial device. */
    private fun serveRegistration(registrationCode: Int = 201) {
        val nonceB64 = randomNonceB64()
        director = { r ->
            when {
                r.isTo("GET", "/client/id") -> nodeIdAnswer()
                r.isTo("GET", "/client/users/challenge") -> Answer(200, challengeJson(nonceB64))
                r.isTo("POST", "/client/usersAndInitialDevice") -> Answer(registrationCode, """{"token":"issued"}""")
                r.isTo("GET", "/client/profile") -> Answer(200, profileJson())
                else -> Answer(404)
            }
        }
    }

    @Test
    fun `createAccountWithPow solves the challenge and persists identity, session and node`() = runTest {
        configStore.setBaseUrl(baseUrl())
        serveRegistration()

        // A fresh-scratch active profile hosts the new identity: it is persisted locally.
        val state = repository.createAccountWithPow("Alice", "hello", null) as SessionState.Authenticated
        val userId = state.userId

        assertTrue(keyManager.hasUserKey())
        assertEquals(userId, keyManager.userId()?.toString())
        assertEquals(userId, sessionStore.session)
        assertEquals(nodeId, keyManager.registeredNodeId())

        val body = drainRequests().first { it.isTo("POST", "/client/usersAndInitialDevice") }.body
        assertTrue(
            "the request carries the solved PoW fields",
            body.contains("solution") && body.contains("powNonce") &&
                body.contains("userSig") && body.contains("deviceSig"),
        )
        assertTrue("the entered name is registered", body.contains("Alice"))
    }

    @Test
    fun `createAccountWithPow recovers an already-registered identity of its own`() = runTest {
        configStore.setBaseUrl(baseUrl())
        // A lost response: the registration went through, and the resubmit conflicts.
        serveRegistration(registrationCode = 409)

        val state = repository.createAccountWithPow("Alice", null, null) as SessionState.Authenticated

        assertEquals(state.userId, sessionStore.session)
        assertTrue("the identity is confirmed by acting as it", drainRequests().any { it.isTo("GET", "/client/profile") })
    }

    @Test
    fun `importing a key without an OAuth session signs in with the user key and never calls auth-me`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val userKp = BosonCrypto.generateKeyPair()
        val keyText = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(userKp))
        serveDirector()

        // A fresh-scratch active profile hosts the imported identity.
        val state = repository.importUserKeyText(keyText) as SessionState.Authenticated

        assertEquals(BosonCrypto.idOf(userKp).toString(), state.userId)
        assertEquals(state.userId, sessionStore.session)
        assertTrue(keyManager.hasUserKey())

        val reqs = drainRequests()
        val profile = reqs.first { it.isTo("GET", "/client/profile") }
        assertTrue("signed in as the user", profile.method == "GET")
        assertTrue("no auth/me on the self-sovereign path", reqs.none { it.path.endsWith("/auth/me") })
    }

    @Test
    fun `importing a key the Director does not know fails and stores nothing`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val keyText = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(BosonCrypto.generateKeyPair()))
        director = { r -> if (r.isTo("GET", "/client/id")) nodeIdAnswer() else Answer(401) }

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repository.importUserKeyText(keyText) }
        }

        assertTrue(!keyManager.hasUserKey())
        assertNull(sessionStore.session)
    }

    private fun idOfKey64(privateKey64: ByteArray): String =
        BosonCrypto.idOf(BosonCrypto.keyPairFromPrivate64(privateKey64)).toString()

    @Test
    fun `createAccountWithPow hands off to a fresh profile without touching a foreign active profile`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val existingId = givenSignedInUser() // active profile already holds a DIFFERENT identity's key
        serveRegistration()

        val state = repository.createAccountWithPow("Bob", null, null) as SessionState.NewProfileForIdentity

        // The seed carries the brand-new identity, its registered initial device and node.
        val seed = state.seed
        assertNotNull(seed)
        assertEquals(state.userId, seed!!.userId)
        assertEquals(state.userId, idOfKey64(seed.userPrivateKey64))
        assertNotNull(seed.devicePrivateKey64)
        assertEquals(nodeId, seed.registeredNodeId)

        // The foreign active profile is untouched: its key, session and (absent) registration all stand.
        assertEquals(existingId, keyManager.userId()?.toString())
        assertNull(keyManager.registeredNodeId())
        assertEquals(existingId, sessionStore.session)
    }

    @Test
    fun `importing a new identity into a foreign active profile hands off to a fresh profile`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val existingId = givenSignedInUser() // active profile already holds a DIFFERENT identity's key
        val importedKp = BosonCrypto.generateKeyPair()
        val importedId = BosonCrypto.idOf(importedKp).toString()
        val keyText = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(importedKp))
        serveDirector()

        val state = repository.importUserKeyText(keyText) as SessionState.NewProfileForIdentity

        // The seed carries the imported key; the device registers itself on the target later.
        val seed = state.seed
        assertNotNull(seed)
        assertEquals(importedId, state.userId)
        assertEquals(importedId, idOfKey64(seed!!.userPrivateKey64))
        assertNull(seed.devicePrivateKey64)
        assertNull(seed.registeredNodeId)

        // The foreign active profile keeps its own identity and session.
        assertEquals(existingId, keyManager.userId()?.toString())
        assertEquals(existingId, sessionStore.session)
    }

    @Test
    fun `importing an identity that already has a profile reuses it`() = runTest {
        configStore.setBaseUrl(baseUrl())
        givenSignedInUser() // active profile holds identity U
        val importedKp = BosonCrypto.generateKeyPair()
        val importedId = BosonCrypto.idOf(importedKp).toString()
        val keyText = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(importedKp))
        // A different on-device profile already belongs to the imported identity.
        val other = profileManager.createProfile()
        profileManager.bindUserId(other, importedId, displayName = null, homeNodeId = null)
        serveDirector()

        val state = repository.importUserKeyText(keyText) as SessionState.ReuseProfile

        assertEquals(other, state.profileId)
        assertEquals(importedId, state.userId)
        // The target already holds this identity's key, so no device/node is seeded.
        val seed = state.seed
        assertNotNull(seed)
        assertNull(seed!!.devicePrivateKey64)
        assertNull(seed.registeredNodeId)
    }

    // --- OAuth create (bindIdentity): commit-at-end; the OAuth token is never persisted ---

    /** Answers the OAuth session (unbound), the bind flow (nonce, bind) and the profile write. */
    private fun serveBind() {
        val sessionId = Id.random()
        val nonceB58 = Base58.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        director = { r ->
            when {
                r.isTo("GET", "/auth/me") -> Answer(200, """{"sessionId":"$sessionId"}""")
                r.isTo("GET", "/auth/user-identity/nonce") -> Answer(200, """{"nonce":"$nonceB58"}""")
                r.isTo("PUT", "/auth/user-identity") ->
                    Answer(200, """{"token":"bound-cwt","userId":"${Id.random()}"}""")
                r.isTo("GET", "/client/id") -> nodeIdAnswer()
                r.isTo("PUT", "/client/profile") -> Answer(204)
                else -> Answer(404)
            }
        }
    }

    @Test
    fun `bindIdentity on a fresh profile stores the key and signs in`() = runTest {
        configStore.setBaseUrl(baseUrl())
        serveBind()
        assertTrue(repository.onAuthToken("oauth-token") is SessionState.NeedsIdentity)

        val state = repository.bindIdentity("Alice", "hi") as SessionState.Authenticated

        assertTrue(keyManager.hasUserKey())
        assertEquals(state.userId, keyManager.userId()?.toString())
        // The PERSISTED session is the identity, never the OAuth/bind token.
        assertEquals(state.userId, sessionStore.session)
        val reqs = drainRequests()
        val bind = reqs.first { it.isTo("PUT", "/auth/user-identity") }.body
        assertTrue("the bind names the new key", bind.contains(BosonCrypto.publicKeyBase58(keyManager.userKeyPair()!!)))
        val profile = reqs.first { it.isTo("PUT", "/client/profile") }.body
        assertTrue("the chosen name is written as the new user", profile.contains("Alice") && profile.contains("hi"))
        // The OAuth token is dropped once the identity exists: there is no OAuth session left to resolve.
        assertThrows(AppError.Unauthorized::class.java) {
            kotlinx.coroutines.runBlocking { repository.currentSession() }
        }
    }

    @Test
    fun `bindIdentity into a foreign active profile seeds a fresh profile and leaves the active untouched`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val existingId = givenSignedInUser() // active profile already holds identity U
        sessionStore.session = null
        serveBind()
        repository.onAuthToken("oauth-token")

        val state = repository.bindIdentity("Bob", null) as SessionState.NewProfileForIdentity

        val seed = state.seed
        assertNotNull(seed)
        assertEquals(state.userId, idOfKey64(seed!!.userPrivateKey64))
        assertEquals("Bob", seed.displayName)
        assertNull(seed.devicePrivateKey64)
        assertNull(seed.registeredNodeId)
        // The foreign active profile is untouched: its key stands and nothing was persisted to its slot.
        assertEquals(existingId, keyManager.userId()?.toString())
        assertNull(sessionStore.session)
    }

    @Test
    fun `bindIdentity without an OAuth session is unauthorized`() = runTest {
        configStore.setBaseUrl(baseUrl())
        assertThrows(AppError.Unauthorized::class.java) {
            kotlinx.coroutines.runBlocking { repository.bindIdentity("Alice", null) }
        }
    }
}
