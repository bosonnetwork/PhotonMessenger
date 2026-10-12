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
import io.bosonnetwork.Id
import io.bosonnetwork.photon.core.boson.BosonCrypto
import io.bosonnetwork.photon.core.boson.DirectorClients
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.SessionStore
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.security.ProfileManager
import io.bosonnetwork.photon.core.security.IdentityVault
import io.bosonnetwork.photon.core.security.KeySealer
import io.mockk.mockk
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
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
 * The account flows of [AuthRepository] against a stand-in Director, through the real Director client:
 * device registration management (the connect path probes the super node id and skips re-registration
 * while the stored registered-node id matches; a node change re-registers), proof-of-work registration
 * of a new identity, import of a backup, and the profile each identity lands in. (Rotation of the device key on
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

    /** Stands in for the Keystore: seals in the clear (the vault's own tests check the sealing). */
    private class PlainSealer : KeySealer {
        override fun seal(alias: String, plain: ByteArray): String = java.util.Base64.getEncoder().encodeToString(plain)
        override fun open(alias: String, sealed: String): ByteArray = java.util.Base64.getDecoder().decode(sealed)
        override fun delete(alias: String) {}
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
        val vault = IdentityVault(tmp.newFolder("identity"), "test", PlainSealer())
        keyManager = KeyManager(vault)
        configStore = DirectorConfigStore(vault)
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
    fun `a pairing whose key is already a device starts over with a new key`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val firstKey = keyManager.ensureDeviceKey()
        director = { r ->
            when {
                r.isTo("GET", "/client/id") -> nodeIdAnswer()
                // An earlier pairing of this key was approved, but its answer never arrived.
                r.isTo("POST", "/client/devices/registrations") && r.body.contains(BosonCrypto.idOf(firstKey).toString()) ->
                    Answer(409, """{"error":"The device already exists"}""")
                r.isTo("POST", "/client/devices/registrations") -> Answer(200, """{"registrationId":"reg1"}""")
                else -> Answer(404)
            }
        }

        val code = repository.startPairing("Pixel")
        assertTrue(code.startsWith("boson:pair:1:reg1:"))
        val newKey = keyManager.deviceKeyPair()!!
        assertNotEquals(BosonCrypto.idOf(firstKey), BosonCrypto.idOf(newKey))
        assertEquals(2, received.count { it.isTo("POST", "/client/devices/registrations") })
    }

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
        val newId = repository.newIdentity()
        assertNull("nothing is stored before the node registers it", keyManager.userId())
        val state = repository.createAccountWithPow("Alice", "hello", backupChecked = true) as SessionState.Authenticated
        assertEquals(newId, state.userId)
        assertTrue(!keyManager.isBackupPending())
        assertNull("the new identity is no longer pending", repository.pendingIdentityId())
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

        repository.newIdentity()
        val state = repository.createAccountWithPow("Alice", null, backupChecked = true) as SessionState.Authenticated

        assertEquals(state.userId, sessionStore.session)
        assertTrue("the identity is confirmed by acting as it", drainRequests().any { it.isTo("GET", "/client/profile") })
    }

    @Test
    fun `importing a backup signs in with the user key`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val userKp = BosonCrypto.generateKeyPair()
        val keyText = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(userKp))
        serveDirector()

        // A fresh-scratch active profile hosts the imported identity.
        val state = repository.importBackup(keyText) as SessionState.Authenticated

        assertEquals(BosonCrypto.idOf(userKp).toString(), state.userId)
        assertEquals(state.userId, sessionStore.session)
        assertTrue(keyManager.hasUserKey())

        val reqs = drainRequests()
        val profile = reqs.first { it.isTo("GET", "/client/profile") }
        assertTrue("signed in as the user", profile.method == "GET")
    }

    @Test
    fun `importing a key the Director does not know fails and stores nothing`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val keyText = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(BosonCrypto.generateKeyPair()))
        director = { r -> if (r.isTo("GET", "/client/id")) nodeIdAnswer() else Answer(401) }

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repository.importBackup(keyText) }
        }

        assertTrue(!keyManager.hasUserKey())
        assertNull(sessionStore.session)
    }

    @Test
    fun `an unchecked backup leaves a reminder on the profile`() = runTest {
        configStore.setBaseUrl(baseUrl())
        serveRegistration()
        repository.newIdentity()
        repository.createAccountWithPow("Alice", null, backupChecked = false)
        assertTrue(keyManager.isBackupPending())
    }

    @Test
    fun `registering without a new identity fails and stores nothing`() = runTest {
        configStore.setBaseUrl(baseUrl())
        serveRegistration()
        assertThrows(AppError.InvalidInput::class.java) {
            kotlinx.coroutines.runBlocking { repository.createAccountWithPow("Alice", null, backupChecked = true) }
        }
        assertTrue(!keyManager.hasUserKey())
    }

    @Test
    fun `a backup in its printed form, numbered rows and all, imports`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val userKp = BosonCrypto.generateKeyPair()
        val base58 = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(userKp))
        serveDirector()
        val printed = io.bosonnetwork.crypto.KeyBackup.gridLines(base58).joinToString("\n")
        val state = repository.importBackup(printed) as SessionState.Authenticated
        assertEquals(BosonCrypto.idOf(userKp).toString(), state.userId)
    }

    @Test
    fun `an identity this node doesn't know is told apart`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val keyText = BosonCrypto.privateKey64ToBase58(BosonCrypto.privateKeyBytes64(BosonCrypto.generateKeyPair()))
        director = { r -> if (r.isTo("GET", "/client/id")) nodeIdAnswer() else Answer(401) }
        val e = assertThrows(AppError.InvalidInput::class.java) {
            kotlinx.coroutines.runBlocking { repository.importBackup(keyText) }
        }
        assertNotNull(e.cause)
    }

    private fun idOfKey64(privateKey64: ByteArray): String =
        BosonCrypto.idOf(BosonCrypto.keyPairFromPrivate64(privateKey64)).toString()

    @Test
    fun `createAccountWithPow hands off to a fresh profile without touching a foreign active profile`() = runTest {
        configStore.setBaseUrl(baseUrl())
        val existingId = givenSignedInUser() // active profile already holds a DIFFERENT identity's key
        serveRegistration()

        repository.newIdentity()
        val state = repository.createAccountWithPow("Bob", null, backupChecked = false) as SessionState.NewProfileForIdentity

        // The seed carries the brand-new identity, its registered initial device and node.
        val seed = state.seed
        assertNotNull(seed)
        assertEquals(state.userId, seed!!.userId)
        assertEquals(state.userId, idOfKey64(seed.userPrivateKey64))
        assertNotNull(seed.devicePrivateKey64)
        assertEquals(nodeId, seed.registeredNodeId)
        assertTrue("the unchecked backup's reminder travels with the seed", seed.backupPending)

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

        val state = repository.importBackup(keyText) as SessionState.NewProfileForIdentity

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

        val state = repository.importBackup(keyText) as SessionState.ReuseProfile

        assertEquals(other, state.profileId)
        assertEquals(importedId, state.userId)
        // The target already holds this identity's key, so no device/node is seeded.
        val seed = state.seed
        assertNotNull(seed)
        assertNull(seed!!.devicePrivateKey64)
        assertNull(seed.registeredNodeId)
    }
}
