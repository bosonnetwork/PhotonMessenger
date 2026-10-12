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

import android.content.Context
import io.bosonnetwork.photon.core.boson.KeyManager
import io.bosonnetwork.photon.core.boson.OwnProfileStore
import io.bosonnetwork.photon.core.model.SessionStore
import io.bosonnetwork.photon.core.security.AvatarStore
import io.bosonnetwork.photon.core.security.EncryptedSessionStore
import io.bosonnetwork.photon.core.security.IdentityVault
import io.bosonnetwork.photon.core.security.KeySealer
import io.bosonnetwork.photon.core.security.KeystoreSealer
import io.bosonnetwork.photon.core.security.ProfileManager
import io.bosonnetwork.photon.core.security.SecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    @Provides
    @Singleton
    fun provideProfileManager(@ApplicationContext context: Context): ProfileManager =
        ProfileManager(context.filesDir, context.cacheDir)

    @Provides
    @Singleton
    fun provideSecretStore(@ApplicationContext context: Context, profileManager: ProfileManager): SecretStore =
        SecretStore(context, profileManager.secretsFileName())

    @Provides
    @Singleton
    fun provideSessionStore(secrets: SecretStore): SessionStore = EncryptedSessionStore(secrets)

    /** Seals the identities' keys with the Android Keystore. */
    @Provides
    @Singleton
    fun provideKeySealer(): KeySealer = KeystoreSealer()

    /** The active profile's identity record and keys. */
    @Provides
    @Singleton
    fun provideIdentityVault(profileManager: ProfileManager, sealer: KeySealer): IdentityVault {
        val profileId = profileManager.activeProfileId()
        return IdentityVault(profileManager.filesRootFor(profileId), IdentityVault.aliasFor(profileId), sealer)
    }

    @Provides
    @Singleton
    fun provideKeyManager(vault: IdentityVault): KeyManager = KeyManager(vault)

    /** The active profile's own avatar, in its files. */
    @Provides
    @Singleton
    fun provideAvatarStore(profileManager: ProfileManager): AvatarStore =
        AvatarStore(profileManager.filesRootFor(profileManager.activeProfileId()))

    /** The identity's own profile and avatar, kept as the home node gives them. */
    @Provides
    @Singleton
    fun provideOwnProfileStore(vault: IdentityVault, avatars: AvatarStore): OwnProfileStore = OwnProfileStore(vault, avatars)
}
