package com.appetiser.guardapp.core.network

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
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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

    @GET("profile")
    suspend fun profile(): Envelope<ProfileDto>

    /** Not paginated: the client caches the whole set so a QR code resolves offline. */
    @GET("checkpoints")
    suspend fun checkpoints(): Envelope<List<CheckpointDto>>

    @GET("duties")
    suspend fun duties(): Envelope<DutyDto>

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
}
