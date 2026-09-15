package com.minsu.guardapp.data

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.ObservationDao
import com.minsu.guardapp.core.database.ObservationEntity
import com.minsu.guardapp.core.database.ObservationPhotoEntity
import com.minsu.guardapp.core.database.ObservationWithPhotos
import com.minsu.guardapp.core.database.SyncStatus
import com.minsu.guardapp.core.sync.SyncScheduler
import com.minsu.guardapp.domain.model.Observation
import com.minsu.guardapp.domain.model.ObservationDraft
import com.minsu.guardapp.domain.model.ObservationRules
import com.minsu.guardapp.domain.model.SyncState
import com.minsu.guardapp.domain.repository.ObservationRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultObservationRepository @Inject constructor(
    private val dao: ObservationDao,
    private val profiles: ProfileRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : ObservationRepository {

    /** -1 when nobody is signed in, so queries return nothing rather than everyone's reports. */
    private fun currentUserId(): Flow<Long> = profiles.observe().map { it?.id ?: NO_USER }

    override fun observeRecent(limit: Int): Flow<List<Observation>> =
        currentUserId().flatMapLatest { userId ->
            dao.observePage(userId, limit).map { rows -> rows.map { it.toDomain() } }
        }

    override fun observeUnsyncedCount(): Flow<Int> =
        currentUserId().flatMapLatest { dao.observeUnsyncedCount(it) }

    override suspend fun submit(id: String, draft: ObservationDraft) {
        val note = draft.note.trim()
        // Checked here as well as on the screen: a draft the server will refuse is a record that sits
        // in Rejected forever, so it is never allowed into the queue.
        require(note.isNotEmpty()) { "Write what you observed before submitting." }
        require(note.length <= ObservationRules.MAX_NOTE_LENGTH) {
            "The note is longer than ${ObservationRules.MAX_NOTE_LENGTH} characters."
        }
        require(draft.photos.size in ObservationRules.MIN_PHOTOS..ObservationRules.MAX_PHOTOS) {
            "Attach between ${ObservationRules.MIN_PHOTOS} and ${ObservationRules.MAX_PHOTOS} photos."
        }

        val userId = profiles.observe().first()?.id ?: NO_USER
        check(userId != NO_USER) {
            "Could not tell who is signed in. Reopen the app and try again — your photos are not lost."
        }

        val now = clock.nowMillis()
        // Insert first, then enqueue: the report is durable before anything touches the network.
        dao.insert(
            observation = ObservationEntity(
                id = id,
                userId = userId,
                note = note,
                capturedAt = draft.capturedAtMillis,
                deviceId = null,
                syncStatus = SyncStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            ),
            photos = draft.photos.mapIndexed { index, photo ->
                ObservationPhotoEntity(
                    id = UUID.randomUUID().toString(),
                    observationId = id,
                    position = index,
                    path = photo.path,
                    capturedAt = photo.capturedAtMillis,
                    latitude = photo.latitude,
                    longitude = photo.longitude,
                    accuracy = photo.accuracyMetres,
                )
            },
        )
        syncScheduler.requestSync()
    }

    override suspend fun retry(id: String) {
        if (dao.requeue(id, clock.nowMillis()) > 0) syncScheduler.requestSync()
    }

    private fun ObservationWithPhotos.toDomain() = Observation(
        id = observation.id,
        note = observation.note,
        capturedAtMillis = observation.capturedAt,
        photoPaths = photos.sortedBy { it.position }.map { it.path },
        syncState = when (observation.syncStatus) {
            SyncStatus.PENDING -> SyncState.PENDING
            SyncStatus.SYNCING -> SyncState.SYNCING
            SyncStatus.SYNCED -> SyncState.SYNCED
            SyncStatus.FAILED -> SyncState.FAILED
            SyncStatus.REJECTED -> SyncState.REJECTED
        },
        lastError = observation.lastError,
    )

    private companion object {
        const val NO_USER = -1L
    }
}
