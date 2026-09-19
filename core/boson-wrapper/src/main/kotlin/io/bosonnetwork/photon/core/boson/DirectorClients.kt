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

package io.bosonnetwork.photon.core.boson

import io.bosonnetwork.Id
import io.bosonnetwork.crypto.Signature
import io.bosonnetwork.director.client.DirectorBuilder
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.DirectorGuest
import io.bosonnetwork.director.client.DirectorOAuth
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.network.DirectorConfig
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.vertx.core.Vertx
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await

/**
 * The Director clients of the active profile, all built on the shared [Vertx] and the configured Director:
 *
 *  - [client] acts as the profile's own user, with the user key held in [KeyManager];
 *  - [clientFor] acts as a user whose key is only in memory (an identity being created or imported,
 *    not yet committed to a profile);
 *  - [guest] needs no identity: node information, sign-up options, and a new device joining an account;
 *  - [oauth] carries on an OAuth sign-in, with its session token.
 *
 * A client that acts as a user issues its own tokens from its key, bound to a node id: the configured
 * Server ID, else the node this device is registered with - so a Director that later claimed to be another
 * node cannot collect tokens valid there - else, before any registration, the id the Director reports.
 * The shared clients are rebuilt when the Director config, the user key or that node id changes; the ones
 * they replace are closed after a grace period, so that calls still in flight on them finish.
 */
class DirectorClients(
    private val vertx: Vertx,
    private val configStore: DirectorConfigStore,
    private val keyManager: KeyManager,
) {
    private val lock = Any()
    private var shared: Shared? = null
    private var sharedGuest: Pair<DirectorConfig, DirectorGuest>? = null
    private var reportedNodeId: Pair<DirectorConfig, Id>? = null

    private class Shared(val config: DirectorConfig, val userId: Id, val nodeId: Id?, val client: DirectorClient)

    /** The configured Director; emits again whenever it changes. */
    val config: Flow<DirectorConfig> = configStore.config.distinctUntilChanged()

    /**
     * The client acting as the profile's user, with its tokens bound to [pinnedNodeId] when given - a node
     * the device is about to register with, which differs from the registered one only during a migration.
     *
     * @throws AppError.Unauthorized if this profile holds no user key yet
     */
    suspend fun client(pinnedNodeId: Id? = null): DirectorClient {
        val cfg = configStore.config.first()
        val userKey = keyManager.userKeyPair()
            ?: throw AppError.Unauthorized("No identity on this device")
        val userId = BosonCrypto.idOf(userKey)
        val nodeId = pinnedNodeId ?: configuredNodeId(cfg) ?: keyManager.registeredNodeId()?.let(::parseNodeId)

        val stale: DirectorClient?
        val client: DirectorClient
        synchronized(lock) {
            val current = shared
            if (current != null && current.config == cfg && current.userId == userId && current.nodeId == nodeId)
                return current.client
            stale = current?.client
            client = DirectorClient.builder().configure(cfg, nodeId).userKey(userKey).build()
            shared = Shared(cfg, userId, nodeId, client)
        }
        stale?.let(::retire)
        return client
    }

    /**
     * A client acting as the user of [userKey], optionally with [deviceKey] as its device (the initial
     * device of a registration). It is not shared: the caller closes it; see [withClient]. Its tokens are
     * bound to the node the Director reports, looked up once per Director rather than by every such client.
     */
    suspend fun clientFor(userKey: Signature.KeyPair, deviceKey: Signature.KeyPair? = null): DirectorClient {
        val cfg = configStore.config.first()
        val builder = DirectorClient.builder().configure(cfg, configuredNodeId(cfg) ?: reportedNodeId(cfg))
            .userKey(userKey)
        if (deviceKey != null) builder.deviceKey(deviceKey)
        return builder.build()
    }

    /** Runs [block] with a [clientFor] client, closing it afterwards. */
    suspend fun <T> withClient(
        userKey: Signature.KeyPair,
        deviceKey: Signature.KeyPair? = null,
        block: suspend (DirectorClient) -> T,
    ): T {
        val client = clientFor(userKey, deviceKey)
        try {
            return block(client)
        } finally {
            client.close()
        }
    }

    /** The client that needs no identity: node information, sign-up options, and joining an account. */
    suspend fun guest(): DirectorGuest {
        val cfg = configStore.config.first()
        val stale: DirectorGuest?
        val guest: DirectorGuest
        synchronized(lock) {
            val current = sharedGuest
            if (current != null && current.first == cfg) return current.second
            stale = current?.second
            guest = DirectorGuest.builder().configure(cfg, configuredNodeId(cfg)).build()
            sharedGuest = cfg to guest
        }
        stale?.let { retire(it::close) }
        return guest
    }

    /** A client for the OAuth sign-in session of [sessionToken]; the caller closes it. */
    suspend fun oauth(sessionToken: String): DirectorOAuth {
        val cfg = configStore.config.first()
        return DirectorOAuth.builder().configure(cfg, configuredNodeId(cfg)).sessionToken(sessionToken).build()
    }

    /** The node id the configured Director reports now, asked every time: how a node change shows. */
    suspend fun probeNodeId(): Id = guest().nodeId.await()

    // The node id the Director reported, remembered per Director config.
    private suspend fun reportedNodeId(cfg: DirectorConfig): Id {
        synchronized(lock) { reportedNodeId?.let { (c, id) -> if (c == cfg) return id } }
        val id = probeNodeId()
        synchronized(lock) { reportedNodeId = cfg to id }
        return id
    }

    // A replaced client may still carry calls; close it once they have had time to finish.
    private fun retire(client: DirectorClient) = retire(client::close)

    private fun retire(close: () -> Any) {
        vertx.setTimer(RETIRE_DELAY_MS) { close() }
    }

    private fun <B : DirectorBuilder<B>> B.configure(cfg: DirectorConfig, nodeId: Id?): B {
        vertx(vertx).directorUrl(cfg.baseUrl)
        nodeId?.let { nodeId(it) }
        return this
    }

    private fun configuredNodeId(cfg: DirectorConfig): Id? = cfg.nodeId?.let(::parseNodeId)

    private fun parseNodeId(nodeId: String): Id =
        try {
            Id.of(nodeId)
        } catch (e: Exception) {
            throw AppError.InvalidInput("Invalid super node id", e)
        }

    private companion object {
        // Longer than any call can take: the longest, waiting for a pairing to be answered, gives up after
        // four minutes.
        const val RETIRE_DELAY_MS = 5L * 60 * 1000
    }
}
