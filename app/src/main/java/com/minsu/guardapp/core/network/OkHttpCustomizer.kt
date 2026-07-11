package com.minsu.guardapp.core.network

import okhttp3.OkHttpClient

/**
 * A hook for build-variant-specific tweaks to the HTTP client.
 *
 * The set is empty in release, so a release build gets a stock client: stock DNS, stock trust
 * store, stock hostname verification. Anything a local development environment needs in order to
 * be reachable — private DNS, a self-signed certificate — is contributed by the debug source set
 * and therefore cannot exist in a shipped APK.
 */
fun interface OkHttpCustomizer {
    fun customize(builder: OkHttpClient.Builder)
}
