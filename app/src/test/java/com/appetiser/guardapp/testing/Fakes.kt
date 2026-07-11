package com.appetiser.guardapp.testing

import com.appetiser.guardapp.core.network.GuardApi
import com.appetiser.guardapp.core.network.dto.AnnouncementDto
import com.appetiser.guardapp.core.network.dto.AttendanceDto
import com.appetiser.guardapp.core.network.dto.CheckpointDto
import com.appetiser.guardapp.core.network.dto.DutyDto
import com.appetiser.guardapp.core.network.dto.Envelope
import com.appetiser.guardapp.core.network.dto.LoginRequest
import com.appetiser.guardapp.core.network.dto.LoginResponse
import com.appetiser.guardapp.core.network.dto.MobileSettingsDto
import com.appetiser.guardapp.core.network.dto.PagedEnvelope
import com.appetiser.guardapp.core.network.dto.ProfileDto
import com.appetiser.guardapp.core.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Every endpoint throws unless a test overrides it, so a test that reaches an endpoint it did
 * not intend to exercise fails loudly rather than seeing a default value.
 *
 * Shared so that adding an endpoint to [GuardApi] does not break every test file at once.
 */
open class FakeGuardApi : GuardApi {
    override suspend fun login(request: LoginRequest): LoginResponse = error("login not stubbed")
    override suspend fun logout(): Unit = error("logout not stubbed")
    override suspend fun profile(): Envelope<ProfileDto> = error("profile not stubbed")
    override suspend fun checkpoints(): Envelope<List<CheckpointDto>> = error("checkpoints not stubbed")
    override suspend fun duties(): Envelope<DutyDto> = error("duties not stubbed")
    override suspend fun announcements(): Envelope<List<AnnouncementDto>> = error("announcements not stubbed")
    override suspend fun settings(): Envelope<MobileSettingsDto> = error("settings not stubbed")
    override suspend fun history(page: Int, perPage: Int): PagedEnvelope<AttendanceDto> =
        error("history not stubbed")
    override suspend fun submitAttendance(
        clientUuid: okhttp3.RequestBody,
        checkpointId: okhttp3.RequestBody,
        attendanceType: okhttp3.RequestBody,
        capturedAt: okhttp3.RequestBody,
        dutiesAcknowledged: okhttp3.RequestBody,
        latitude: okhttp3.RequestBody?,
        longitude: okhttp3.RequestBody?,
        accuracy: okhttp3.RequestBody?,
        dutiesVersionId: okhttp3.RequestBody?,
        deviceId: okhttp3.RequestBody?,
        selfie: okhttp3.MultipartBody.Part,
    ): Envelope<AttendanceDto> = error("submitAttendance not stubbed")
}

class FakeTokenStore(initial: String? = null) : TokenStore {
    private val present = MutableStateFlow(initial != null)
    var stored: String? = initial
    var clearCount = 0

    override val hasToken: Flow<Boolean> = present
    override suspend fun token(): String? = stored
    override suspend fun save(token: String) {
        stored = token
        present.value = true
    }
    override suspend fun clear() {
        stored = null
        present.value = false
        clearCount++
    }
}
