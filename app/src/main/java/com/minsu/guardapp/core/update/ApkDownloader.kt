package com.minsu.guardapp.core.update

import com.minsu.guardapp.core.di.UpdateCacheDir
import com.minsu.guardapp.core.di.UpdateClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val bytes: Long, val total: Long?) : DownloadState {
        /** 0f..1f, or null when the server did not say how big the file is. */
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) }
    }
    data class Ready(val apk: File, val versionCode: Int) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/**
 * Downloads the APK.
 *
 * A singleton owning both the progress and the coroutine that produces it, rather than work a
 * ViewModel awaits. Two reasons, and the second is the important one: the guard can leave the
 * update sheet for the Account screen and back, and both places must show the same one download
 * rather than starting a second — and a ViewModel-scoped job would be cancelled the moment they
 * navigated away, silently discarding tens of megabytes already paid for on a campus connection.
 */
@Singleton
class ApkDownloader @Inject constructor(
    // The directory rather than a Context: everything below is ordinary file and socket work, and
    // taking it this way is what lets it be tested off a device.
    @UpdateCacheDir private val directory: File,
    @UpdateClient private val client: OkHttpClient,
) {
    private val state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val progress: StateFlow<DownloadState> = state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Starts a download, or does nothing if one is already running. */
    fun start(update: AppUpdate) {
        if (job?.isActive == true) return
        // Published here, synchronously, rather than left to the coroutine. A caller that starts
        // a download and then waits for the next terminal state would otherwise be handed the
        // *previous* attempt's Failed or Ready before this one has begun.
        state.value = DownloadState.Downloading(0, update.sizeBytes)
        job = scope.launch { download(update) }
    }

    fun cancel() {
        job?.cancel()
        job = null
        state.value = DownloadState.Idle
    }

    fun reset() {
        if (job?.isActive == true) return
        state.value = DownloadState.Idle
    }

    /**
     * Fetches [update]'s APK into the cache and verifies it.
     *
     * Cancellation-safe: a partial file is deleted rather than left to be mistaken for a
     * finished download on the next attempt.
     */
    suspend fun download(update: AppUpdate): DownloadState = withContext(Dispatchers.IO) {
        // Anything from an older version is dead weight — an APK is tens of megabytes and this
        // is the cache, which the guard cannot clean out themselves.
        pruneExcept(update.versionCode)

        val target = File(directory, "guard-${update.versionCode}.apk")

        // Already here and still intact: skip the download entirely. This is the common case
        // when a guard declines Android's install prompt and taps Update again.
        if (target.isFile && verifies(target, update.sha256)) {
            return@withContext DownloadState.Ready(target, update.versionCode).also { state.value = it }
        }
        target.delete()

        state.value = DownloadState.Downloading(0, update.sizeBytes)

        val result = runCatching {
            directory.mkdirs()
            val request = Request.Builder().url(update.apkUrl).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        var lastPublished = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            copied += read
                            // Throttled: publishing every 8KB chunk would recompose the progress
                            // bar thousands of times a second to no visible benefit.
                            if (copied - lastPublished >= PROGRESS_STEP_BYTES) {
                                lastPublished = copied
                                state.value = DownloadState.Downloading(copied, total)
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (!verifies(target, update.sha256)) {
                throw IOException("The downloaded file did not match its checksum")
            }
            target
        }

        result.fold(
            onSuccess = { DownloadState.Ready(it, update.versionCode) },
            onFailure = { error ->
                // A half-written or corrupt APK must never survive to be handed to the installer.
                target.delete()
                // runCatching swallows CancellationException along with everything else. Letting
                // that through would report a deliberate cancel as a failure and, worse, leave
                // this coroutine looking like it completed normally to its parent.
                if (error is CancellationException) throw error
                DownloadState.Failed(
                    error.message?.takeIf { it.isNotBlank() } ?: "The download did not finish"
                )
            },
        ).also { state.value = it }
    }

    /**
     * True when [expected] is absent, or when the file hashes to it.
     *
     * An absent checksum is accepted rather than treated as a failure — older manifests may not
     * carry one — but a present one that does not match is fatal. A truncated download and a
     * tampered one look identical from here, and neither should reach the package installer.
     */
    private fun verifies(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return file.length() > 0
        return runCatching { sha256(file) }.getOrNull()?.equals(expected, ignoreCase = true) == true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pruneExcept(versionCode: Int) {
        val keep = "guard-$versionCode.apk"
        directory.listFiles()?.forEach { if (it.name != keep) it.delete() }
    }

    private companion object {
        const val PROGRESS_STEP_BYTES = 256L * 1024
    }
}
