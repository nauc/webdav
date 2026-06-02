package com.hoggie.hoggiemap.webdav.xml

import javax.xml.namespace.QName

// Internal parsed data models (not part of the public API)
// These represent the raw result of parsing a WebDAV XML response.

data class ParsedMultistatus(
    val responses: List<ParsedDavResponse>,
    val syncToken: String? = null
)

data class ParsedDavResponse(
    val href: String,
    val propStats: List<ParsedPropStat>,
    val status: String? = null
)

data class ParsedPropStat(
    val prop: ParsedProp,
    val status: String? = null
)

data class ParsedProp(
    val creationDate: String? = null,
    val lastModified: String? = null,
    val contentType: String? = null,
    val contentLength: String? = null,
    val etag: String? = null,
    val displayName: String? = null,
    val contentLanguage: String? = null,
    val isCollection: Boolean = false,
    val isPrincipal: Boolean = false,
    val customProps: Map<QName, String> = emptyMap(),
    // ACL
    val ownerHref: String? = null,
    val ownerUnauthenticated: Boolean = false,
    val groupHref: String? = null,
    val aces: List<ParsedAce> = emptyList(),
    // Quota
    val quotaAvailableBytes: String? = null,
    val quotaUsedBytes: String? = null,
    // Lock
    val lockTokenHref: String? = null,
    // Principal collection set
    val principalCollectionSetHrefs: List<String> = emptyList()
)

data class ParsedAce(
    val principalHref: String? = null,
    /** One of: "all", "authenticated", "unauthenticated", "self" */
    val principalKey: String? = null,
    val principalPropertyNs: String? = null,
    val principalPropertyLocal: String? = null,
    val granted: List<String> = emptyList(),
    val denied: List<String> = emptyList(),
    val inheritedHref: String? = null,
    val isProtected: Boolean = false
)

