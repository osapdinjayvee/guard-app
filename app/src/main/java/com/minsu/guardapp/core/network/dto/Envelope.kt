package com.minsu.guardapp.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Every successful response wraps its payload in `data`, per the API contract. */
@JsonClass(generateAdapter = true)
data class Envelope<T>(
    @Json(name = "data") val data: T,
)

/** Paginated collections add `meta`. */
@JsonClass(generateAdapter = true)
data class PagedEnvelope<T>(
    @Json(name = "data") val data: List<T>,
    @Json(name = "meta") val meta: PageMetaDto,
)

@JsonClass(generateAdapter = true)
data class PageMetaDto(
    @Json(name = "page") val page: Int,
    @Json(name = "per_page") val perPage: Int,
    @Json(name = "total") val total: Int,
)
