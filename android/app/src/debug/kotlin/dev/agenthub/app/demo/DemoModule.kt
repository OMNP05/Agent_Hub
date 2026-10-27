package dev.agenthub.app.demo

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agenthub.app.BuildConfig
import dev.agenthub.app.auth.data.GoogleIdTokenProvider
import dev.agenthub.app.auth.data.IdTokenResult
import dev.agenthub.app.core.network.DebugHooks
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Pretends to be the Google account picker: always "signs in" with a made-up token. */
internal class DemoIdTokens : GoogleIdTokenProvider {
    override suspend fun request(context: Context): IdTokenResult {
        kotlinx.coroutines.delay(400)
        return IdTokenResult.Token("demo-google-id-token", "demo-nonce")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DemoModule {
    /**
     * The demo is on by default in debug builds and can be switched off with DEMO_BACKEND=false in
     * local.properties to talk to a real backend instead.
     */
    @Provides @Singleton
    fun debugHooks(): DebugHooks = if (BuildConfig.DEMO_MODE) {
        val backend = DemoBackend()
        object : DebugHooks {
            override fun customize(builder: OkHttpClient.Builder) { builder.addInterceptor(backend) }
            override fun idTokens(): GoogleIdTokenProvider = DemoIdTokens()
        }
    } else {
        object : DebugHooks {
            override fun customize(builder: OkHttpClient.Builder) = Unit
            override fun idTokens(): GoogleIdTokenProvider? = null
        }
    }
}
