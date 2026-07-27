package com.minsu.guardapp.core.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The download, and the checksum that guards it.
 *
 * A truncated download and a tampered one are indistinguishable from here, and neither may reach
 * the package installer — so the interesting assertions are all about what is *left behind* when
 * something goes wrong.
 */
class ApkDownloaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var directory: File

    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val payloadSha = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        directory = temporaryFolder.newFolder("updates")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun downloader() = ApkDownloader(
        directory = directory,
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
    )

    private fun update(sha256: String? = payloadSha, versionCode: Int = 3) = AppUpdate(
        versionCode = versionCode,
        versionName = "0.$versionCode.0",
        apkUrl = server.url("/app/guard.apk").toString(),
        sha256 = sha256,
        releaseNotes = null,
        sizeBytes = payload.size.toLong(),
    )

    private fun enqueueApk(body: ByteArray = payload) {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
    }

    @Test
    fun `a matching checksum yields the file`() = runTest {
        enqueueApk()

        val state = downloader().download(update())

        val ready = state as DownloadState.Ready
        assertEquals(3, ready.versionCode)
        assertEquals(payload.size.toLong(), ready.apk.length())
    }

    @Test
    fun `a mismatched checksum fails and leaves nothing behind`() = runTest {
        enqueueApk()

        val state = downloader().download(update(sha256 = "0".repeat(64)))

        assertTrue(state is DownloadState.Failed)
        assertTrue("a rejected APK must not survive", directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a truncated download fails and leaves nothing behind`() = runTest {
        // Content-Length promises the full body; the connection dies partway through.
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(payload))
                .setBodyDelay(0, TimeUnit.MILLISECONDS)
                .setHeader("Content-Length", payload.size * 2)
        )

        val state = downloader().download(update())

        assertTrue(state is DownloadState.Failed)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an http error fails and leaves nothing behind`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val state = downloader().download(update())

        assertTrue((state as DownloadState.Failed).reason.contains("404"))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an absent checksum is accepted`() = runTest {
        // Older manifests carry no sha256. Weaker, but not a failure.
        enqueueApk()

        val state = downloader().download(update(sha256 = null))

        assertTrue(state is DownloadState.Ready)
    }

    @Test
    fun `an already downloaded apk is not fetched twice`() = runTest {
        enqueueApk()
        val downloader = downloader()
        assertTrue(downloader.download(update()) is DownloadState.Ready)

        // No second response is enqueued: a second request would hang and fail the test.
        assertTrue(downloader.download(update()) is DownloadState.Ready)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a cached file that no longer matches its checksum is refetched`() = runTest {
        val stale = File(directory, "guard-3.apk").apply { writeBytes(ByteArray(32)) }
        enqueueApk()

        val state = downloader().download(update())

        assertTrue(state is DownloadState.Ready)
        assertEquals(payload.size.toLong(), stale.length())
    }

    @Test
    fun `downloading a new version clears the old one`() = runTest {
        val previous = File(directory, "guard-2.apk").apply { writeBytes(ByteArray(1024)) }
        enqueueApk()

        downloader().download(update(versionCode = 3))

        assertFalse("a superseded APK is dead weight in the cache", previous.exists())
    }
}
