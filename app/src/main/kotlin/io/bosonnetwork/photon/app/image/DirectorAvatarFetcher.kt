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

package io.bosonnetwork.photon.app.image

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import io.bosonnetwork.Id
import io.bosonnetwork.photon.core.boson.DirectorAvatars
import io.bosonnetwork.photon.core.boson.DirectorClients
import io.bosonnetwork.photon.core.model.AppError
import kotlinx.coroutines.future.await
import okio.Buffer

/**
 * Loads a [DirectorAvatars] model through the Director client, which authenticates the request with the
 * profile's own key (the Director fetches a remote user's avatar only for a signed-in user of its own) and
 * pins a self-signed Director to its node id. A user without an avatar fails the request, so the image
 * falls back to initials.
 *
 * Coil keeps decoded avatars in its memory cache, keyed by the model URI; they are not written to its
 * disk cache, so each process downloads an avatar once and always gets the current one.
 */
class DirectorAvatarFetcher(
    private val userId: Id,
    private val clients: DirectorClients,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val avatar = clients.client().getUserAvatar(userId).await()
            ?: throw AppError.NotFound("No avatar for $userId")
        return SourceResult(
            source = ImageSource(Buffer().write(avatar.data), options.context),
            mimeType = avatar.contentType,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val clients: DirectorClients) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            DirectorAvatars.userIdOf(data)?.let { DirectorAvatarFetcher(it, clients, options) }
    }
}
