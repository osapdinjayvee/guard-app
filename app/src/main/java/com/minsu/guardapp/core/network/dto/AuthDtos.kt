package com.minsu.guardapp.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LoginRequest(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String,
    @Json(name = "device_id") val deviceId: String? = null,
)

/** `POST /api/login` returns the token alongside the profile, so Home can render immediately. */
@JsonClass(generateAdapter = true)
data class LoginResponse(
    @Json(name = "token") val token: String,
    @Json(name = "user") val user: UserDto,
)
