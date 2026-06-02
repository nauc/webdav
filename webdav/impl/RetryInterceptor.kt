package com.webdav.impl

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.logging.Logger

/**
 * OkHttp [Interceptor] that retries requests on transient network failures
 * and server-side 503/429 responses using exponential back-off.
 *
 * **One-shot bodies (InputStream-based PUT) are never retried** because the
 * source stream has already been consumed and cannot be replayed.
 *
 * For **HTTP 429 (Too Many Requests)** the interceptor reads the `Retry-After`
 * response header and waits exactly that long before retrying (capped at
 * [maxRetryAfterMs]). If the server does not send `Retry-After`, the request
 * is **not** retried - blindly hammering a rate-limited endpoint would only
 * extend the blackout window.
 *
 * For **HTTP 503 (Service Unavailable)** the standard exponential back-off is used.
 *
 * Retry schedule for 503 (default, [maxRetries] = 3):
 * | Attempt | Delay    |
 * |---------|----------|
 * | 1st     | 1 000 ms |
 * | 2nd     | 2 000 ms |
 * | 3rd     | 4 000 ms |
 *
 * @param maxRetries       Maximum number of additional attempts after the first failure.
 * @param initialDelayMs   Delay before the first retry (doubles on each subsequent retry).
 * @param retryOnCodes     HTTP status codes that trigger a retry (default: 503, 429).
 * @param maxRetryAfterMs  Maximum delay extracted from `Retry-After` header (default: 60 s).
 *                         Prevents indefinitely blocking the calling coroutine.
 */
internal class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1_000L,
    private val retryOnCodes: Set<Int> = setOf(503, 429),
    private val maxRetryAfterMs: Long = 60_000L
) : Interceptor {

    private val log = Logger.getLogger(RetryInterceptor::class.java.name)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // One-shot bodies (InputStream PUT) cannot be replayed - skip retry.
        val canRetry = request.body?.isOneShot() != true

        var attempt = 0
        var lastException: IOException? = null

        while (true) {
            try {
                val response = chain.proceed(request)

                if (response.code in retryOnCodes && canRetry && attempt < maxRetries) {
                    val delay = computeDelay(response, attempt)

                    if (delay == null) {
                        // 429 without Retry-After - do not retry, return response as-is
                        log.warning(
                            "HTTP ${response.code} - no Retry-After header, " +
                            "will not retry to avoid worsening rate-limit"
                        )
                        return response
                    }

                    response.close()
                    attempt++
                    log.warning(
                        "HTTP ${response.code} - retrying in ${delay}ms " +
                        "(attempt $attempt/$maxRetries)"
                    )
                    Thread.sleep(delay)
                    continue
                }

                return response
            } catch (e: IOException) {
                if (!canRetry || attempt >= maxRetries) throw e
                lastException = e
                val delay = backoffMs(attempt++)
                log.warning(
                    "Network error: ${e.message} - retrying in ${delay}ms " +
                    "(attempt $attempt/$maxRetries)"
                )
                Thread.sleep(delay)
            }
        }

        @Suppress("UNREACHABLE_CODE")
        throw lastException ?: IOException("Request failed after $maxRetries retries")
    }

    /**
     * Returns the delay in milliseconds before the next retry attempt, or `null`
     * if the request should **not** be retried.
     *
     * For 429: use `Retry-After` (seconds) from the response header, capped at
     * [maxRetryAfterMs]. Returns `null` if the header is absent.
     * For other codes (e.g. 503): use standard exponential back-off.
     */
    private fun computeDelay(response: Response, attempt: Int): Long? {
        if (response.code == 429) {
            val retryAfterSec = response.header("Retry-After")?.trim()?.toLongOrNull()
                ?: return null  // No Retry-After - do not retry
            val delayMs = (retryAfterSec * 1_000L).coerceAtMost(maxRetryAfterMs)
            return delayMs
        }
        return backoffMs(attempt)
    }

    private fun backoffMs(attempt: Int): Long =
        initialDelayMs * (1L shl attempt.coerceAtMost(5))
}

