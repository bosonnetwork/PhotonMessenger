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

package io.bosonnetwork.photon.app.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.bosonnetwork.photon.core.boson.DirectorClients
import io.bosonnetwork.photon.core.boson.DirectorProfileResolver
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.security.ProfileManager
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.network.DirectorConfigStore
import io.bosonnetwork.photon.core.network.NotificationPreferencesStore
import io.bosonnetwork.photon.core.network.ThemePreferencesStore
import io.vertx.core.Vertx
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(profileManager: ProfileManager): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            java.io.File(profileManager.activeFilesRoot(), "settings.preferences_pb")
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

    /** All Director communication of the active profile, on the shared Vert.x runtime. */
    @Provides
    @Singleton
    fun provideDirectorClients(
        vertx: Vertx,
        configStore: DirectorConfigStore,
        keyManager: KeyManager,
    ): DirectorClients = DirectorClients(vertx, configStore, keyManager)

    /**
     * Resolves public profiles (name/bio/avatar) for ANY user id from the Director, with in-memory TTL
     * caching. Enriches friend requests, channel member lists, and chat sender names beyond what the
     * Boson library provides locally.
     */
    @Provides
    @Singleton
    fun provideProfileResolver(clients: DirectorClients): ProfileResolver =
        DirectorProfileResolver.create(clients, CoroutineScope(SupervisorJob() + Dispatchers.IO))
}
