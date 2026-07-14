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

package io.bosonnetwork.photon.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The app-wide, resolved presentation of a user: the single [displayName] to show, an [avatarUrl] to
 * load (null when the user has no avatar), and their [bio] when known.
 *
 * Produced by the one identity policy - [ProfileResolver.displayProfile] / [cachedDisplay] / the
 * [ResolvedProfile.toDisplay] combiner - so every surface (contact rows, chat titles, channel
 * members, friend requests, notifications) renders names and avatars identically.
 */
data class DisplayProfile(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    /**
     * True when [displayName] is only the short-id fallback - no local alias and no Director-resolved
     * name were available. Callers that already hold a library-provided title use this to keep their
     * own fallback (and only adopt [displayName] when it is a genuine, resolved upgrade).
     */
    val nameIsFallback: Boolean = false,
)

/**
 * The one short form of a long Boson id, shown when no human name is available (e.g. `8Hk3Rtq...9fQ2`).
 * Every surface uses this single format so an id looks the same everywhere it falls back.
 */
fun shortId(id: String): String =
    if (id.length <= SHORT_ID_MAX) id else id.take(SHORT_ID_HEAD) + "..." + id.takeLast(SHORT_ID_TAIL)

private const val SHORT_ID_MAX = 14
private const val SHORT_ID_HEAD = 8
private const val SHORT_ID_TAIL = 4

/**
 * The single identity-presentation policy: pick the name to show from a [ResolvedProfile] (may be
 * null when unresolved) plus a caller-supplied [localName].
 *
 * Name preference: [localName] (a contact remark or library profile name, already the winner locally)
 * > the Director-resolved name > the short id. Avatar and bio always come from the resolved profile.
 * When neither a local nor a resolved name exists the result is [shortId] and [DisplayProfile.nameIsFallback]
 * is set, so a caller holding its own library title can keep it instead of overwriting with the id.
 */
fun ResolvedProfile?.toDisplay(userId: String, localName: String? = null): DisplayProfile {
    val local = localName?.takeIf { it.isNotBlank() }
    val resolved = this?.name?.takeIf { it.isNotBlank() }
    val chosen = local ?: resolved
    return DisplayProfile(
        userId = userId,
        displayName = chosen ?: shortId(userId),
        avatarUrl = this?.avatarUrl,
        bio = this?.bio,
        nameIsFallback = chosen == null,
    )
}

/**
 * Reactive [DisplayProfile] for [userId]: emits immediately from cache (or the short-id fallback) and
 * upgrades once a Director (re)fetch completes. A [localName] hint (remark or library name) always
 * outranks the resolved name. Use when the local name is known up front; when it arrives on a separate
 * flow, combine that flow with [profile] and apply [toDisplay] instead.
 */
fun ProfileResolver.displayProfile(userId: String, localName: String? = null): Flow<DisplayProfile> =
    profile(userId).map { it.toDisplay(userId, localName) }

/** Synchronous, cache-only [DisplayProfile] for hot paths (e.g. a notification sender name). */
fun ProfileResolver.cachedDisplay(userId: String, localName: String? = null): DisplayProfile =
    cached(userId).toDisplay(userId, localName)
