package com.webdav.impl

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp [Interceptor] that attaches a Yandex OAuth2 token to every request
 * using the **`OAuth`** scheme required by Yandex Disk WebDAV.
 *
 * Yandex Disk does **not** accept the standard `Bearer` scheme — requests
 * authenticated with `Authorization: Bearer ...` receive HTTP 401.
 * The correct format is `Authorization: OAuth <token>`.
 *
 * ### Usage
 * ```kotlin
 * val dav = OkHttpWebDavInterface()
 * dav.setYandexOAuthToken("y0_AgA...your-token...")
 *
 * val files = dav.list("https://webdav.yandex.ru/")
 * ```
 *
 * Obtain a token at https://oauth.yandex.ru - the required scope is
 * `cloud_api:disk.read` (read) and/or `cloud_api:disk.write` (write).
 */
internal class YandexOAuthInterceptor(private val token: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "OAuth $token")
            .build()
        return chain.proceed(request)
    }
}

