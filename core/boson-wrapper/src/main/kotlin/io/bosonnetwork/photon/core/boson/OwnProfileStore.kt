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

import io.bosonnetwork.director.client.Avatar
import io.bosonnetwork.director.client.AvatarRefresh
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.photon.core.security.AvatarStore
import io.bosonnetwork.photon.core.security.IdentityVault
import io.bosonnetwork.photon.core.security.OwnProfile
import io.bosonnetwork.photon.core.security.StoredAvatar
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/** The identity's own profile as kept on this device: [avatar] is the image, null for none. */
class KeptProfile(
    val userId: String,
    val name: String?,
    val bio: String?,
    val email: String?,
    val revision: Long,
    val avatar: ByteArray?,
)

/**
 * Keeps the identity's own profile and avatar in the profile's files, as the home node gives them, so
 * that they show at once and offline: the profile in the [IdentityVault] record, the avatar in the
 * [AvatarStore]. The avatar is downloaded when it is missing, and asked again only when the profile
 * changed, conditionally: the node answers "unchanged" without sending it.
 */
class OwnProfileStore(
    private val vault: IdentityVault,
    private val avatars: AvatarStore,
) {
    /** The profile as kept, with its avatar image; null before the node first gave it. */
    val kept: Flow<KeptProfile?> = combine(vault.info, avatars.avatar) { info, avatar ->
        val userId = info.userId
        val profile = info.profile
        if (userId == null || profile == null) null
        else KeptProfile(userId, profile.name, profile.bio, profile.email, profile.revision, avatar?.data?.takeIf { profile.hasAvatar })
    }.distinctUntilChanged()

    /** The avatar image as kept, or null. */
    fun avatar(): ByteArray? = avatars.avatar.value?.data

    /**
     * Keeps [profile], just read from the node through [client], and brings the avatar up to date. An
     * avatar the node can't give now stays as kept.
     */
    suspend fun keep(profile: Profile, client: DirectorClient) {
        val hasAvatar = profile.avatar.orElse(null)?.isNotBlank() == true
        val own = OwnProfile(
            name = profile.name.orElse(null)?.takeIf { it.isNotBlank() },
            bio = profile.bio.orElse(null)?.takeIf { it.isNotBlank() },
            email = profile.email.orElse(null)?.takeIf { it.isNotBlank() },
            revision = profile.profileRevision,
            hasAvatar = hasAvatar,
        )
        val previous = vault.info.value.profile
        withContext(Dispatchers.IO) { vault.update { it.copy(profile = own) } }

        withContext(Dispatchers.IO) {
            if (!hasAvatar) {
                avatars.put(null)
                return@withContext
            }
            val held = avatars.avatar.value
            // The same revision has the same avatar: nothing to ask.
            if (held != null && previous?.revision == own.revision) return@withContext
            try {
                if (held == null) {
                    client.avatar.await().orElse(null)?.let { avatars.put(it.stored()) }
                } else {
                    val refresh = client.refreshAvatar(held.avatar()).await()
                    when (refresh.status) {
                        AvatarRefresh.Status.CHANGED -> refresh.avatar.orElse(null)?.let { avatars.put(it.stored()) }
                        AvatarRefresh.Status.REMOVED -> avatars.put(null)
                        AvatarRefresh.Status.UNCHANGED, null -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Asked again the next time the profile is read.
            }
        }
    }

    private fun Avatar.stored() = StoredAvatar(contentType, data, eTag.orElse(null), lastModified.orElse(null))

    private fun StoredAvatar.avatar(): Avatar = Avatar.of(contentType, data, eTag, lastModified)
}
