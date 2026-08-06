package com.minsu.guardapp.data

import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.dto.AnnouncementDto
import com.minsu.guardapp.core.network.dto.AttendanceDto
import com.minsu.guardapp.core.network.dto.CheckpointDto
import com.minsu.guardapp.core.network.dto.DocumentDto
import com.minsu.guardapp.core.network.dto.DutyDto
import com.minsu.guardapp.core.network.dto.Envelope
import com.minsu.guardapp.core.network.dto.EvaluationQuestionDto
import com.minsu.guardapp.core.network.dto.LoginRequest
import com.minsu.guardapp.core.network.dto.LoginResponse
import com.minsu.guardapp.core.network.dto.MobileSettingsDto
import com.minsu.guardapp.core.network.dto.PagedEnvelope
import com.minsu.guardapp.core.network.dto.ProfileDto
import com.minsu.guardapp.core.network.dto.ScheduleDto
import okhttp3.MultipartBody
import okhttp3.RequestBody

/**
 * A [GuardApi] whose every endpoint fails loudly until a test overrides the one it cares about.
 *
 * Retrofit interfaces are wide and tests use a sliver of them; stubbing the rest with empty
 * successes would let a test quietly pass while calling something it never meant to. Failing here
 * names the endpoint instead.
 */
open class FakeGuardApi : GuardApi {

    override suspend fun login(request: LoginRequest): LoginResponse = notStubbed("login")

    override suspend fun logout(): Unit = notStubbed("logout")

    override suspend fun submitAttendance(
        clientUuid: RequestBody,
        checkpointId: RequestBody,
        attendanceType: RequestBody,
        capturedAt: RequestBody,
        dutiesAcknowledged: RequestBody,
        latitude: RequestBody?,
        longitude: RequestBody?,
        accuracy: RequestBody?,
        dutiesVersionId: RequestBody?,
        evaluations: RequestBody?,
        deviceId: RequestBody?,
        selfie: MultipartBody.Part,
    ): Envelope<AttendanceDto> = notStubbed("submitAttendance")

    override suspend fun profile(): Envelope<ProfileDto> = notStubbed("profile")

    override suspend fun checkpoints(): Envelope<List<CheckpointDto>> = notStubbed("checkpoints")

    override suspend fun checkpoint(code: String): Envelope<CheckpointDto> = notStubbed("checkpoint")

    override suspend fun schedule(): Envelope<ScheduleDto> = notStubbed("schedule")

    override suspend fun duties(): Envelope<DutyDto> = notStubbed("duties")

    override suspend fun evaluationQuestions(): Envelope<List<EvaluationQuestionDto>> =
        notStubbed("evaluationQuestions")

    override suspend fun announcements(): Envelope<List<AnnouncementDto>> = notStubbed("announcements")

    override suspend fun settings(): Envelope<MobileSettingsDto> = notStubbed("settings")

    override suspend fun history(page: Int, perPage: Int): PagedEnvelope<AttendanceDto> =
        notStubbed("history")

    override suspend fun document(identifier: String): Envelope<DocumentDto> =
        notStubbed("document")

    private fun notStubbed(endpoint: String): Nothing =
        throw NotImplementedError("$endpoint was called but this test did not stub it")
}