package com.minsu.guardapp.data

import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.dto.AnnouncementDto
import com.minsu.guardapp.core.network.dto.AttendanceDto
import com.minsu.guardapp.core.network.dto.CheckpointDto
import com.minsu.guardapp.core.network.dto.DutyDto
import com.minsu.guardapp.core.network.dto.Envelope
import com.minsu.guardapp.core.network.dto.MobileSettingsDto
import com.minsu.guardapp.core.network.dto.PagedEnvelope
import com.minsu.guardapp.core.network.dto.ProfileDto
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.GpsFailurePolicy
import com.minsu.guardapp.testing.FakeGuardApi
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SettingsRepositoryTest {

    private class FakeSettingsCache : SettingsCache {
        val state = MutableStateFlow(AppSettings())
        override fun observe(): Flow<AppSettings> = state
        override suspend fun save(settings: AppSettings) { state.value = settings }
    }

    private val cache = FakeSettingsCache()
    private val errors = ApiErrorMapper(Moshi.Builder().build())

    private fun repo(api: GuardApi) = DefaultSettingsRepository(cache, api, errors)

    @Test
    fun `emits documented defaults before the first successful refresh`() = runTest {
        val settings = repo(FakeGuardApi()).current()

        assertEquals(50f, settings.gpsAccuracyThresholdMetres, 0.01f)
        assertEquals(GpsFailurePolicy.BLOCK, settings.gpsFailurePolicy)
        assertEquals(80, settings.imageQuality)
    }

    @Test
    fun `refresh replaces the thresholds the capture flow reads`() = runTest {
        val repository = repo(object : FakeGuardApi() {
            override suspend fun settings() = Envelope(
                MobileSettingsDto(
                    gpsAccuracyThresholdM = 25f,
                    gpsFailurePolicy = "allow",
                    imageQuality = 60,
                    imageMaxDimensionPx = 1080,
                    maintenanceMessage = "Back at 6am",
                )
            )
        })

        assertTrue(repository.refresh() is ApiResult.Success)

        val settings = repository.current()
        assertEquals(25f, settings.gpsAccuracyThresholdMetres, 0.01f)
        assertEquals(GpsFailurePolicy.ALLOW, settings.gpsFailurePolicy)
        assertEquals(60, settings.imageQuality)
        assertEquals("Back at 6am", settings.maintenanceMessage)
    }

    /**
     * A typo in a server config must not silently start accepting attendance with no location.
     */
    @Test
    fun `an unrecognised gps policy fails safe to BLOCK`() = runTest {
        val repository = repo(object : FakeGuardApi() {
            override suspend fun settings() =
                Envelope(MobileSettingsDto(gpsFailurePolicy = "permissive"))
        })

        repository.refresh()

        assertEquals(GpsFailurePolicy.BLOCK, repository.current().gpsFailurePolicy)
    }

    @Test
    fun `a failed refresh keeps the last known thresholds`() = runTest {
        cache.save(AppSettings(gpsAccuracyThresholdMetres = 15f, gpsFailurePolicy = GpsFailurePolicy.ALLOW))
        val repository = repo(object : FakeGuardApi() {
            override suspend fun settings(): Envelope<MobileSettingsDto> = throw IOException("offline")
        })

        assertTrue(repository.refresh() is ApiResult.Failure)

        val settings = repository.current()
        assertEquals(15f, settings.gpsAccuracyThresholdMetres, 0.01f)
        assertEquals(GpsFailurePolicy.ALLOW, settings.gpsFailurePolicy)
    }
}
