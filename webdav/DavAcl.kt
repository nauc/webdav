package com.webdav

import com.webdav.xml.ParsedDavResponse

/**
 * Access Control List for a WebDAV resource.
 */
class DavAcl private constructor(
    /** URL of the resource's owner principal, or "unauthenticated" / null. */
    val owner: String?,
    /** URL of the resource's group principal, or null. */
    val group: String?,
    /** The list of access control entries for this resource. */
    val aces: List<DavAce>
) {
    companion object {
        internal fun fromParsedResponse(response: ParsedDavResponse): DavAcl {
            var owner: String? = null
            var group: String? = null
            val aces = mutableListOf<DavAce>()

            for (propStat in response.propStats) {
                val prop = propStat.prop
                if (prop.ownerUnauthenticated) {
                    owner = "unauthenticated"
                } else if (prop.ownerHref != null) {
                    owner = prop.ownerHref
                }
                if (prop.groupHref != null) {
                    group = prop.groupHref
                }
                for (parsedAce in prop.aces) {
                    aces.add(DavAce.fromParsed(parsedAce))
                }
            }
            return DavAcl(owner, group, aces)
        }
    }
}

