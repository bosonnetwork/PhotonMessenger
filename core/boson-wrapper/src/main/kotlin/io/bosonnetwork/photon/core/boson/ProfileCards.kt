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
import io.bosonnetwork.UserProfile
import io.bosonnetwork.crypto.CryptoIdentity
import io.bosonnetwork.director.client.NodeStatus
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.higgs.HiggsNode
import io.bosonnetwork.identifier.Card
import io.bosonnetwork.identifier.Registry
import io.bosonnetwork.photon.core.model.AppError
import io.vertx.core.Vertx
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.future.await

/**
 * What a published profile card says: the claims of its Boson profile credential. Everything in it is
 * public; anyone who knows the id can read it. Made the same way Boson Identity makes it, so that the
 * two apps agree on whether a card is out of date.
 *
 * @property homeNode the home node's id; the node announces itself under it, so other nodes find where
 *   to fetch the avatar
 * @property avatar the avatar's bnr:// URI (the home node and the path there that serves it to anyone),
 *   or null when the account has none
 * @property messagingHomePeer the home node's messaging service, when it offers one
 */
data class CardContent(
    val name: String?,
    val bio: String?,
    val avatar: String?,
    val homeNode: String,
    val messagingHomePeer: String?,
) {
    companion object {
        /** Where the home node [nodeId] serves [userId]'s avatar, as it names it in the profile. */
        fun avatarUri(nodeId: String, userId: String): String = "bnr://$nodeId/api/v1/client/avatar/$userId"

        /** The card for [profile] at the node of [status]. */
        fun of(profile: Profile, status: NodeStatus): CardContent {
            val nodeId = status.nodeId.toString()
            return CardContent(
                name = profile.name.orElse(null)?.takeIf { it.isNotBlank() },
                bio = profile.bio.orElse(null)?.takeIf { it.isNotBlank() },
                avatar = profile.avatar.orElse(null)?.takeIf { it.isNotBlank() }
                    ?.let { avatarUri(nodeId, profile.id.toString()) },
                homeNode = nodeId,
                messagingHomePeer = status.getService(NodeStatus.Service.PHOTON_MESSAGING).orElse(null)
                    ?.peerId?.toString(),
            )
        }
    }
}

/** Whether the identity's profile card is published, and how it compares with the profile now. */
sealed interface CardState {
    data object NotPublished : CardState

    /**
     * Published, at [version]. [outdated] when it says something else than the profile now does (its
     * name, bio, avatar, home node or messaging peer); a card that can't be read is not called out of
     * date.
     */
    data class Published(val version: Int, val outdated: Boolean) : CardState
}

/**
 * The identity's public profile card: its profile as a Boson DID Card, so that anyone can look up the
 * name, avatar and home node by the id.
 *
 * The phone runs no DHT node, so the card goes through the home node's web gateway, which stores it
 * persistently and announces it again and again, keeping it in the DHT while the phone is off. The card
 * and the DHT value holding it are signed by the user key; the gateway only carries them, acting for the
 * identity through this device's key. Boson's identity registry does the publishing over the gateway (a
 * [io.bosonnetwork.Node]), choosing the version as every Boson app does, so Photon and Boson Identity
 * never publish below each other.
 *
 * Every call fails with an [AppError].
 */
class ProfileCards(
    private val vertx: Vertx,
    private val directorClients: DirectorClients,
    private val keyManager: KeyManager,
) {
    /** The card's state, against the profile as the home node keeps it now. */
    suspend fun state(): CardState = mapped {
        val (profile, status) = profileAndStatus()
        gateway(status) { gateway ->
            val published = Registry.DHTRegistry(gateway).getPublished(userId()).await().orElse(null)
            if (published == null) {
                CardState.NotPublished
            } else {
                val content = published.card.orElse(null)?.let(::read)
                CardState.Published(published.version, content != null && content != CardContent.of(profile, status))
            }
        }
    }

    /** Publishes the profile as the home node keeps it now; returns the version published. */
    suspend fun publish(): Int = mapped {
        val (profile, status) = profileAndStatus()
        val content = CardContent.of(profile, status)
        val userKey = keyManager.userKeyPair() ?: throw AppError.Unauthorized("No identity on this device")
        gateway(status) { gateway ->
            val owner = CryptoIdentity(userKey)
            val card = UserProfile.builder(owner)
                .name(content.name)
                .bio(content.bio)
                .avatar(content.avatar)
                .homeNode(Id.of(content.homeNode))
                .messagingHomePeer(content.messagingHomePeer?.let { Id.of(it) })
                .build()
                .card
            // The registry publishes past every version known (the gateway's, the DHT's); this device adds
            // the last one it published, which a card that expired everywhere may still be remembered at.
            val version = Registry.DHTRegistry(gateway).publish(owner, card, keyManager.cardVersion() ?: -1).await()
            keyManager.setCardVersion(version)
            version
        }
    }

    /**
     * Publishes the profile again if a card is published and out of date; after an edit of the profile.
     * Returns whether it published.
     */
    suspend fun refresh(): Boolean {
        val state = state()
        if (state !is CardState.Published || !state.outdated) return false
        publish()
        return true
    }

    /**
     * Stops publishing the card: the gateway no longer keeps or announces it. Copies other nodes hold
     * expire on their own. The version stays: a later card must still be newer than the ones they may
     * remember.
     */
    suspend fun unpublish(): Unit = mapped {
        val (_, status) = profileAndStatus()
        gateway(status) { gateway -> Registry.DHTRegistry(gateway).unpublish(userId()).await() }
        Unit
    }

    private fun userId(): Id = keyManager.userId() ?: throw AppError.Unauthorized("No identity on this device")

    private suspend fun profileAndStatus(): Pair<Profile, NodeStatus> {
        val client = directorClients.client()
        return client.profile.await() to client.nodeStatus.await()
    }

    // Runs [block] with the home node's web gateway, started and acting as the identity through this
    // device's key, and stops it afterwards. The gateway's peer id (which pins its certificate) and
    // endpoint come from the node's status.
    private suspend fun <T> gateway(status: NodeStatus, block: suspend (HiggsNode) -> T): T {
        val service = status.getService(NodeStatus.Service.WEB_GATEWAY).orElse(null)
            ?: throw AppError.NotFound("The node offers no web gateway")
        val endpoint = service.endpoint.orElse(null)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: throw AppError.NotFound("The node's web gateway announces no endpoint")
        val deviceKey = keyManager.deviceKeyPair() ?: throw AppError.Unauthorized("This device has no key")
        val node = HiggsNode.builder()
            .vertx(vertx)
            .userId(userId())
            .deviceKey(deviceKey)
            .gatewayPeerId(service.peerId)
            .gatewayUrl(endpoint)
            .build()
        node.start().await()
        try {
            return block(node)
        } finally {
            if (node.isRunning) runCatching { node.stop() }
        }
    }

    // What a published card says; null when it isn't a profile card.
    private fun read(card: Card): CardContent? = try {
        val profile = UserProfile.fromCard(card)
        CardContent(
            name = profile.name.orElse(null),
            bio = profile.bio.orElse(null),
            avatar = profile.avatar.orElse(null),
            homeNode = profile.homeNode.orElse(null)?.toString() ?: "",
            messagingHomePeer = profile.messagingHomePeer.orElse(null)?.toString(),
        )
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> mapped(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.toDirectorError()
        }
}
