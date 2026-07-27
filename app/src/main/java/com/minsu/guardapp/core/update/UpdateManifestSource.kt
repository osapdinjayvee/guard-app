package com.minsu.guardapp.core.update

import com.minsu.guardapp.core.di.UpdateClient
import com.minsu.guardapp.core.di.UpdateManifestUrl
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Fetches and parses the update manifest. One request, one JSON document, no Retrofit. */
@Singleton
class UpdateManifestSource @Inject constructor(
    @UpdateClient private val client: OkHttpClient,
    @UpdateManifestUrl private val manifestUrl: String,
    moshi: Moshi,
) {
    private val adapter = moshi.adapter(UpdateManifestDto::class.java)

    /**
     * Reads the manifest.
     *
     * Fails as a [Result] rather than throwing: an update check is the least important thing the
     * app does, and it must never be able to take anything else down with it.
     */
    suspend fun fetch(): Result<UpdateManifestDto> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(manifestUrl)
                // The manifest is small and its whole job is to be current. A cached copy —
                // OkHttp's, a proxy's, or the campus network's — means a guard is told they are
                // up to date by a file written before the release existed.
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Update manifest returned HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                adapter.fromJson(body)
                    ?: throw IOException("Update manifest was empty or not an object")
            }
        }
    }
}
