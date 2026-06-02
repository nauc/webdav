package com.webdav.impl

import com.webdav.auth.OAuth2TokenProvider
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp [Authenticator] that handles HTTP 401 challenges for OAuth2.
 *
 * On receiving a 401 the authenticator calls [OAuth2TokenProvider.refreshAccessToken]
 * and retries the request with the new Bearer token.
 *
 * Infinite-retry protection: if the response already carries an `Authorization`
 * header **and** there is a prior response with the same header (i.e. the
 * refresh itself failed), the authenticator returns `null` to let the caller
 * receive the 401 exception.
 */
internal class OAuth2Authenticator(
    private val provider: OAuth2TokenProvider
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Count how many times we have already retried for this call.
        // responseCount == 1 means first 401 (original request); > 1 means
        // we already attempted a refresh and still get 401 - give up.
        if (response.responseCount() > 1) return null

        val newToken = provider.refreshAccessToken() ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    /** Counts the chain of prior responses to detect retry loops. */
    private fun Response.responseCount(): Int {
        var count = 1
        var prior = priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

