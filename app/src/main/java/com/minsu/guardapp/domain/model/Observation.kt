package com.minsu.guardapp.domain.model

/** The server's rules for an observation, checked on the phone so a report is never queued to be refused. */
object ObservationRules {
    const val MAX_NOTE_LENGTH = 5_000
    const val MIN_PHOTOS = 1
    const val MAX_PHOTOS = 5
}

/** One captured photo, with the time and fix it was taken at. */
data class ObservationPhotoDraft(
    val path: String,
    val capturedAtMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float?,
)

/** An observation as the guard wrote it, before it is committed. */
data class ObservationDraft(
    val note: String,
    val capturedAtMillis: Long,
    val photos: List<ObservationPhotoDraft>,
)

/** A stored observation and where it stands in the upload queue. */
data class Observation(
    val id: String,
    val note: String,
    val capturedAtMillis: Long,
    val photoPaths: List<String>,
    val syncState: SyncState,
    val lastError: String?,
)
