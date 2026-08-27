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

package io.bosonnetwork.photon.feature.contacts

import android.content.Context
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.ResolvedProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Test double: profiles are seeded/updated through [profiles]; prefetch is a no-op. */
internal class FakeProfileResolver(
    initial: Map<String, ResolvedProfile> = emptyMap(),
) : ProfileResolver {
    val profiles = MutableStateFlow(initial)

    override fun profile(userId: String): Flow<ResolvedProfile?> =
        profiles.map { it[userId] }

    override fun cached(userId: String): ResolvedProfile? = profiles.value[userId]

    override fun prefetch(userId: String) = Unit
}

/**
 * A relaxed [Context] test double whose formatted [Context.getString] echoes the arguments it is given.
 * The ViewModels now build their snackbar copy from string resources (`context.getString(format, prefix,
 * reason)`), so echoing the args keeps assertions that a message contains the underlying failure reason
 * (e.g. "boom", "denied") valid without pinning exact localized text.
 *
 * A lookup with no format arguments has nothing to echo, so it yields the resource id instead: that is
 * what lets a test assert WHICH message a failure was mapped to (see [stringId]) without pinning the
 * localized wording.
 */
internal fun fakeContext(): Context = mockk(relaxed = true) {
    // getString(int) and getString(int, vararg) are distinct methods, so both need stubbing.
    every { getString(any()) } answers { stringId(firstArg()) }
    every { getString(any(), *anyVararg()) } answers {
        val args = invocation.args.drop(1)
            .flatMap { if (it is Array<*>) it.toList() else listOf(it) }
        if (args.isEmpty())
            stringId(firstArg())
        else
            args.joinToString(": ") { it?.toString().orEmpty() }
    }
}

/** The text [fakeContext] returns for an argument-less lookup of [resId]. */
internal fun stringId(resId: Int): String = "string:$resId"
