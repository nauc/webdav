package com.webdav.impl

import com.webdav.auth.OAuth2TokenProvider
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp [Interceptor] that attaches the current Bearer token to every request.
 *
 * Works together with OAuth2Authenticator: this interceptor adds the token
 * proactively, while the authenticator handles token refresh when a 401 arrives.
 */
internal class OAuth2Interceptor(
    private val provider: OAuth2TokenProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer ${provider.getAccessToken()}")
            .build()
        return chain.proceed(request)
    }
}

