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

package io.photonmessenger.app.di

import android.content.Context
import io.photonmessenger.core.boson.BosonClientFactory
import io.photonmessenger.core.boson.BosonSessionManager
import io.photonmessenger.core.boson.DefaultUnreadTracker
import io.photonmessenger.core.boson.KeyManager
import io.photonmessenger.core.boson.UnreadTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.bosonnetwork.photonmessaging.MessagingStore
import io.vertx.core.Vertx
import javax.inject.Singleton

/**
 * Provides the single shared Vert.x runtime used by both MessagingClient and IonStore, and the
 * factory that builds those clients once sign-in keys + discovered coordinates exist
 * (design spec section 4.4). The MessagingClient/IonStore singletons themselves are constructed
 * lazily in M1 from a session-scoped component, since they require post-discovery inputs.
 */
@Module
@InstallIn(SingletonComponent::class)
object BosonModule {
    @Provides
    @Singleton
    fun provideVertx(): Vertx = BosonClientFactory.newVertx()

    @Provides
    @Singleton
    fun provideBosonClientFactory(vertx: Vertx): BosonClientFactory = BosonClientFactory(vertx)

    @Provides
    @Singleton
    fun provideBosonSessionManager(
        @ApplicationContext context: Context,
        factory: BosonClientFactory,
        keyManager: KeyManager,
        store: MessagingStore,
    ): BosonSessionManager = BosonSessionManager(factory, keyManager, store, context.filesDir)

    @Provides
    @Singleton
    fun provideUnreadTracker(session: BosonSessionManager): UnreadTracker =
        DefaultUnreadTracker(session)
}
