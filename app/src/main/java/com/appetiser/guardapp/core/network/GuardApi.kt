package com.appetiser.guardapp.core.network

import com.appetiser.guardapp.core.network.dto.CheckpointDto
import retrofit2.http.GET

/**
 * Backend contract. Only the endpoints needed so far are declared; the rest land as their
 * features do. The full endpoint table lives in `.docs/Implementation_Plan.md` §5.
 */
interface GuardApi {

    @GET("checkpoints")
    suspend fun checkpoints(): List<CheckpointDto>
}
