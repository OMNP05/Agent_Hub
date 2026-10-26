package dev.agenthub.app.auth.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** Unauthenticated endpoints. Built on a client without the bearer interceptor or authenticator. */
interface AuthApi {
    @POST("auth/google")
    suspend fun signInWithGoogle(@Body body: GoogleSignInRequest): Response<TokenResponse>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): Response<TokenResponse>

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<Unit>
}
