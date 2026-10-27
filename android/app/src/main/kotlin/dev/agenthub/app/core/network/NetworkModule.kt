package dev.agenthub.app.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.agenthub.app.BuildConfig
import dev.agenthub.app.auth.data.AuthApi
import dev.agenthub.app.auth.data.AuthRepository
import dev.agenthub.app.auth.data.CredentialManagerGoogleIdTokenProvider
import dev.agenthub.app.auth.data.DefaultAuthRepository
import dev.agenthub.app.auth.data.GoogleIdTokenProvider
import dev.agenthub.app.core.session.AesGcmCipher
import dev.agenthub.app.core.session.DataStoreSessionStore
import dev.agenthub.app.core.session.KeystoreKey
import dev.agenthub.app.core.session.SessionStore
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Provides @Singleton
    fun sessionStore(@ApplicationContext context: Context): SessionStore =
        DataStoreSessionStore(context.sessionDataStore, AesGcmCipher { KeystoreKey.get() })

    /** Plain client: no auth headers, used for sign-in and refresh so refresh can't recurse. */
    @Provides @Singleton @Named("plain")
    fun plainClient(hooks: java.util.Optional<DebugHooks>): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .also { builder -> hooks.ifPresent { it.customize(builder) } }
        .build()

    @Provides @Singleton
    fun authApi(@Named("plain") client: OkHttpClient, json: Json): AuthApi = retrofit(client, json).create(AuthApi::class.java)

    @Provides @Singleton @Named("authed")
    fun authedClient(@Named("plain") plain: OkHttpClient, store: SessionStore, authApi: AuthApi): OkHttpClient =
        plain.newBuilder()
            .addInterceptor(AuthInterceptor(store, BuildConfig.API_BASE_URL.toHttpUrl().host))
            .authenticator(TokenAuthenticator(store, authApi))
            .build()

    @Provides @Singleton @Named("authed")
    fun authedRetrofit(@Named("authed") client: OkHttpClient, json: Json): Retrofit = retrofit(client, json)

    private fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthBindingsModule {
    @dagger.Binds abstract fun authRepository(impl: DefaultAuthRepository): AuthRepository
    @dagger.Binds abstract fun idTokenProvider(impl: CredentialManagerGoogleIdTokenProvider): GoogleIdTokenProvider
}
