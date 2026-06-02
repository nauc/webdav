package com.webdav.auth

/**
 * Provides OAuth2 Bearer tokens for WebDAV requests.
 *
 * Implement this interface to integrate any OAuth2 library
 * (e.g. AppAuth, MSAL, Retrofit + custom flow) with the WebDAV client.
 *
 * All methods are called on a background thread (OkHttp's dispatcher),
 * so blocking I/O is allowed.
 *
 * ### Example (simple in-memory token):
 * ```kotlin
 * val provider = object : OAuth2TokenProvider {
 *     private var token = "initial-access-token"
 *
 *     override fun getAccessToken() = token
 *
 *     override fun refreshAccessToken(): String? {
 *         // Call your token endpoint, update `token`, return new value.
 *         token = myOAuthClient.refreshBlocking() ?: return null
 *         return token
 *     }
 * }
 * dav.setOAuth2Credentials(provider)
 * ```
 */
interface OAuth2TokenProvider {

    /**
     * Returns the current (possibly cached) access token.
     * Called before every request to add the `Authorization: Bearer ...` header.
     */
    fun getAccessToken(): String

    /**
     * Forces a token refresh and returns the **new** access token.
     *
     * Called automatically when the server responds with HTTP 401.
     * Return `null` to cancel the retry (the 401 will be propagated to the caller).
     *
     * Implementations must be thread-safe - OkHttp may call this from
     * multiple threads concurrently if several requests fail with 401.
     */
    fun refreshAccessToken(): String?
}

