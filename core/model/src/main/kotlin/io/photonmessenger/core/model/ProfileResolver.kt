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

package io.photonmessenger.core.model

import kotlinx.coroutines.flow.Flow

/**
 * A user's public profile as resolved from the Director (`GET /api/v1/client/profile/{userId}`).
 * [avatarUrl] points at the Director's avatar endpoint and is set only when the user has one.
 */
data class ResolvedProfile(
    val userId: String,
    val name: String?,
    val bio: String?,
    val avatarUrl: String?,
)

/**
 * Resolves public profiles (display name, bio, avatar) for ANY user id - local or remote - against
 * the configured Director, with in-memory caching. Lives in core:model so feature modules can enrich
 * raw user ids without a core:network dependency.
 *
 * This is the raw fetch primitive; the app-wide name/avatar policy is layered on top in
 * [DisplayProfile] ([displayProfile] / [cachedDisplay] / [toDisplay]). Resolved names are a FALLBACK:
 * contact remarks and library-provided profile names always win (preference order: remark > library
 * profile name > resolved name > short id).
 */
interface ProfileResolver {
    /**
     * Emits the cached profile immediately (or null while unknown), then updates once a (re)fetch
     * completes. Collecting triggers a fetch when the entry is missing or stale. Emits null for
     * users the Director does not know (404).
     */
    fun profile(userId: String): Flow<ResolvedProfile?>

    /** Synchronous cache-only lookup for hot paths (e.g. resolving message sender names). */
    fun cached(userId: String): ResolvedProfile?

    /** Fire-and-forget prefetch; a no-op when the cached entry is still fresh. */
    fun prefetch(userId: String)
}
