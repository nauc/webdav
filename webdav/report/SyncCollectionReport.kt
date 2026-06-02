package com.webdav.report

import com.webdav.DavResource
import com.webdav.xml.DavXmlBuilder
import com.webdav.xml.ParsedMultistatus
import java.util.logging.Logger
import javax.xml.namespace.QName

/**
 * WebDAV `sync-collection` REPORT (RFC 6578).
 *
 * Sends a `<D:sync-collection>` request and returns a [Result] containing
 * the changed resources and the new sync token.
 */
class SyncCollectionReport(
    private val syncToken: String,
    private val syncLevel: SyncLevel,
    private val properties: Set<QName>,
    private val limit: Int? = null
) : WebDavReport<SyncCollectionReport.Result>() {

    enum class SyncLevel(val value: String) {
        LEVEL_1("1"),
        LEVEL_INFINITY("infinite");

        override fun toString() = value
    }

    override fun toXml(): String = DavXmlBuilder.syncCollection(
        syncToken = syncToken,
        syncLevel = syncLevel.value,
        props = properties,
        limit = limit
    )

    override fun fromMultistatus(multistatus: ParsedMultistatus): Result {
        val resources = mutableListOf<DavResource>()
        for (response in multistatus.responses) {
            val resource = DavResource.fromParsedResponse(response)
            if (resource != null) {
                resources.add(resource)
            } else {
                log.warning("Ignoring resource with invalid URI: ${response.href}")
            }
        }
        return Result(resources, multistatus.syncToken)
    }

    data class Result(
        val resources: List<DavResource>,
        val syncToken: String?
    )

    companion object {
        private val log = Logger.getLogger(SyncCollectionReport::class.java.name)
    }
}

