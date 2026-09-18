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
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.ResolvedProfile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * [ProfileResolver] backed by the Director's public profiles with an in-memory TTL cache.
 *
 * Cache design: profiles carry no updatedAt (any persistence would be TTL-guessing anyway), and the
 * Director itself caches remote profiles for 24h - so an in-memory cache per process is the sweet spot.
 * 404s are negative-cached briefly; transient failures retry after a short window. The whole cache is
 * dropped when the Director config changes (profiles are per-Director).
 *
 * [lookup] fetches one profile, returning null for an unknown user; [directorChanges] emits whenever
 * the Director changes. [create] wires both to the Director client.
 */
class DirectorProfileResolver(
    directorChanges: Flow<Any>,
    private val lookup: suspend (userId: String) -> ResolvedProfile?,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ProfileResolver {

    private sealed interface Entry {
        val at: Long

        data class Found(val profile: ResolvedProfile, override val at: Long) : Entry
        data class NotFound(override val at: Long) : Entry
        data class Failed(override val at: Long) : Entry
    }

    private val entries = ConcurrentHashMap<String, MutableStateFlow<Entry?>>()
    private val inFlight = ConcurrentHashMap<String, Job>()

    init {
        scope.launch {
            directorChanges.collect {
                // Profiles are per-Director: reset entries IN PLACE (never replace the StateFlow
                // instances - active collectors stay attached and observe the reset).
                entries.values.forEach { it.value = null }
            }
        }
    }

    override fun profile(userId: String): Flow<ResolvedProfile?> =
        entryFlow(userId)
            // Re-resolve whenever the entry is missing (first collection, or reset by a Director
            // config change) or has outlived its TTL; prefetch dedups concurrent fetches.
            .onEach { entry -> if (entry == null || entry.isStale()) prefetch(userId) }
            .map { (it as? Entry.Found)?.profile }

    override fun cached(userId: String): ResolvedProfile? =
        (entries[userId]?.value as? Entry.Found)?.profile

    override fun prefetch(userId: String) {
        if (userId.isBlank()) return
        val entry = entryFlow(userId).value
        if (entry != null && !entry.isStale()) return
        inFlight.computeIfAbsent(userId) {
            scope.launch { fetch(userId) }.also { job ->
                job.invokeOnCompletion { inFlight.remove(userId) }
            }
        }
    }

    private fun entryFlow(userId: String): MutableStateFlow<Entry?> =
        entries.getOrPut(userId) { MutableStateFlow(null) }

    private fun Entry.isStale(): Boolean {
        val ttl = when (this) {
            is Entry.Found -> FOUND_TTL_MS
            is Entry.NotFound -> NOT_FOUND_TTL_MS
            is Entry.Failed -> FAILED_RETRY_MS
        }
        return nowMs() - at > ttl
    }

    private suspend fun fetch(userId: String) {
        val entry = try {
            lookup(userId)?.let { Entry.Found(it, nowMs()) } ?: Entry.NotFound(nowMs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (e.toDirectorError()) {
                is AppError.NotFound -> Entry.NotFound(nowMs())
                else -> Entry.Failed(nowMs())
            }
        }
        entryFlow(userId).value = entry
    }

    companion object {
        private const val FOUND_TTL_MS = 60L * 60 * 1000 // 1h; the Director caches remote profiles 24h
        private const val NOT_FOUND_TTL_MS = 10L * 60 * 1000 // unknown users re-checked after 10min
        private const val FAILED_RETRY_MS = 30L * 1000 // transient failures retry quickly

        /** A resolver over the Director [clients] of the active profile. */
        fun create(clients: DirectorClients, scope: CoroutineScope): DirectorProfileResolver =
            DirectorProfileResolver(
                directorChanges = clients.config,
                lookup = { userId ->
                    // The user may belong to another node: the Director resolves it for its own users.
                    clients.client().getUserProfile(Id.of(userId)).await()?.let { profile ->
                        ResolvedProfile(
                            userId = profile.id.toString(),
                            name = profile.name.orElse(null)?.takeIf { it.isNotBlank() },
                            bio = profile.bio.orElse(null)?.takeIf { it.isNotBlank() },
                            avatarUrl = if (profile.avatar.orElse("").isBlank()) null
                            else DirectorAvatars.uri(profile.id.toString()),
                        )
                    }
                },
                scope = scope,
            )
    }
}
