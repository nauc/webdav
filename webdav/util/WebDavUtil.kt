package com.webdav.util

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.namespace.QName

/**
 * Utility functions for WebDAV operations.
 * Provides date parsing, QName helpers, and charset utilities.
 */
object WebDavUtil {

    /** Default namespace prefix for custom user properties */
    const val CUSTOM_NAMESPACE_PREFIX = "s"

    /** Default namespace URI for custom user properties */
    const val CUSTOM_NAMESPACE_URI = "SAR:"

    /** Default namespace prefix for DAV properties */
    const val DEFAULT_NAMESPACE_PREFIX = "D"

    /** Default namespace URI for DAV properties */
    const val DEFAULT_NAMESPACE_URI = "DAV:"

    private val SUPPORTED_DATE_FORMATS = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "EEE MMM dd HH:mm:ss zzz yyyy",
        "EEEEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMMM d HH:mm:ss yyyy"
    )

    private val DATETIME_FORMATS: List<ThreadLocal<SimpleDateFormat>> =
        Collections.unmodifiableList(
            SUPPORTED_DATE_FORMATS.map { formatStr ->
                ThreadLocal.withInitial<SimpleDateFormat> {
                    SimpleDateFormat(formatStr, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                }
            }
        )

    /**
     * Tries all known date formats and returns the parsed [Date], or null on failure.
     */
    fun parseDate(value: String?): Date? {
        if (value == null) return null
        for (tlFormat in DATETIME_FORMATS) {
            try {
                return tlFormat.get()?.parse(value)
            } catch (_: ParseException) {
                // continue to next format
            }
        }
        return null
    }

    /** Returns UTF-8 [Charset] (minSdk ≥ 26 always has StandardCharsets). */
    fun standardUTF8(): Charset = StandardCharsets.UTF_8

    // QName helpers

    fun createQNameWithCustomNamespace(key: String): QName =
        QName(CUSTOM_NAMESPACE_URI, key, CUSTOM_NAMESPACE_PREFIX)

    fun createQNameWithDefaultNamespace(key: String): QName =
        QName(DEFAULT_NAMESPACE_URI, key, DEFAULT_NAMESPACE_PREFIX)

    fun toQName(setProps: Map<String, String>?): Map<QName, String> {
        if (setProps.isNullOrEmpty()) return emptyMap()
        return setProps.entries.associate { (k, v) ->
            createQNameWithCustomNamespace(k) to v
        }
    }

    fun toQName(removeProps: List<String>?): List<QName> {
        if (removeProps.isNullOrEmpty()) return emptyList()
        return removeProps.map { createQNameWithCustomNamespace(it) }
    }
}

