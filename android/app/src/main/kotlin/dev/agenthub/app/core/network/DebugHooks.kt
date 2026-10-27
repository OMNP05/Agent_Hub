package dev.agenthub.app.core.network

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agenthub.app.auth.data.GoogleIdTokenProvider
import okhttp3.OkHttpClient

/**
 * Extension point for debug builds only. The debug source set binds an implementation (the demo backend);
 * release builds bind nothing, so no demo code or behaviour can reach a release APK.
 */
interface DebugHooks {
    fun customize(builder: OkHttpClient.Builder)

    /** A stand-in for Google sign-in, or null to use the real one. */
    fun idTokens(): GoogleIdTokenProvider?
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugHooksModule {
    @BindsOptionalOf abstract fun debugHooks(): DebugHooks
}
