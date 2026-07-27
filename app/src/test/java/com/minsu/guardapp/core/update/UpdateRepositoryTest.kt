package com.minsu.guardapp.core.update

import com.minsu.guardapp.core.common.Clock
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The update check, end to end from the wire.
 *
 * The behaviour that matters most here is the *silence*: a check that cannot reach the server must
 * leave the app exactly as it found it. Attendance capture does not depend on this feature, and it
 * must never be able to take it down.
 */
class UpdateRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var preferences: FakeUpdatePreferences
    private var now = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        preferences = FakeUpdatePreferences()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(installedVersionCode: Int): DefaultUpdateRepository {
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        val source = UpdateManifestSource(
            client = client,
            manifestUrl = server.url("/app/guard-version.json").toString(),
            moshi = Moshi.Builder().build(),
        )
        return DefaultUpdateRepository(
            source = source,
            preferences = preferences,
            clock = Clock { now },
            installedVersionCode = installedVersionCode,
        )
    }

    private fun enqueueManifest(
        versionCode: Int = 3,
        versionName: String = "0.3.0",
        minSupported: Int? = null,
    ) {
        val floor = minSupported?.let { ""","min_supported_version_code":$it""" }.orEmpty()
        server.enqueue(
            MockResponse().setBody(
                """{"version_code":$versionCode,"version_name":"$versionName",
                   "apk_url":"https://minsu.edu.ph/app/guard.apk"$floor}"""
                    .trimIndent()
            )
        )
    }

    @Test
    fun `a higher versionCode is offered`() = runTest {
        enqueueManifest(versionCode = 3, versionName = "0.3.0")

        val outcome = repository(installedVersionCode = 2).check()

        val status = (outcome as CheckOutcome.Finished).status
        assertEquals("0.3.0", (status as UpdateStatus.Available).update.versionName)
    }

    @Test
    fun `the same versionCode is not an update`() = runTest {
        enqueueManifest(versionCode = 2)

        val outcome = repository(installedVersionCode = 2).check()

        assertEquals(UpdateStatus.UpToDate, (outcome as CheckOutcome.Finished).status)
    }

    @Test
    fun `a lower versionCode is not an update`() = runTest {
        // A rollback on the server must never persuade a newer build to downgrade itself.
        enqueueManifest(versionCode = 1)

        val outcome = repository(installedVersionCode = 2).check()

        assertEquals(UpdateStatus.UpToDate, (outcome as CheckOutcome.Finished).status)
    }

    @Test
    fun `versionName is never compared, only versionCode`() = runTest {
        // "0.10.0" sorts before "0.9.0" as a string. The codes are what decide.
        enqueueManifest(versionCode = 10, versionName = "0.10.0")

        val outcome = repository(installedVersionCode = 9).check()

        assertTrue((outcome as CheckOutcome.Finished).status is UpdateStatus.Available)
    }

    @Test
    fun `a floor above the installed build blocks it`() = runTest {
        enqueueManifest(versionCode = 5, minSupported = 4)

        val outcome = repository(installedVersionCode = 2).check()

        val status = (outcome as CheckOutcome.Finished).status
        assertEquals("0.3.0", (status as UpdateStatus.Required).update.versionName)
    }

    @Test
    fun `a dismissed version still blocks when it is below the floor`() = runTest {
        preferences.dismissed.value = 5
        enqueueManifest(versionCode = 5, minSupported = 4)

        val outcome = repository(installedVersionCode = 2).check()

        assertTrue((outcome as CheckOutcome.Finished).status is UpdateStatus.Required)
    }

    @Test
    fun `a missing floor never blocks`() = runTest {
        // Older manifests carry no floor at all; defaulting it to 0 has to be inert.
        enqueueManifest(versionCode = 3, minSupported = null)

        val outcome = repository(installedVersionCode = 2).check()

        assertTrue((outcome as CheckOutcome.Finished).status is UpdateStatus.Available)
    }

    @Test
    fun `dismissal suppresses that version but not a later one`() = runTest {
        preferences.dismissed.value = 3
        enqueueManifest(versionCode = 3)
        assertEquals(
            UpdateStatus.UpToDate,
            (repository(installedVersionCode = 2).check() as CheckOutcome.Finished).status,
        )

        now += TimeUnit.DAYS.toMillis(1)
        enqueueManifest(versionCode = 4, versionName = "0.4.0")
        val outcome = repository(installedVersionCode = 2).check()

        assertTrue((outcome as CheckOutcome.Finished).status is UpdateStatus.Available)
    }

    @Test
    fun `a forced check ignores a past dismissal`() = runTest {
        // Tapping "Check for updates" is a direct question and deserves a truthful answer.
        preferences.dismissed.value = 3
        enqueueManifest(versionCode = 3)

        val outcome = repository(installedVersionCode = 2).check(force = true)

        assertTrue((outcome as CheckOutcome.Finished).status is UpdateStatus.Available)
    }

    @Test
    fun `a server error leaves the status untouched`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val repository = repository(installedVersionCode = 2)

        val outcome = repository.check()

        assertEquals(CheckOutcome.Unreachable, outcome)
        assertEquals(UpdateStatus.Unknown, repository.status.value)
    }

    @Test
    fun `malformed json leaves the status untouched`() = runTest {
        server.enqueue(MockResponse().setBody("<html>proxy sign-in page</html>"))
        val repository = repository(installedVersionCode = 2)

        val outcome = repository.check()

        assertEquals(CheckOutcome.Unreachable, outcome)
        assertEquals(UpdateStatus.Unknown, repository.status.value)
    }

    @Test
    fun `a dropped connection leaves the status untouched`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val repository = repository(installedVersionCode = 2)

        val outcome = repository.check()

        assertEquals(CheckOutcome.Unreachable, outcome)
        assertEquals(UpdateStatus.Unknown, repository.status.value)
    }

    @Test
    fun `a failed check does not start the throttle window`() = runTest {
        // Otherwise one flaky check on launch would blind the app for six hours.
        server.enqueue(MockResponse().setResponseCode(500))
        val repository = repository(installedVersionCode = 2)
        assertEquals(CheckOutcome.Unreachable, repository.check())

        enqueueManifest(versionCode = 3)
        assertTrue((repository.check() as CheckOutcome.Finished).status is UpdateStatus.Available)
    }

    @Test
    fun `a second check inside the window is skipped`() = runTest {
        enqueueManifest(versionCode = 3)
        val repository = repository(installedVersionCode = 2)
        repository.check()

        now += TimeUnit.MINUTES.toMillis(5)

        assertEquals(CheckOutcome.Skipped, repository.check())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the window reopens once it has elapsed`() = runTest {
        enqueueManifest(versionCode = 3)
        val repository = repository(installedVersionCode = 2)
        repository.check()

        now += TimeUnit.HOURS.toMillis(7)
        enqueueManifest(versionCode = 4, versionName = "0.4.0")

        assertTrue(repository.check() is CheckOutcome.Finished)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a forced check ignores the window`() = runTest {
        enqueueManifest(versionCode = 3)
        val repository = repository(installedVersionCode = 2)
        repository.check()

        enqueueManifest(versionCode = 3)
        assertTrue(repository.check(force = true) is CheckOutcome.Finished)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a clock that moved backwards does not suppress checks`() = runTest {
        enqueueManifest(versionCode = 3)
        val repository = repository(installedVersionCode = 2)
        repository.check()

        // The guard corrected a wrong device date, or a timezone update landed.
        now -= TimeUnit.DAYS.toMillis(2)
        enqueueManifest(versionCode = 3)

        assertTrue(repository.check() is CheckOutcome.Finished)
    }

    @Test
    fun `unknown keys in the manifest are ignored`() = runTest {
        // The manifest is read by builds older than it. A key added later must not break them.
        server.enqueue(
            MockResponse().setBody(
                """{"version_code":3,"version_name":"0.3.0",
                   "apk_url":"https://minsu.edu.ph/app/guard.apk",
                   "rollout_percentage":25,"channel":"stable"}"""
            )
        )

        val outcome = repository(installedVersionCode = 2).check()

        assertTrue((outcome as CheckOutcome.Finished).status is UpdateStatus.Available)
    }

    @Test
    fun `optional fields are normalised`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"version_code":3,"version_name":"0.3.0",
                   "apk_url":"https://minsu.edu.ph/app/guard.apk",
                   "sha256":"  ABCDEF  ","release_notes":"   ","size_bytes":0}"""
            )
        )

        val status = (repository(installedVersionCode = 2).check() as CheckOutcome.Finished).status
        val update = (status as UpdateStatus.Available).update

        assertEquals("abcdef", update.sha256)
        // Blank notes and a zero size are absent, not empty — the UI must not render either.
        assertEquals(null, update.releaseNotes)
        assertEquals(null, update.sizeBytes)
    }

    @Test
    fun `dismissal only ever moves forward`() = runTest {
        preferences.dismiss(4)
        preferences.dismiss(2)

        assertEquals(4, preferences.dismissed.value)
    }
}

private class FakeUpdatePreferences : UpdatePreferences {
    val dismissed = MutableStateFlow(0)
    private var checkedAt = 0L

    override val dismissedVersionCode: Flow<Int> = dismissed

    override suspend fun dismiss(versionCode: Int) {
        dismissed.value = maxOf(dismissed.value, versionCode)
    }

    override suspend fun lastCheckedAt(): Long = checkedAt

    override suspend fun markChecked(atMillis: Long) {
        checkedAt = atMillis
    }
}
