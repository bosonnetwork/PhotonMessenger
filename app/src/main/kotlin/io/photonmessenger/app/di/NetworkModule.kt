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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.photonmessenger.core.boson.BosonDirectorTrustManagerProvider
import io.photonmessenger.core.model.AuthTokenStore
import io.photonmessenger.core.model.AvatarUrls
import io.photonmessenger.core.network.DirectorApiFactory
import io.photonmessenger.core.network.DirectorConfigStore
import io.photonmessenger.core.network.DirectorTrustManagerProvider
import io.photonmessenger.core.network.NotificationPreferencesStore
import io.photonmessenger.core.network.ThemePreferencesStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("photon_settings")
        }

    @Provides
    @Singleton
    fun provideDirectorConfigStore(dataStore: DataStore<Preferences>): DirectorConfigStore =
        DirectorConfigStore(dataStore)

    @Provides
    @Singleton
    fun provideThemePreferencesStore(dataStore: DataStore<Preferences>): ThemePreferencesStore =
        ThemePreferencesStore(dataStore)

    @Provides
    @Singleton
    fun provideNotificationPreferencesStore(dataStore: DataStore<Preferences>): NotificationPreferencesStore =
        NotificationPreferencesStore(dataStore)

    @Provides
    @Singleton
    fun provideDirectorTrustManagerProvider(): DirectorTrustManagerProvider =
        BosonDirectorTrustManagerProvider()

    @Provides
    @Singleton
    fun provideDirectorApiFactory(
        tokenStore: AuthTokenStore,
        trustManagerProvider: DirectorTrustManagerProvider,
    ): DirectorApiFactory =
        DirectorApiFactory(tokenStore, trustManagerProvider)

    /**
     * Public per-user avatar URLs from the configured Director (auth-less
     * `GET /api/v1/client/avatar/{userId}`). Tracks the persisted base URL so feature modules can
     * render contact avatars without a core:network dependency.
     */
    @Provides
    @Singleton
    fun provideAvatarUrls(configStore: DirectorConfigStore): AvatarUrls =
        object : AvatarUrls {
            private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            @Volatile
            private var baseUrl: String? = null

            init {
                scope.launch { configStore.config.collect { baseUrl = it.baseUrl } }
            }

            override fun forUser(userId: String): String? =
                baseUrl?.let { "$it/api/v1/client/avatar/$userId" }
        }
}
