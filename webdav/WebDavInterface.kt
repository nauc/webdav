package com.webdav

import com.webdav.auth.OAuth2TokenProvider
import com.webdav.report.WebDavReport
import java.io.File
import java.io.InputStream
import javax.xml.namespace.QName

/**
 * Main interface for all WebDAV operations.
 *
 * All methods are `suspend` functions - they must be called from a coroutine
 * or another suspend function.  The implementation dispatches work to
 * [kotlinx.coroutines.Dispatchers.IO] so it is safe to call from the UI thread.
 */
interface WebDavInterface {

    // Authentication

    /**
     * Configures Basic Auth credentials.
     *
     * @param username        Login name
     * @param password        Password
     * @param isPreemptive    When `true` the Authorization header is sent with
     *                        every request instead of waiting for a 401 challenge.
     */
    fun setCredentials(username: String, password: String, isPreemptive: Boolean = false)

    /**
     * Configures OAuth2 Bearer token authentication.
     *
     * The library will:
     * 1. Add `Authorization: Bearer <token>` to every request via [OAuth2TokenProvider.getAccessToken].
     * 2. On HTTP 401, call [OAuth2TokenProvider.refreshAccessToken] and retry once.
     *
     * This call replaces any previously set credentials.
     *
     * @param tokenProvider   Your implementation of [OAuth2TokenProvider].
     */
    fun setOAuth2Credentials(tokenProvider: OAuth2TokenProvider)

    // PROPFIND / listing

    /** Lists resources at [url] with depth 1 (directory contents). */
    suspend fun list(url: String): List<DavResource>

    /** Lists resources at [url] with the given [depth] (0 = resource itself, −1 = infinity). */
    suspend fun list(url: String, depth: Int): List<DavResource>

    /** Lists resources at [url] requesting all properties (`allprop`) or a minimal set. */
    suspend fun list(url: String, depth: Int, allProp: Boolean): List<DavResource>

    /** Lists resources at [url] requesting the specified custom [props]. */
    suspend fun list(url: String, depth: Int, props: Set<QName>): List<DavResource>

    /** Fetches only the specified [props] for resources at [url]. */
    suspend fun propfind(url: String, depth: Int, props: Set<QName>): List<DavResource>

    // REPORT

    /** Runs a typed [report] against [url]. */
    suspend fun <T> report(url: String, depth: Int, report: WebDavReport<T>): T

    // SEARCH

    /**
     * Performs a WebDAV SEARCH.
     *
     * @param url      Base collection URL
     * @param language Query language (e.g. `"DAV:basicsearch"`)
     * @param query    Query body
     */
    suspend fun search(url: String, language: String, query: String): List<DavResource>

    // PROPPATCH

    /** Sets custom properties on [url]. */
    suspend fun patch(url: String, addProps: Map<QName, String>): List<DavResource>

    /**
     * Sets and/or removes custom properties on [url].
     *
     * @param addProps    Properties to set (update if already present)
     * @param removeProps Properties to remove (no error if absent)
     */
    suspend fun patch(
        url: String,
        addProps: Map<QName, String>,
        removeProps: List<QName>
    ): List<DavResource>

    // GET / PUT / DELETE

    /**
     * Downloads a resource; the caller **must** close the returned [InputStream].
     */
    suspend fun get(url: String): InputStream

    /**
     * Downloads a resource with custom headers; the caller **must** close the returned stream.
     */
    suspend fun get(url: String, headers: Map<String, String>): InputStream

    /** Uploads [data] to [url]. */
    suspend fun put(url: String, data: ByteArray)

    /** Uploads [data] to [url] with the given [contentType]. */
    suspend fun put(url: String, data: ByteArray, contentType: String?)

    /**
     * Uploads [localFile] to [url] with the given [contentType].
     * Uses streaming to avoid loading the entire file into memory.
     */
    suspend fun put(url: String, localFile: File, contentType: String?)

    /** Like [put] above, with optional `Expect: 100-continue` and lock token. */
    suspend fun put(
        url: String,
        localFile: File,
        contentType: String?,
        expectContinue: Boolean,
        lockToken: String? = null
    )

    /**
     * Streaming upload from an [InputStream].
     * [contentLength] must be known in advance; −1 triggers chunked transfer
     * (not supported by all servers).
     */
    suspend fun put(
        url: String,
        stream: InputStream,
        contentType: String?,
        contentLength: Long = -1L
    )

    /** Deletes the resource at [url]. */
    suspend fun delete(url: String)

    // WebDAV collection / copy / move

    /** Creates a collection (directory) at [url] using MKCOL. */
    suspend fun createDirectory(url: String)

    /** Moves [sourceUrl] to [destinationUrl], overwriting if present. */
    suspend fun move(sourceUrl: String, destinationUrl: String)

    /** Moves [sourceUrl] to [destinationUrl]. */
    suspend fun move(sourceUrl: String, destinationUrl: String, overwrite: Boolean)

    /** Moves [sourceUrl] to [destinationUrl] with optional lock token. */
    suspend fun move(
        sourceUrl: String,
        destinationUrl: String,
        overwrite: Boolean,
        lockToken: String?
    )

    /** Copies [sourceUrl] to [destinationUrl], overwriting if present. */
    suspend fun copy(sourceUrl: String, destinationUrl: String)

    /** Copies [sourceUrl] to [destinationUrl]. */
    suspend fun copy(sourceUrl: String, destinationUrl: String, overwrite: Boolean)

    // Existence check

    /**
     * Returns `true` when a HEAD request to [url] returns 2xx, `false` on 404.
     * Throws [com.hoggie.hoggiemap.webdav.exception.WebDavException] on other errors.
     */
    suspend fun exists(url: String): Boolean

    // Locking

    /**
     * Attempts an exclusive write lock on [url].
     * @return The lock token (opaque URI string).
     */
    suspend fun lock(url: String): String

    /**
     * Attempts an exclusive write lock on [url] with the given [timeout] in seconds.
     * @return The lock token.
     */
    suspend fun lock(url: String, timeout: Int): String

    /**
     * Refreshes an existing lock.
     * @param token  The current lock token
     * @param file   The href used in the `If` header
     * @return The new (or unchanged) lock token.
     */
    suspend fun refreshLock(url: String, token: String, file: String): String

    /** Releases a lock previously obtained via [lock]. */
    suspend fun unlock(url: String, token: String)

    // ACL

    /** Retrieves the Access Control List for [url]. */
    suspend fun getAcl(url: String): DavAcl?

    /** Retrieves quota properties for [url]. */
    suspend fun getQuota(url: String): DavQuota?

    /** Writes an ACL to [url]. */
    suspend fun setAcl(url: String, aces: List<DavAce>)

    /** Lists principals available for use in ACL operations at [url]. */
    suspend fun getPrincipals(url: String): List<DavPrincipal>?

    /** Returns the principal-collection-set for [url]. */
    suspend fun getPrincipalCollectionSet(url: String): List<String>?
}

