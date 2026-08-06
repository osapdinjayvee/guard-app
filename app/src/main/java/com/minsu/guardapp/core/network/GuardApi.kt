package com.minsu.guardapp.core.network

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
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * The backend contract, as specified in `.docs/api/openapi.yaml`.
 *
 * `POST /attendance` is absent until T-23: it is a multipart upload whose idempotency
 * semantics depend on decision 1, which is not yet ratified.
 */
interface GuardApi {

    /** Unauthenticated. A 401 here means "wrong password", not "session expired". */
    @POST("login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("logout")
    suspend fun logout()

    /**
     * Submit one attendance record. Multipart (decision 2): base64 would inflate the payload by
     * a third and hold the whole image in memory as a string. Idempotent on client_uuid — the
     * server returns 200 with the existing record for a repeat, 201 for a first submit
     * (decision 1). Both are success.
     */
    @Multipart
    @POST("attendance")
    suspend fun submitAttendance(
        @Part("client_uuid") clientUuid: RequestBody,
        @Part("qr_checkpoint_id") checkpointId: RequestBody,
        @Part("attendance_type") attendanceType: RequestBody,
        @Part("captured_at") capturedAt: RequestBody,
        @Part("duties_acknowledged") dutiesAcknowledged: RequestBody,
        @Part("latitude") latitude: RequestBody?,
        @Part("longitude") longitude: RequestBody?,
        @Part("accuracy") accuracy: RequestBody?,
        @Part("duties_version_id") dutiesVersionId: RequestBody?,
        /**
         * The post-shift self-evaluation, as a JSON array. Present on a Time Out and absent on
         * anything else — the server requires it on one and prohibits it on the other.
         */
        @Part("evaluations") evaluations: RequestBody?,
        @Part("device_id") deviceId: RequestBody?,
        @Part selfie: MultipartBody.Part,
    ): Envelope<AttendanceDto>

    @GET("profile")
    suspend fun profile(): Envelope<ProfileDto>

    /** Not paginated: the client caches the whole set so a QR code resolves offline. */
    @GET("checkpoints")
    suspend fun checkpoints(): Envelope<List<CheckpointDto>>

    /**
     * Resolves one scanned code when the local cache misses — a checkpoint added since the last
     * refresh, or a cache that has never been warmed. Matches the code or the slug. A 404 here is
     * the only thing that licenses the app to tell a guard their code is not a checkpoint.
     */
    @GET("checkpoints/{code}")
    suspend fun checkpoint(@Path("code") code: String): Envelope<CheckpointDto>

    /**
     * The guard's own duty roster. Drives the scanner: a stationed guard is offered Time In and Time
     * Out alone and is refused a second checkpoint; a roving guard may record a visit anywhere.
     */
    @GET("schedule")
    suspend fun schedule(): Envelope<ScheduleDto>

    @GET("duties")
    suspend fun duties(): Envelope<DutyDto>

    /**
     * The post-shift self-evaluation questions. Cached, because a guard closing a shift at a
     * perimeter post has no more signal than one opening it — and a Time Out they cannot complete
     * is a shift they cannot clock out of.
     */
    @GET("evaluation-questions")
    suspend fun evaluationQuestions(): Envelope<List<EvaluationQuestionDto>>

    @GET("announcements")
    suspend fun announcements(): Envelope<List<AnnouncementDto>>

    /** Thresholds live here, never in constants. */
    @GET("settings")
    suspend fun settings(): Envelope<MobileSettingsDto>

    @GET("attendance/history")
    suspend fun history(
        @Query("page") page: Int = 1,
        @Query("per_page") perPage: Int = 25,
    ): PagedEnvelope<AttendanceDto>

    /**
     * A published PDF by name — the guard handbook, and whatever the office publishes next.
     *
     * Only the URL comes back; the file itself is opened by whatever the handset uses for PDFs.
     * Downloading a twenty-megabyte handbook into the app, to show it in a viewer this app does
     * not have, would be a lot of a guard's data spent to reproduce something their phone already
     * does well.
     */
    @GET("documents/{identifier}")
    suspend fun document(@Path("identifier") identifier: String): Envelope<DocumentDto>
}
