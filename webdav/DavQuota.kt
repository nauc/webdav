package com.webdav

import com.webdav.xml.ParsedDavResponse

/**
 * Quota and size properties for a WebDAV resource (RFC 4331).
 */
class DavQuota private constructor(
    /**
     * Additional disk space (in bytes) that can be allocated to this resource
     * before further allocations are refused. [Long.MAX_VALUE] if unknown.
     */
    val quotaAvailableBytes: Long,
    /**
     * Space used (in bytes) by this resource and related resources.
     */
    val quotaUsedBytes: Long
) {
    companion object {
        internal fun fromParsedResponse(response: ParsedDavResponse): DavQuota {
            var available = Long.MAX_VALUE
            var used = 0L

            for (propStat in response.propStats) {
                val prop = propStat.prop
                prop.quotaAvailableBytes?.toLongOrNull()?.let { available = it }
                prop.quotaUsedBytes?.toLongOrNull()?.let { used = it }
            }
            return DavQuota(available, used)
        }
    }
}

