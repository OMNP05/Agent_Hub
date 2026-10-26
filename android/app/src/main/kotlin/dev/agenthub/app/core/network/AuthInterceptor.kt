package dev.agenthub.app.core.network

import dev.agenthub.app.core.session.SessionStore
import okhttp3.Interceptor
import okhttp3.Response

/** Adds the bearer token, but only for requests to the Agent Hub API host, never to other hosts. */
class AuthInterceptor(
    private val store: SessionStore,
    private val apiHost: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = store.current()?.accessToken
        if (token == null || request.url.host != apiHost || request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}
