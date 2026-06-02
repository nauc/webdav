package com.webdav

import com.webdav.util.WebDavUtil
import com.webdav.xml.ParsedDavResponse
import java.net.URI
import java.util.Date
import java.util.logging.Logger
import javax.xml.namespace.QName

/**
 * Describes a resource (file or directory) on a remote WebDAV server.
 *
 * Construct via [DavResource.fromParsedResponse].
 */
class DavResource private constructor(
    /** Full URI of the resource as returned by the server. */
    val href: URI,
    /** HTTP status code (200 if absent in response, −1 if malformed). */
    val statusCode: Int,
    /** RFC 3339 / ISO 8601 creation timestamp, or null. */
    val creation: Date?,
    /** Last-modified timestamp, or null. */
    val modified: Date?,
    /**
     * MIME content-type. Directories always report [HTTPD_UNIX_DIRECTORY_CONTENT_TYPE].
     * Falls back to [DEFAULT_CONTENT_TYPE] if not provided.
     */
    val contentType: String?,
    /** Content length in bytes, or [DEFAULT_CONTENT_LENGTH] (−1) if unknown. */
    val contentLength: Long,
    /** ETag fingerprint, or null. */
    val etag: String?,
    /** Content-Language header value, or null. */
    val contentLanguage: String?,
    /** Human-readable display name, or null. */
    val displayName: String?,
    /** List of DAV resource-type QNames (may include `collection`, `principal`, custom types). */
    val resourceTypes: List<QName>,
    /** Custom (non-standard DAV) properties with full namespace information. */
    val customPropsNS: Map<QName, String>
) {

    companion object {
        private val log = Logger.getLogger(DavResource::class.java.name)

        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
        const val DEFAULT_CONTENT_LENGTH = -1L
        const val HTTPD_UNIX_DIRECTORY_CONTENT_TYPE = "httpd/unix-directory"
        const val DEFAULT_STATUS_CODE = 200

        private const val SEPARATOR = "/"

        /**
         * Converts a [ParsedDavResponse] (from [com.hoggie.hoggiemap.webdav.xml.DavXmlParser])
         * into a [DavResource]. Logs a warning and returns null when the href is not a valid URI.
         */
        internal fun fromParsedResponse(parsed: ParsedDavResponse): DavResource? {
            val href = try {
                URI(parsed.href)
            } catch (e: Exception) {
                log.warning("Ignore resource with invalid URI '${parsed.href}': ${e.message}")
                return null
            }

            val status = parseStatusCode(parsed.status)

            // Merge properties from all propstat blocks (first non-null wins)
            var creation: Date? = null
            var modified: Date? = null
            var contentType: String? = null
            var contentLength = DEFAULT_CONTENT_LENGTH
            var etag: String? = null
            var contentLanguage: String? = null
            var displayName: String? = null
            var isCollection = false
            var isPrincipal = false
            val customProps = mutableMapOf<QName, String>()

            for (propStat in parsed.propStats) {
                val prop = propStat.prop
                if (creation == null && prop.creationDate != null)
                    creation = WebDavUtil.parseDate(prop.creationDate)
                if (modified == null && prop.lastModified != null)
                    modified = WebDavUtil.parseDate(prop.lastModified)
                if (contentLength == DEFAULT_CONTENT_LENGTH && prop.contentLength != null)
                    contentLength = prop.contentLength.toLongOrNull() ?: DEFAULT_CONTENT_LENGTH
                if (etag == null && prop.etag != null)
                    etag = prop.etag
                if (contentLanguage == null && prop.contentLanguage != null)
                    contentLanguage = prop.contentLanguage
                if (displayName == null && prop.displayName != null)
                    displayName = prop.displayName
                if (!isCollection) isCollection = prop.isCollection
                if (!isPrincipal) isPrincipal = prop.isPrincipal
                customProps.putAll(prop.customProps)

                if (contentType == null) {
                    contentType = when {
                        prop.isCollection  -> HTTPD_UNIX_DIRECTORY_CONTENT_TYPE
                        prop.contentType != null -> prop.contentType
                        else -> null
                    }
                }
            }

            val resourceTypes = buildList {
                if (isCollection)  add(WebDavUtil.createQNameWithDefaultNamespace("collection"))
                if (isPrincipal)   add(WebDavUtil.createQNameWithDefaultNamespace("principal"))
            }

            return DavResource(
                href = href,
                statusCode = status,
                creation = creation,
                modified = modified,
                contentType = contentType ?: DEFAULT_CONTENT_TYPE,
                contentLength = contentLength,
                etag = etag,
                contentLanguage = contentLanguage,
                displayName = displayName,
                resourceTypes = resourceTypes,
                customPropsNS = customProps
            )
        }

        private fun parseStatusCode(statusLine: String?): Int {
            if (statusLine.isNullOrEmpty()) return DEFAULT_STATUS_CODE
            return try {
                // "HTTP/1.1 200 OK" → take second token
                statusLine.split(" ").getOrNull(1)?.toInt() ?: DEFAULT_STATUS_CODE
            } catch (_: NumberFormatException) {
                log.warning("Failed to parse status line: $statusLine")
                -1
            }
        }
    }

    // Derived properties

    /** True when this resource is a collection (directory). */
    val isDirectory: Boolean
        get() = HTTPD_UNIX_DIRECTORY_CONTENT_TYPE == contentType

    /**
     * The last path component (decoded), e.g. `"file.txt"` or `"folder"`.
     * Returns null if the path cannot be parsed.
     */
    val name: String?
        get() {
            var path = href.path
            return try {
                if (path.endsWith(SEPARATOR)) path = path.dropLast(1)
                path.substringAfterLast('/')
            } catch (_: Exception) {
                log.warning("Failed to parse name from path $path")
                null
            }
        }

    /** Full path component of the URI. */
    val path: String get() = href.path

    /**
     * Custom properties without namespace information (local name -> value).
     * When multiple namespaces define the same local name, only one entry is kept.
     */
    val customProps: Map<String, String>
        get() = customPropsNS.entries.associate { (k, v) -> k.localPart to v }

    override fun toString(): String = path
}

