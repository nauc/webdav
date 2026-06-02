package com.webdav

import javax.xml.namespace.QName

/**
 * Identifies a principal (user, group, or special keyword) in a WebDAV ACL.
 */
class DavPrincipal private constructor(
    val principalType: PrincipalType?,
    val value: String?,
    val property: QName?,
    val displayName: String?
) {
    enum class PrincipalType {
        /** Principal is a URL href to an existing principal */
        HREF,
        /** Principal is one of: "all", "authenticated", "unauthenticated", "self" */
        KEY,
        /** Principal is identified by a DAV property (e.g. DAV:owner) */
        PROPERTY
    }

    companion object {
        const val KEY_SELF = "self"
        const val KEY_UNAUTHENTICATED = "unauthenticated"
        const val KEY_AUTHENTICATED = "authenticated"
        const val KEY_ALL = "all"

        fun href(value: String, displayName: String? = null) =
            DavPrincipal(PrincipalType.HREF, value, null, displayName)

        fun key(key: String, displayName: String? = null): DavPrincipal {
            require(key in listOf(KEY_SELF, KEY_UNAUTHENTICATED, KEY_AUTHENTICATED, KEY_ALL)) {
                "Unknown principal key: $key"
            }
            return DavPrincipal(PrincipalType.KEY, key, null, displayName)
        }

        fun property(qname: QName, displayName: String? = null) =
            DavPrincipal(PrincipalType.PROPERTY, null, qname, displayName)

        /**
         * Reconstructs a [DavPrincipal] from parsed XML data.
         */
        internal fun fromParsed(
            href: String?,
            key: String?,
            propertyNs: String?,
            propertyLocal: String?,
            displayName: String? = null
        ): DavPrincipal {
            return when {
                href != null -> DavPrincipal(PrincipalType.HREF, href, null, displayName)
                key != null  -> DavPrincipal(PrincipalType.KEY, key, null, displayName)
                propertyNs != null && propertyLocal != null ->
                    DavPrincipal(PrincipalType.PROPERTY, null, QName(propertyNs, propertyLocal), displayName)
                else         -> DavPrincipal(null, null, null, displayName)
            }
        }
    }

    override fun toString() =
        "[principalType=$principalType, value=$value, property=$property, displayName=$displayName]"
}

