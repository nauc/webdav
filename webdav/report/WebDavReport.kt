package com.webdav.report

import com.webdav.xml.ParsedMultistatus

/**
 * Base class for WebDAV REPORT requests.
 *
 * Subclasses provide the XML request body via [toXml] and convert the
 * parsed server response into a typed result via [fromMultistatus].
 *
 * @param T The result type produced after processing the multistatus response.
 */
abstract class WebDavReport<T> {
    /** Returns the XML body to send with the REPORT request. */
    abstract fun toXml(): String

    /** Converts the parsed multistatus response into a typed result. */
    abstract fun fromMultistatus(multistatus: ParsedMultistatus): T
}

