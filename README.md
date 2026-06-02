# webdav
 WebDAV Client Module
### WebDAV Client Module (`com.webdav`)

This module is a **modern Kotlin fork of the [sardine](https://github.com/lookfirst/sardine/tree/master) library**, specifically redesigned for the Android ecosystem. It retains the robust logic of the original WebDAV protocol implementation while upgrading the entire stack to modern development standards.

#### Key Improvements Over Original Sardine
*   **Kotlin First**: Completely rewritten in Kotlin, providing native null-safety and a more idiomatic API.
*   **Coroutines Support**: All blocking network calls are replaced with asynchronous `suspend` functions. Operations are dispatched to `Dispatchers.IO` and are safe to call from any scope.
*   **Modern Auth**: Enhanced OAuth2 support with automatic token refresh mechanisms, optimized for cloud storage providers.

#### Features
*   **Full CRUD Lifecycle**: Support for Upload (`PUT`), Download (`GET`), Delete (`DELETE`), Copy, and Move operations.
*   **Metadata & Search**: Comprehensive support for `PROPFIND`, `PROPPATCH` (custom properties), and `SEARCH`.
*   **Access Control**: Manage Access Control Lists (`DavAcl`, `DavAce`) and check storage quotas.
*   **Locking Mechanism**: Support for exclusive write locks to prevent data overwrite conflicts.
*   **Memory-Efficient Streaming**: Uses streaming for both uploads and downloads, which is critical for handling large files on mobile devices without memory overflows.

#### Usage Example
```kotlin
// Initialize the interface with an OkHttpClient
val webDav: WebDavInterface = OkHttpWebDavInterface(okHttpClient)

// Configure OAuth2 (e.g., for Yandex.Disk or Nextcloud)
webDav.setOAuth2Credentials(myTokenProvider)

// Use within a Coroutine scope
lifecycleScope.launch {
    try {
        val resources = webDav.list("https://webdav.yandex.ru/navigation/tracks/")

        resources.forEach { res ->
            println(
                "Found: ${res.displayName}, " +
                "Size: ${res.contentLength} bytes"
            )
        }
    } catch (e: WebDavException) {
        // Handle protocol or network errors
        e.printStackTrace()
    }
}
```
