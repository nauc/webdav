package com.webdav.exception

import java.io.IOException

/**
 * Base exception for all WebDAV errors.
 * @param statusCode HTTP status code returned by the server (0 if not applicable)
 * @param message Human-readable description of the error
 */
open class WebDavException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null
) : IOException(formatMessage(statusCode, message), cause) {

    companion object {
        private fun formatMessage(code: Int, msg: String): String =
            if (code > 0) "$msg (HTTP $code)" else msg
    }
}

/**
 * Thrown when the server returns HTTP 401 or 403.
 * Indicates an authentication or authorisation failure.
 */
class AuthException(
    statusCode: Int,
    message: String = "Authentication failed"
) : WebDavException(statusCode, message)

/**
 * Thrown when the server returns HTTP 404.
 */
class ResourceNotFoundException(url: String) :
    WebDavException(404, "Resource not found: $url")

/**
 * Thrown when PROPFIND / PROPPATCH / other WebDAV methods fail.
 */
class PropfindException(
    statusCode: Int,
    message: String
) : WebDavException(statusCode, message)

/**
 * Thrown when the server response body is missing or cannot be parsed.
 */
class InvalidResponseException(
    message: String,
    cause: Throwable? = null
) : WebDavException(0, message, cause)

