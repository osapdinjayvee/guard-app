package com.minsu.guardapp.core.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gets the photograph for an attendance record onto the screen, wherever it happens to live.
 *
 * A record's `selfiePath` is one of two things, and which one depends on how the record arrived.
 * Captured on this handset, it is an absolute path to a file in the app's own storage. Downloaded
 * from the server — a reinstall, a replacement phone — it is the URL the server serves the image
 * from, because the history download deliberately brings back records without their photographs.
 *
 * Fetching them all would be the obvious alternative and the wrong one: a thousand records is a few
 * hundred megabytes of a guard's own data, spent up front, for images almost none of which anybody
 * will ever open. So they are fetched one at a time, when a guard actually opens the record, and
 * kept afterwards so the second look works with no signal at all.
 */
@Singleton
class SelfieStore @Inject constructor(
    @ApplicationContext private val context: Context,
    /**
     * The API's client, deliberately.
     *
     * The image sits on the same host as the API, so its Sanctum token going along is neither a
     * leak nor wasted: `/storage` is public today, and the day somebody decides a guard's face
     * should not be world-readable, this keeps working instead of starting to 403.
     */
    private val client: OkHttpClient,
) {
    private val directory: File get() = File(context.cacheDir, "selfies")

    /**
     * The image for [recordId], or null if there is none to be had.
     *
     * Null covers three cases that look identical to the screen and are worth naming: the record
     * never had a photo, the file it pointed at is gone and there is no URL to replace it, or the
     * download failed. Only the last is worth retrying, and [isRemote] is how the caller tells.
     */
    suspend fun resolve(recordId: String, pathOrUrl: String): File? = withContext(Dispatchers.IO) {
        if (pathOrUrl.isBlank()) return@withContext null

        // Captured here. The original file, untouched — the one with the metadata burned in.
        if (!isRemote(pathOrUrl)) {
            return@withContext File(pathOrUrl).takeIf { it.isFile && it.length() > 0 }
        }

        val cached = File(directory, "$recordId.jpg")
        if (cached.isFile && cached.length() > 0) return@withContext cached

        download(pathOrUrl, cached)
    }

    /** Whether a path points at the server rather than at this phone. */
    fun isRemote(pathOrUrl: String): Boolean =
        pathOrUrl.startsWith("http://", ignoreCase = true) ||
            pathOrUrl.startsWith("https://", ignoreCase = true)

    private fun download(url: String, target: File): File? {
        // Written to a neighbour and renamed, so a download cut off halfway cannot be mistaken for
        // a complete one on the next attempt and shown as a truncated grey photograph.
        val partial = File(target.parentFile, "${target.name}.part")

        return runCatching {
            directory.mkdirs()
            partial.delete()

            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")

                body.byteStream().use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
            }

            if (partial.length() == 0L) throw IOException("Empty image")
            if (!partial.renameTo(target)) throw IOException("Could not store the image")

            trim()
            target
        }.getOrElse {
            partial.delete()
            null
        }
    }

    /**
     * Keeps the kept photographs to a bounded size, oldest first.
     *
     * Without this the directory only ever grows: a guard who reviews a few records a week is
     * carrying every one of them a year later, on a phone that also has to hold the queue of
     * captures that have not uploaded yet. Those are the bytes that actually matter — an
     * un-uploaded attendance exists nowhere else, while every photo in here is a copy of one the
     * server already has and can serve again.
     *
     * Least-recently-used rather than oldest-captured, because the record a guard keeps opening is
     * the one worth not re-fetching.
     */
    private fun trim() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return

        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_CACHE_BYTES) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private companion object {
        /**
         * 40 MB — roughly a couple of hundred selfies at the quality the server is sent.
         *
         * In `cacheDir`, so Android may reclaim the lot under storage pressure and the guard can
         * clear it from Settings. Neither is a loss: every file here is re-fetchable.
         */
        const val MAX_CACHE_BYTES = 40L * 1024 * 1024
    }
}
