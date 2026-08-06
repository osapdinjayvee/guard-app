package com.minsu.guardapp.core.update

import com.minsu.guardapp.core.di.UpdateClient
import com.minsu.guardapp.core.di.UpdateManifestUrl
import com.minsu.guardapp.core.di.UpdateVersionsUrl
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The versions endpoint wraps its list in `data`, like every other authenticated response. */
@JsonClass(generateAdapter = true)
data class UpdateManifestListDto(val data: List<UpdateManifestDto> = emptyList())

/**
 * Finds out what builds exist.
 *
 * Two sources, tried in order. The office's published list says what is available; the static
 * manifest beside the APK says only what is newest. The list is asked first and the file is the
 * fallback — not a legacy path to be deleted, but the thing that keeps working when the server is
 * down, being restarted, or has simply not had the release feature deployed to it yet.
 *
 * An update check is the least important thing this app does and must never be able to take
 * anything else down with it, so every failure here is a [Result] rather than a throw.
 */
@Singleton
class UpdateManifestSource @Inject constructor(
    @UpdateClient private val client: OkHttpClient,
    @UpdateManifestUrl private val manifestUrl: String,
    @UpdateVersionsUrl private val versionsUrl: String,
    moshi: Moshi,
) {
    private val single = moshi.adapter(UpdateManifestDto::class.java)
    private val list = moshi.adapter(UpdateManifestListDto::class.java)

    /**
     * Every build the office has published, newest first.
     *
     * A single-item list when only the static manifest could be reached, so callers never have to
     * care which source answered.
     */
    suspend fun fetch(): Result<List<UpdateManifestDto>> = withContext(Dispatchers.IO) {
        fetchPublished().recoverCatching { listOfNotNull(fetchManifest().getOrThrow()) }
            .map { versions -> versions.sortedByDescending { it.versionCode } }
    }

    private fun fetchPublished(): Result<List<UpdateManifestDto>> = runCatching {
        val body = get(versionsUrl)
        val parsed = list.fromJson(body) ?: throw IOException("Version list was not an object")

        // An empty list is an answer — nothing published yet — but not one worth failing over to
        // the static manifest for. The file would describe a build the office has not released.
        parsed.data
    }

    private fun fetchManifest(): Result<UpdateManifestDto> = runCatching {
        single.fromJson(get(manifestUrl))
            ?: throw IOException("Update manifest was empty or not an object")
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            // Small, and its whole job is to be current. A cached copy — OkHttp's, a proxy's, or
            // the campus network's — means a guard is told they are up to date by something
            // written before the release existed.
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            return response.body?.string().orEmpty()
        }
    }
}
