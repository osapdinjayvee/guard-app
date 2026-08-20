package com.minsu.guardapp.core.media

import android.content.Context
import com.minsu.guardapp.BuildConfig
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
 * Published PDFs, kept on the phone once fetched.
 *
 * Unlike the selfies, these are worth holding onto: a guard reads the handbook at a post, which is
 * where the signal is worst, and it is one document rather than a thousand. Fetched once and then
 * available with nothing but the phone.
 */
@Singleton
class DocumentCache @Inject constructor(
    @ApplicationContext private val context: Context,
    /** The API's client. Same host as the API, so the token rides along harmlessly and usefully. */
    private val client: OkHttpClient,
) {
    private val directory: File get() = File(context.cacheDir, "documents")

    /** The cached copy of [identifier], if one has already been fetched. */
    fun cached(identifier: String): File? =
        File(directory, "$identifier.pdf").takeIf { it.isFile && it.length() > 0 }

    /**
     * The document, from cache or from the server.
     *
     * Returns the cached copy without asking the network first, which is what makes this work at a
     * perimeter post. A guard wanting a newer edition can clear it; the office replacing the file
     * gives it a new URL, and that is the case [fresh] exists for.
     */
    suspend fun fetch(identifier: String, url: String): File? = withContext(Dispatchers.IO) {
        cached(identifier)?.let { return@withContext it }
        download(identifier, url)
    }

    /** Fetches again even if a copy is held, for when the office has published a new edition. */
    suspend fun fresh(identifier: String, url: String): File? = withContext(Dispatchers.IO) {
        download(identifier, url)
    }

    /**
     * A document the API generates on demand, at [path] relative to the API root.
     *
     * The DTR and the guard report are built per request from attendance the office may still be
     * correcting, so unlike the handbook there is no edition to hold on to: this always asks the
     * server, and the cached copy is what the caller falls back to when the ask fails. Goes through
     * the API's own client, so the guard's token is attached — these endpoints are not public.
     */
    suspend fun fetchFromApi(identifier: String, path: String): File? = withContext(Dispatchers.IO) {
        download(identifier, BuildConfig.API_BASE_URL.trimEnd('/') + "/" + path.trimStart('/'))
    }

    private fun download(identifier: String, url: String): File? {
        val target = File(directory, "$identifier.pdf")
        // Written beside and renamed, so a download cut off halfway is never mistaken next time for
        // a complete file and handed to a PDF renderer that will refuse to open it.
        val partial = File(directory, "$identifier.pdf.part")

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

            if (partial.length() == 0L) throw IOException("Empty document")
            target.delete()
            if (!partial.renameTo(target)) throw IOException("Could not store the document")

            target
        }.getOrElse {
            partial.delete()
            null
        }
    }
}
