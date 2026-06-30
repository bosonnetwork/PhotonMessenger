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

package io.photonmessenger.core.network

import io.photonmessenger.core.network.model.AvatarUriDto
import io.photonmessenger.core.network.model.BindIdentityRequest
import io.photonmessenger.core.network.model.BindIdentityResponse
import io.photonmessenger.core.network.model.DeviceDto
import io.photonmessenger.core.network.model.FinishRegistrationRequest
import io.photonmessenger.core.network.model.FinishRegistrationResponse
import io.photonmessenger.core.network.model.MeDto
import io.photonmessenger.core.network.model.NodeStatusDto
import io.photonmessenger.core.network.model.NonceDto
import io.photonmessenger.core.network.model.ProfileDto
import io.photonmessenger.core.network.model.ProviderDto
import io.photonmessenger.core.network.model.RegisterDeviceRequest
import io.photonmessenger.core.network.model.RegistrationIdDto
import io.photonmessenger.core.network.model.RegistrationInfoDto
import io.photonmessenger.core.network.model.ReplyRegistrationRequest
import io.photonmessenger.core.network.model.SelfRegisterRequest
import io.photonmessenger.core.network.model.TokenDto
import io.photonmessenger.core.network.model.UpdateProfileRequest
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Director REST API consumed by PhotonMessenger. Retrofit is configured with
 * baseUrl = "<directorBaseUrl>/api/v1/", so paths here are relative to that prefix
 * (auth routes under "auth/", client routes under "client/"). See spec 1.7, 2.1-2.2.
 *
 * The OAuth authorize step is NOT here - it is a browser redirect opened in a Custom Tab; build
 * that URL with [DirectorOAuth.authorizeUrl].
 */
interface DirectorApi {
    // --- Auth (some auth-less, some require Bearer; the interceptor attaches the token if present) ---

    @GET("auth/providers")
    suspend fun getProviders(): List<ProviderDto>

    @GET("auth/me")
    suspend fun getMe(): MeDto

    @POST("auth/refresh")
    suspend fun refresh(): TokenDto

    @GET("auth/user-identity/nonce")
    suspend fun getBindingNonce(): NonceDto

    @PUT("auth/user-identity")
    suspend fun bindUserIdentity(@Body body: BindIdentityRequest): BindIdentityResponse

    @DELETE("auth/session")
    suspend fun signOut()

    // --- Client ---

    /** Self-contained (non-OAuth) registration of a user + initial device (spec 2.5). Auth-less. */
    @POST("client/usersAndInitialDevice")
    suspend fun register(@Body body: SelfRegisterRequest): TokenDto

    /** Authenticated service discovery (spec 1.7). */
    @GET("client/node")
    suspend fun getNodeStatus(): NodeStatusDto

    // --- Profile & avatar (spec 2.6, M6-1) ---

    @GET("client/profile")
    suspend fun getProfile(): ProfileDto

    /** Updates any subset of {name, bio, email}; 204 No Content on success. */
    @PUT("client/profile")
    suspend fun updateProfile(@Body body: UpdateProfileRequest)

    /**
     * Uploads the avatar as a raw (streaming) body; the [body]'s media type sets the avatar
     * Content-Type. The Director stores it and returns the new `bnr://` URI.
     */
    @PUT("client/avatar")
    suspend fun updateAvatar(@Body body: RequestBody): AvatarUriDto

    @DELETE("client/avatar")
    suspend fun removeAvatar()

    // --- Devices (account registry; spec screen 6, M6-2/M6-3) ---

    @GET("client/devices")
    suspend fun getDevices(): List<DeviceDto>

    /** Deregisters a device from the account (204 on success, 404 if not owned/found). */
    @DELETE("client/devices/{deviceId}")
    suspend fun removeDevice(@Path("deviceId") deviceId: String)

    // --- Multi-device registration (spec 2.4, M6-4) ---

    /** New device: request registration (auth-less, signed by the device key). */
    @POST("client/devices/registrations")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): RegistrationIdDto

    /** Approver (authenticated): read what the pending registration is asking for. */
    @GET("client/devices/registrations/{id}")
    suspend fun getRegistration(@Path("id") registrationId: String): RegistrationInfoDto

    /** Approver (authenticated): approve (with the sealed user key) or deny; 204 on success. */
    @PATCH("client/devices/registrations/{id}")
    suspend fun replyRegistration(
        @Path("id") registrationId: String,
        @Body body: ReplyRegistrationRequest,
    )

    /** New device: finish once approved (auth-less, signed by the device key). */
    @POST("client/devices/registrations/{id}")
    suspend fun finishRegistration(
        @Path("id") registrationId: String,
        @Body body: FinishRegistrationRequest,
    ): FinishRegistrationResponse
}
