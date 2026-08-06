package com.minsu.guardapp.core.update

import com.minsu.guardapp.core.common.Clock
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

    /**
     * The server's answer for the published list, or null to have it fail.
     *
     * Null by default so the existing tests exercise the fallback: the office's endpoint is
     * unreachable — not deployed, being restarted — and the static manifest answers instead. That
     * is the path every one of these was written against, and it must keep working.
     */
    private var versionList: String? = null

    @Before
    fun setUp() {
        preferences = FakeUpdatePreferences()
        server = MockWebServer().apply {
            // Routed by path rather than a queue. The source now asks two different addresses, and
            // a queue would hand the manifest's response to whichever request arrived first.
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path?.startsWith("/api/app/versions") == true ->
                            versionList?.let { MockResponse().setBody(it) }
                                ?: MockResponse().setResponseCode(404)

                        else -> {
                            manifestRequests++
                            manifestResponses.poll() ?: MockResponse().setResponseCode(404)
                        }
                    }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        versionList = null
        manifestResponses.clear()
        manifestRequests = 0
    }

    private val manifestResponses = java.util.concurrent.ConcurrentLinkedQueue<MockResponse>()

    /**
     * Manifest fetches only.
     *
     * `server.requestCount` counts the versions probe too, so a single check now looks like two
     * requests — the throttle tests would pass for the wrong reason.
     */
    @Volatile
    private var manifestRequests = 0

    private fun repository(installedVersionCode: Int): DefaultUpdateRepository {
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        val source = UpdateManifestSource(
            client = client,
            manifestUrl = server.url("/app/guard-version.json").toString(),
            versionsUrl = server.url("/api/app/versions").toString(),
            moshi = Moshi.Builder().build(),
        )
        return DefaultUpdateRepository(
            source = source,
            preferences = preferences,
            clock = Clock { now },
            installedVersionCode = installedVersionCode,
        )
    }

    private fun manifestJson(versionCode: Int, versionName: String, minSupported: Int?): String {
        val floor = minSupported?.let { ""","min_supported_version_code":$it""" }.orEmpty()
        return """{"version_code":$versionCode,"version_name":"$versionName",
                  "apk_url":"https://minsu.edu.ph/app/guard.apk"$floor}""".trimIndent()
    }

    private fun enqueueManifest(
        versionCode: Int = 3,
        versionName: String = "0.3.0",
        minSupported: Int? = null,
    ) {
        manifestResponses.add(
            MockResponse().setBody(manifestJson(versionCode, versionName, minSupported))
        )
    }

    /** The office's list, which the source prefers over the static file. */
    private fun publishList(vararg versions: Triple<Int, String, Int?>) {
        versionList = versions.joinToString(
            prefix = """{"data":[""",
            postfix = "]}",
            separator = ",",
        ) { (code, name, floor) -> manifestJson(code, name, floor) }
    }

    /*
     * The office's list is preferred, and the static file is what happens when it cannot be had.
     *
     * The fallback is not a legacy path waiting to be deleted. It is what keeps the updater
     * working while the server is down, being restarted, or has simply not had the release
     * feature deployed to it — the exact moments a guard most needs to hear about a fix.
     */
    @Test
    fun `the published list is preferred over the static manifest`() = runTest {
        publishList(Triple(9, "0.9.0", null))
        enqueueManifest(versionCode = 3, versionName = "0.3.0")

        val outcome = repository(installedVersionCode = 2).check()

        val status = (outcome as CheckOutcome.Finished).status
        assertEquals("0.9.0", (status as UpdateStatus.Available).update.versionName)
        assertEquals("the manifest must not be asked for when the list answered", 0, manifestRequests)
    }

    @Test
    fun `an unreachable list falls back to the static manifest`() = runTest {
        // versionList stays null, so the endpoint 404s — the server has not been updated yet.
        enqueueManifest(versionCode = 3, versionName = "0.3.0")

        val outcome = repository(installedVersionCode = 2).check()

        assertTrue((outcome as CheckOutcome.Finished).status is UpdateStatus.Available)
        assertEquals(1, manifestRequests)
    }

    /** Only builds this handset could take: Android refuses anything below the installed code. */
    @Test
    fun `only versions newer than the installed build are offered`() = runTest {
        publishList(
            Triple(9, "0.9.0", null),
            Triple(5, "0.5.0", null),
            Triple(2, "0.2.0", null),
            Triple(1, "0.1.0", null),
        )

        val repository = repository(installedVersionCode = 2)
        repository.check()

        assertEquals(listOf(9, 5), repository.available.value.map { it.versionCode })
    }

    @Test
    fun `the newest is what the prompt offers`() = runTest {
        publishList(Triple(5, "0.5.0", null), Triple(9, "0.9.0", null))

        val outcome = repository(installedVersionCode = 2).check()

        val status = (outcome as CheckOutcome.Finished).status
        assertEquals("0.9.0", (status as UpdateStatus.Available).update.versionName)
    }

    /**
     * The floor is read from the newest release, not from whichever entry happens to carry one.
     *
     * A release that raises the minimum does so as of itself; an older entry still carrying the
     * old floor must not undo that.
     */
    @Test
    fun `the floor comes from the newest release`() = runTest {
        publishList(Triple(9, "0.9.0", 8), Triple(5, "0.5.0", 1))

        val outcome = repository(installedVersionCode = 2).check()

        assertTrue((outcome as CheckOutcome.Finished).status is UpdateStatus.Required)
    }

    @Test
    fun `a list with nothing newer leaves the guard up to date`() = runTest {
        publishList(Triple(2, "0.2.0", null), Triple(1, "0.1.0", null))

        val repository = repository(installedVersionCode = 2)
        val outcome = repository.check()

        assertEquals(UpdateStatus.UpToDate, (outcome as CheckOutcome.Finished).status)
        assertTrue(repository.available.value.isEmpty())
    }

    /** Nothing published yet is an answer, and not a reason to fall back to a stale file. */
    @Test
    fun `an empty list is up to date, not a fallback`() = runTest {
        versionList = """{"data":[]}"""
        enqueueManifest(versionCode = 99, versionName = "0.99.0")

        val outcome = repository(installedVersionCode = 2).check()

        assertEquals(UpdateStatus.UpToDate, (outcome as CheckOutcome.Finished).status)
        assertEquals("the manifest must not be consulted", 0, manifestRequests)
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
        manifestResponses.add(MockResponse().setResponseCode(500))
        val repository = repository(installedVersionCode = 2)

        val outcome = repository.check()

        assertEquals(CheckOutcome.Unreachable, outcome)
        assertEquals(UpdateStatus.Unknown, repository.status.value)
    }

    @Test
    fun `malformed json leaves the status untouched`() = runTest {
        manifestResponses.add(MockResponse().setBody("<html>proxy sign-in page</html>"))
        val repository = repository(installedVersionCode = 2)

        val outcome = repository.check()

        assertEquals(CheckOutcome.Unreachable, outcome)
        assertEquals(UpdateStatus.Unknown, repository.status.value)
    }

    @Test
    fun `a dropped connection leaves the status untouched`() = runTest {
        manifestResponses.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val repository = repository(installedVersionCode = 2)

        val outcome = repository.check()

        assertEquals(CheckOutcome.Unreachable, outcome)
        assertEquals(UpdateStatus.Unknown, repository.status.value)
    }

    @Test
    fun `a failed check does not start the throttle window`() = runTest {
        // Otherwise one flaky check on launch would blind the app for six hours.
        manifestResponses.add(MockResponse().setResponseCode(500))
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
        assertEquals(1, manifestRequests)
    }

    @Test
    fun `the window reopens once it has elapsed`() = runTest {
        enqueueManifest(versionCode = 3)
        val repository = repository(installedVersionCode = 2)
        repository.check()

        now += TimeUnit.HOURS.toMillis(7)
        enqueueManifest(versionCode = 4, versionName = "0.4.0")

        assertTrue(repository.check() is CheckOutcome.Finished)
        assertEquals(2, manifestRequests)
    }

    @Test
    fun `a forced check ignores the window`() = runTest {
        enqueueManifest(versionCode = 3)
        val repository = repository(installedVersionCode = 2)
        repository.check()

        enqueueManifest(versionCode = 3)
        assertTrue(repository.check(force = true) is CheckOutcome.Finished)
        assertEquals(2, manifestRequests)
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
        manifestResponses.add(
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
        manifestResponses.add(
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
