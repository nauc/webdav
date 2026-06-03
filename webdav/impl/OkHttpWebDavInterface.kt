package com.webdav.impl

import com.webdav.DavAce
import com.webdav.DavAcl
import com.webdav.DavPrincipal
import com.webdav.DavQuota
import com.webdav.DavResource
import com.webdav.WebDavInterface
import com.webdav.auth.OAuth2TokenProvider
import com.webdav.exception.AuthException
import com.webdav.exception.InvalidResponseException
import com.webdav.exception.ResourceNotFoundException
import com.webdav.exception.WebDavException
import com.webdav.report.WebDavReport
import com.webdav.util.WebDavUtil
import com.webdav.xml.DavXmlBuilder
import com.webdav.xml.DavXmlParser
import com.webdav.xml.ParsedMultistatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.logging.Logger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.xml.namespace.QName

/**
 * Kotlin coroutines-based implementation of the [WebDavInterface] WebDAV client.
 *
 * All suspend methods dispatch onto [Dispatchers.IO] internally, so they are
 * safe to call from the main (UI) thread.
 *
 * ### Basic usage
 * ```kotlin
 * val sardine = OkHttpSardine()
 * sardine.setCredentials("user", "pass")
 *
 * lifecycleScope.launch {
 *     val resources = sardine.list("https://dav.example.com/files/")
 * }
 * ```
 */
class OkHttpWebDavInterface @JvmOverloads constructor(
    @Volatile private var client: OkHttpClient = OkHttpClient()
) : WebDavInterface {

    private val log = Logger.getLogger(OkHttpWebDavInterface::class.java.name)

    // Authentication

    override fun setCredentials(username: String, password: String, isPreemptive: Boolean) {
        val builder = client.newBuilder()
        if (isPreemptive) {
            builder.addInterceptor(preemptiveAuthInterceptor(username, password))
        } else {
            builder.authenticator(BasicAuthenticator(username, password))
        }
        client = builder.build()
    }

    override fun setOAuth2Credentials(tokenProvider: OAuth2TokenProvider) {
        client = client.newBuilder()
            .addInterceptor(OAuth2Interceptor(tokenProvider))
            .authenticator(OAuth2Authenticator(tokenProvider))
            .build()
    }

    /**
     * Adds automatic retry with exponential back-off to this client.
     *
     * Call once during setup (before the first request). Safe to call multiple times -
     * each call wraps the current client with a new [RetryInterceptor].
     *
     * **Note:** InputStream-based `put(stream, ...)` is never retried because the source
     * stream has already been consumed and cannot be replayed.
     *
     * @param maxRetries     Max additional attempts after first failure (default: 3).
     * @param initialDelayMs Delay before first retry in milliseconds (default: 1 000).
     */
    fun enableRetry(maxRetries: Int = 3, initialDelayMs: Long = 1_000L) {
        client = client.newBuilder()
            .addInterceptor(RetryInterceptor(maxRetries, initialDelayMs))
            .build()
    }

    // Yandex Disk

    /**
     * Configures authentication for **Yandex Disk** WebDAV.
     *
     * Yandex requires `Authorization: OAuth <token>` - the standard `Bearer` scheme
     * used by [setOAuth2Credentials] will be rejected with HTTP 401.
     *
     * ### Quick start
     * ```kotlin
     * val dav = OkHttpWebDavInterface()
     * dav.setYandexOAuthToken("y0_AgA...your-token...")
     * dav.enableRetry()
     *
     * val files = dav.list("https://webdav.yandex.ru/")
     * dav.download("https://webdav.yandex.ru/backup.zip", File(cacheDir, "backup.zip"))
     * ```
     *
     * Obtain a token at https://oauth.yandex.ru
     * Required scope: `cloud_api:disk.read` and/or `cloud_api:disk.write`.
     *
     * @param token  Yandex OAuth token (starts with `y0_` or `AQAAA…`).
     */
    fun setYandexOAuthToken(token: String) {
        client = client.newBuilder()
            .addInterceptor(YandexOAuthInterceptor(token))
            .build()
    }

    // Synology NAS

    /**
     * Trusts a specific self-signed [certificate] for **Synology NAS** (or any private server).
     *
     * Synology DSM ships with a self-signed certificate by default. Without this call,
     * OkHttp will throw `SSLHandshakeException: PKIX path building failed` on every request.
     *
     * ### Quick start
     * ```kotlin
     * // 1. Export the NAS certificate (Settings -> Security -> Certificate -> Export)
     * // 2. Place the .crt / .pem file in res/raw/synology_cert.crt
     * // 3. Load and trust it:
     *
     * val cert = resources.openRawResource(R.raw.synology_cert).use { input ->
     *     CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
     * }
     * val dav = OkHttpWebDavInterface()
     * dav.trustCertificate(cert)
     * dav.setCredentials("admin", "app-password", isPreemptive = true)
     *
     * // Default Synology ports: 5005 (HTTP) / 5006 (HTTPS)
     * val files = dav.list("https://192.168.1.100:5006/")
     * ```
     *
     * **Note:** If your Synology has a valid Let's Encrypt certificate
     * (configured in DSM -> Security -> Certificate), this call is not needed.
     *
     * **2FA users:** Generate an **Application Password** in DSM -> Personal ->
     * Security -> App Passwords and use that instead of your login password.
     *
     * @param certificate  The server's self-signed X.509 certificate.
     */
    fun trustCertificate(certificate: X509Certificate) {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("trusted-server", certificate)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        val trustManager = tmf.trustManagers.first() as X509TrustManager
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        client = client.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    // Factory helpers
    
    companion object {

        /** Base URL for Yandex Disk WebDAV. */
        const val YANDEX_DISK_URL = "https://webdav.yandex.ru"

        /**
         * Creates a pre-configured client for **Yandex Disk**.
         *
         * ```kotlin
         * val dav = OkHttpWebDavInterface.forYandexDisk("y0_AgA...your-token...")
         * val files = dav.list(OkHttpWebDavInterface.YANDEX_DISK_URL + "/")
         * ```
         */
        fun forYandexDisk(oauthToken: String, retries: Int = 3): OkHttpWebDavInterface =
            OkHttpWebDavInterface().apply {
                setYandexOAuthToken(oauthToken)
                enableRetry(maxRetries = retries)
            }

        /**
         * Creates a pre-configured client for a **Synology NAS** with Basic Auth.
         *
         * ```kotlin
         * val cert = ... // load from res/raw or file system
         * val dav = OkHttpWebDavInterface.forSynology(
         *     host         = "192.168.1.100",
         *     port         = 5006,
         *     username     = "admin",
         *     appPassword  = "xxxx-xxxx-xxxx",
         *     certificate  = cert  // null if Let's Encrypt cert is configured in DSM
         * )
         * val files = dav.list("https://192.168.1.100:5006/")
         * ```
         *
         * @param host         NAS IP or hostname (e.g. `"192.168.1.100"` or `"mynas.synology.me"`).
         * @param port         WebDAV HTTPS port — default `5006` (HTTP: `5005`).
         * @param username     DSM user login.
         * @param appPassword  User's **Application Password** (required when 2FA is enabled).
         * @param certificate  Self-signed certificate to trust, or `null` for valid/LE certs.
         */
        fun forSynology(
            host: String,
            port: Int = 5006,
            username: String,
            appPassword: String,
            certificate: X509Certificate? = null
        ): OkHttpWebDavInterface = OkHttpWebDavInterface().apply {
            certificate?.let { trustCertificate(it) }
            setCredentials(username, appPassword, isPreemptive = true)
            enableRetry()
        }

        /**
         * Returns the root WebDAV URL for a Synology NAS.
         * Scheme is `https` for default SSL port 5006, `http` otherwise.
         */
        fun synologyUrl(host: String, port: Int = 5006): String {
            val scheme = if (port == 5006 || port == 443) "https" else "http"
            return "$scheme://$host:$port"
        }
    }

    private fun preemptiveAuthInterceptor(username: String, password: String) =
        Interceptor { chain ->
            val credential = Credentials.basic(username, password, WebDavUtil.standardUTF8())
            val request = chain.request().newBuilder()
                .header("Authorization", credential)
                .build()
            chain.proceed(request)
        }

    // PROPFIND / listing

    override suspend fun list(url: String): List<DavResource> = list(url, 1)

    override suspend fun list(url: String, depth: Int): List<DavResource> =
        list(url, depth, allProp = true)

    override suspend fun list(url: String, depth: Int, allProp: Boolean): List<DavResource> =
        if (allProp) {
            propfindRaw(url, depth, DavXmlBuilder.propfindAllProp())
        } else {
            propfindRaw(url, depth, DavXmlBuilder.propfindProps(emptySet()))
        }

    override suspend fun list(
        url: String,
        depth: Int,
        props: Set<QName>
    ): List<DavResource> = propfindRaw(url, depth, DavXmlBuilder.propfindProps(props))

    override suspend fun propfind(
        url: String,
        depth: Int,
        props: Set<QName>
    ): List<DavResource> = propfindRaw(url, depth, DavXmlBuilder.propfindProps(props))

    private suspend fun propfindRaw(url: String, depth: Int, xmlBody: String): List<DavResource> =
        withContext(Dispatchers.IO) {
            val request = buildPropfindRequest(url, depth, xmlBody)
            executeForMultistatus(request).toResources()
        }

    // REPORT

    override suspend fun <T> report(
        url: String,
        depth: Int,
        report: WebDavReport<T>
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Depth", depthHeader(depth))
            .method("REPORT", report.toXml().toXmlRequestBody())
            .build()
        val multistatus = executeForMultistatus(request)
        report.fromMultistatus(multistatus)
    }

    // SEARCH

    override suspend fun search(
        url: String,
        language: String,
        query: String
    ): List<DavResource> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .method("SEARCH", DavXmlBuilder.searchRequest(language, query).toXmlRequestBody())
            .build()
        executeForMultistatus(request).toResources()
    }

    // PROPPATCH

    override suspend fun patch(
        url: String,
        addProps: Map<QName, String>
    ): List<DavResource> = patch(url, addProps, emptyList())

    override suspend fun patch(
        url: String,
        addProps: Map<QName, String>,
        removeProps: List<QName>
    ): List<DavResource> = withContext(Dispatchers.IO) {
        val xml = DavXmlBuilder.proppatch(addProps, removeProps)
        val request = Request.Builder()
            .url(url)
            .method("PROPPATCH", xml.toXmlRequestBody())
            .build()
        executeForMultistatus(request).toResources()
    }

    // GET (streaming - caller must close the returned InputStream)

    override suspend fun get(url: String): InputStream =
        get(url, emptyMap())

    override suspend fun get(
        url: String,
        headers: Map<String, String>
    ): InputStream = withContext(Dispatchers.IO) {
        val headersBuilder = Headers.Builder()
        headers.forEach { (k, v) -> headersBuilder.add(k, v) }
        val request = Request.Builder()
            .url(url)
            .get()
            .headers(headersBuilder.build())
            .build()
        val response = client.newCall(request).execute()
        response.checkSuccess()
        response.body.byteStream()
    }

    // DOWNLOAD with Range-resume + progress

    /**
     * Downloads a remote resource to [destination] with automatic **resume** support.
     *
     * If [destination] already exists (partial download), the method sends a
     * `Range: bytes=<size>-` header and **appends** the remaining bytes.
     * The file is overwritten from scratch when the server returns `200` (no Range support).
     * HTTP `416 Range Not Satisfiable` means the file is already complete - the method
     * returns without downloading anything.
     *
     * Combine with [enableRetry] for fully resilient downloading:
     * ```kotlin
     * dav.enableRetry(maxRetries = 5)
     * dav.download("https://dav.example.com/big.zip", File(cacheDir, "big.zip")) { done, total ->
     *     progressBar.progress = (done * 100 / total).toInt()
     * }
     * ```
     *
     * @param url         Remote resource URL.
     * @param destination Local file to write into (created if absent, appended to if partial).
     * @param onProgress  Optional callback invoked after each buffer write:
     *                    `(bytesWritten, totalExpected)`. `totalExpected` is -1 when the
     *                    server does not provide a Content-Length.
     */
    suspend fun download(
        url: String,
        destination: File,
        onProgress: ((bytesWritten: Long, totalExpected: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val existingBytes = if (destination.exists()) destination.length() else 0L

        val requestBuilder = Request.Builder().url(url).get()
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            when (response.code) {
                200 -> {
                    // Server ignored Range header -> full download, overwrite file
                    val total = response.body.contentLength()
                    destination.outputStream().use { out ->
                        pipeWithProgress(response.body.byteStream(), out, 0L, total, onProgress)
                    }
                }
                206 -> {
                    // 206 Partial Content -> append remaining bytes to existing file
                    val remaining = response.body.contentLength()
                    val total = if (remaining >= 0) existingBytes + remaining else -1L
                    FileOutputStream(destination, /* append = */ true).use { out ->
                        pipeWithProgress(response.body.byteStream(), out, existingBytes, total, onProgress)
                    }
                }
                416 -> {
                    // Range Not Satisfiable -> file already complete, nothing to do
                    log.info("download: $url already complete (416), skipping")
                }
                else -> response.checkSuccess() // throws WebDavException
            }
        }
    }

    // -----------------------------------------------------------------------
    // PUT with Content-Range resume + progress (Nextcloud / ownCloud compatible)
    // -----------------------------------------------------------------------

    /**
     * Uploads [localFile] to [url] with **resumable** semantics via `Content-Range`.
     *
     * Supported by **Nextcloud**, **ownCloud** and any server that accepts partial PUTs.
     * Standard WebDAV servers that ignore `Content-Range` will treat the call as a
     * regular full PUT (safe fallback).
     *
     * ### How resume works
     * 1. Call `putResumable(url, file, ..., offset = 0)` to start.
     * 2. If the connection drops at byte N, catch the IOException and call again
     *    with `offset = N` (or query the server with a HEAD to learn the current size).
     * 3. The method sends `Content-Range: bytes <offset>-<last>/<total>` so the
     *    server can assemble the pieces.
     *
     * @param url         Remote destination URL.
     * @param localFile   Local file to upload.
     * @param contentType MIME type (e.g. `"application/octet-stream"`).
     * @param offset      Byte offset to resume from (0 = fresh upload).
     * @param onProgress  Optional callback: `(bytesUploaded, totalFileSize)`.
     */
    suspend fun putResumable(
        url: String,
        localFile: File,
        contentType: String? = null,
        offset: Long = 0L,
        onProgress: ((bytesSent: Long, total: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val total = localFile.length()
        require(offset in 0..total) { "offset ($offset) out of range [0..$total]" }

        val mediaType = contentType?.toMediaTypeOrNull()

        val requestBody = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = total - offset
            override fun writeTo(sink: BufferedSink) {
                localFile.inputStream().use { fis ->
                    if (offset > 0) fis.skip(offset)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var sent = offset
                    while (true) {
                        val read = fis.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        sent += read
                        onProgress?.invoke(sent, total)
                    }
                }
            }
        }

        val headersBuilder = Headers.Builder()
        if (offset > 0 || total > 0) {
            // Content-Range: bytes <first>-<last>/<total>
            headersBuilder.add("Content-Range", "bytes $offset-${total - 1}/$total")
        }

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .headers(headersBuilder.build())
            .build()

        executeVoid(request)
    }

    // DELETE

    override suspend fun delete(url: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).delete().build()
        executeVoid(request)
    }

    // PUT (byte array)

    override suspend fun put(url: String, data: ByteArray) =
        put(url, data, null)

    override suspend fun put(
        url: String,
        data: ByteArray,
        contentType: String?
    ) = withContext(Dispatchers.IO) {
        val mediaType = contentType?.toMediaTypeOrNull()
        putInternal(url, data.toRequestBody(mediaType), Headers.headersOf())
    }

    // PUT (File) - streaming, no full buffer in RAM

    override suspend fun put(
        url: String,
        localFile: File,
        contentType: String?
    ) = put(url, localFile, contentType, expectContinue = false, lockToken = null)

    override suspend fun put(
        url: String,
        localFile: File,
        contentType: String?,
        expectContinue: Boolean,
        lockToken: String?
    ) = withContext(Dispatchers.IO) {
        val mediaType = contentType?.toMediaTypeOrNull()
        val requestBody = localFile.asRequestBody(mediaType)
        val headersBuilder = Headers.Builder()
        if (expectContinue) headersBuilder.add("Expect", "100-Continue")
        if (!lockToken.isNullOrEmpty()) addLockTokenHeader(headersBuilder, url, lockToken)
        putInternal(url, requestBody, headersBuilder.build())
    }

    // PUT (InputStream) - true streaming upload

    /**
     * Streams [stream] directly to the server via OkHttp's [BufferedSink],
     * avoiding loading the entire content into memory.
     *
     * @param contentLength Content-Length in bytes, or -1 for chunked transfer.
     */
    override suspend fun put(
        url: String,
        stream: InputStream,
        contentType: String?,
        contentLength: Long
    ) = withContext(Dispatchers.IO) {
        val mediaType = contentType?.toMediaTypeOrNull()
        val requestBody = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = contentLength
            // InputStream can only be read once - mark as one-shot so RetryInterceptor
            // and OkHttp itself do not attempt to replay the body on failure.
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                stream.source().use { source -> sink.writeAll(source) }
            }
        }
        putInternal(url, requestBody, Headers.headersOf())
    }

    /**
     * Reads [input] in chunks, writes to [output], and invokes [onProgress] after each write.
     *
     * @param alreadyWritten Bytes already on disk before this call (for resume; 0 on fresh download).
     * @param total          Expected total file size in bytes, or -1 if unknown.
     */
    private fun pipeWithProgress(
        input: InputStream,
        output: OutputStream,
        alreadyWritten: Long,
        total: Long,
        onProgress: ((bytesWritten: Long, totalExpected: Long) -> Unit)?
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = alreadyWritten
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            written += read
            onProgress?.invoke(written, total)
        }
    }

    private fun putInternal(url: String, body: RequestBody, headers: Headers) {
        val request = Request.Builder()
            .url(url)
            .put(body)
            .headers(headers)
            .build()
        executeVoid(request)
    }


    // MKCOL / MOVE / COPY

    override suspend fun createDirectory(url: String) = withContext(Dispatchers.IO) {
        executeVoid(Request.Builder().url(url).method("MKCOL", null).build())
    }

    override suspend fun move(sourceUrl: String, destinationUrl: String) =
        move(sourceUrl, destinationUrl, overwrite = true)

    override suspend fun move(
        sourceUrl: String,
        destinationUrl: String,
        overwrite: Boolean
    ) = move(sourceUrl, destinationUrl, overwrite, lockToken = null)

    override suspend fun move(
        sourceUrl: String,
        destinationUrl: String,
        overwrite: Boolean,
        lockToken: String?
    ) = withContext(Dispatchers.IO) {
        val headersBuilder = Headers.Builder()
            .add("Destination", destinationUrl)
            .add("Overwrite", if (overwrite) "T" else "F")
        if (!lockToken.isNullOrEmpty()) addLockTokenHeader(headersBuilder, destinationUrl, lockToken)
        executeVoid(
            Request.Builder()
                .url(sourceUrl)
                .method("MOVE", null)
                .headers(headersBuilder.build())
                .build()
        )
    }

    override suspend fun copy(sourceUrl: String, destinationUrl: String) =
        copy(sourceUrl, destinationUrl, overwrite = true)

    override suspend fun copy(
        sourceUrl: String,
        destinationUrl: String,
        overwrite: Boolean
    ) = withContext(Dispatchers.IO) {
        executeVoid(
            Request.Builder()
                .url(sourceUrl)
                .method("COPY", null)
                .header("Destination", URI.create(destinationUrl).toASCIIString())
                .header("Overwrite", if (overwrite) "T" else "F")
                .build()
        )
    }

    // HEAD (exists)

    override suspend fun exists(url: String): Boolean = withContext(Dispatchers.IO) {
        val response = client.newCall(
            Request.Builder().url(url).head().build()
        ).execute()
        response.use {
            when {
                it.isSuccessful -> true
                it.code == 404  -> false
                else            -> throw WebDavException(it.code, "HEAD $url failed: ${it.message}")
            }
        }
    }

    // LOCK / UNLOCK

    override suspend fun lock(url: String): String = lock(url, timeout = 0)

    override suspend fun lock(url: String, timeout: Int): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .method("LOCK", DavXmlBuilder.lockinfo().toXmlRequestBody())
        if (timeout > 0) builder.header("Timeout", "Second-$timeout")
        execute(builder.build()).use { response ->
            response.checkSuccess()
            DavXmlParser.parseLockToken(response.body.byteStream())
                ?: throw InvalidResponseException("Lock token not found in LOCK response")
        }
    }

    override suspend fun refreshLock(
        url: String,
        token: String,
        file: String
    ): String = withContext(Dispatchers.IO) {
        execute(
            Request.Builder()
                .url(url)
                .method("LOCK", null)
                .header("If", "<$file> (<$token>)")
                .build()
        ).use { response ->
            response.checkSuccess()
            DavXmlParser.parseLockToken(response.body.byteStream())
                ?: throw InvalidResponseException("Lock token not found in LOCK refresh response")
        }
    }

    override suspend fun unlock(url: String, token: String) = withContext(Dispatchers.IO) {
        executeVoid(
            Request.Builder()
                .url(url)
                .method("UNLOCK", null)
                .header("Lock-Token", "<$token>")
                .build()
        )
    }

    // ACL

    override suspend fun getAcl(url: String): DavAcl? = withContext(Dispatchers.IO) {
        val request = buildPropfindRequest(url, 0, DavXmlBuilder.propfindAcl())
        val multistatus = executeForMultistatus(request)
        multistatus.responses.firstOrNull()?.let { DavAcl.fromParsedResponse(it) }
    }

    override suspend fun getQuota(url: String): DavQuota? = withContext(Dispatchers.IO) {
        val request = buildPropfindRequest(url, 0, DavXmlBuilder.propfindQuota())
        val multistatus = executeForMultistatus(request)
        multistatus.responses.firstOrNull()?.let { DavQuota.fromParsedResponse(it) }
    }

    override suspend fun setAcl(url: String, aces: List<DavAce>) = withContext(Dispatchers.IO) {
        executeVoid(
            Request.Builder()
                .url(url)
                .method("ACL", DavXmlBuilder.aclRequest(aces).toXmlRequestBody())
                .build()
        )
    }

    override suspend fun getPrincipals(url: String): List<DavPrincipal>? =
        withContext(Dispatchers.IO) {
            val request = buildPropfindRequest(url, 1, DavXmlBuilder.propfindPrincipals())
            val multistatus = executeForMultistatus(request)
            if (multistatus.responses.isEmpty()) return@withContext null

            buildList {
                for (response in multistatus.responses) {
                    for (propStat in response.propStats) {
                        val prop = propStat.prop
                        if (prop.isPrincipal) {
                            add(
                                DavPrincipal.fromParsed(
                                    href = response.href,
                                    key = null,
                                    propertyNs = null,
                                    propertyLocal = null,
                                    displayName = prop.displayName
                                )
                            )
                        }
                    }
                }
            }.takeIf { it.isNotEmpty() }
        }

    override suspend fun getPrincipalCollectionSet(url: String): List<String>? =
        withContext(Dispatchers.IO) {
            val request = buildPropfindRequest(url, 0, DavXmlBuilder.propfindPrincipalCollectionSet())
            val multistatus = executeForMultistatus(request)
            if (multistatus.responses.isEmpty()) return@withContext null

            buildList {
                for (response in multistatus.responses) {
                    for (propStat in response.propStats) {
                        addAll(propStat.prop.principalCollectionSetHrefs)
                    }
                }
            }.takeIf { it.isNotEmpty() }
        }

    // Low-level helpers

    private fun buildPropfindRequest(url: String, depth: Int, xmlBody: String): Request =
        Request.Builder()
            .url(url)
            .header("Depth", depthHeader(depth))
            .method("PROPFIND", xmlBody.toXmlRequestBody())
            .build()

    /** Executes [request] and returns the parsed [ParsedMultistatus]. */
    @Throws(IOException::class)
    private fun executeForMultistatus(request: Request): ParsedMultistatus {
        val response = execute(request)
        response.checkSuccess()
        return response.body.byteStream().use { DavXmlParser.parseMultistatus(it) }
    }

    /** Executes [request] and discards the response body, throwing on non-success. */
    @Throws(IOException::class)
    private fun executeVoid(request: Request) {
        execute(request).use { it.checkSuccess() }
    }

    /** Executes [request] and returns the raw [Response] (caller is responsible for closing). */
    @Throws(IOException::class)
    private fun execute(request: Request): Response = client.newCall(request).execute()

    // Extension helpers

    /** Throws a typed [WebDavException] if the response is not successful. */
    private fun Response.checkSuccess() {
        if (!isSuccessful) {
            val url = request.url.toString()
            val ex = when (code) {
                401, 403 -> AuthException(code, "Access denied for $url ($message)")
                404      -> ResourceNotFoundException(url)
                else     -> WebDavException(code, "HTTP $code for $url: $message")
            }
            close()
            throw ex
        }
    }

    private fun ParsedMultistatus.toResources(): List<DavResource> {
        val result = mutableListOf<DavResource>()
        for (r in responses) {
            val resource = DavResource.fromParsedResponse(r)
            if (resource != null) result.add(resource)
            else log.warning("Ignored resource with invalid URI: ${r.href}")
        }
        return result
    }

    private fun String.toXmlRequestBody(): RequestBody =
        toRequestBody("text/xml; charset=utf-8".toMediaTypeOrNull())

    private fun depthHeader(depth: Int): String = if (depth < 0) "infinity" else depth.toString()

    private fun addLockTokenHeader(
        builder: Headers.Builder,
        destinationUrl: String,
        lockToken: String
    ) {
        builder.add("If", "<$destinationUrl> (<$lockToken>)")
    }
}

