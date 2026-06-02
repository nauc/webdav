package com.webdav.impl

import com.webdav.util.WebDavUtil
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp [Authenticator] that responds to a 401 challenge with Basic credentials.
 * Prevents an infinite retry loop by checking whether the header was already set.
 */
internal class BasicAuthenticator(
    private val userName: String,
    private val password: String
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // If we already sent credentials and still got 401, give up.
        if (response.request.header("Authorization") != null) return null

        val credential = Credentials.basic(userName, password, WebDavUtil.standardUTF8())
        return response.request.newBuilder()
            .header("Authorization", credential)
            .build()
    }
}

