package com.minsu.guardapp.domain.repository

import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.model.ShiftWindow
import com.minsu.guardapp.domain.model.Announcement
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.model.EvaluationQuestion
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.domain.model.SyncOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Every repository reads locally and refreshes separately. A failed refresh never clears the
 * cache: an offline guard must still be able to scan, acknowledge duties, and submit.
 */

interface CheckpointRepository {
    /** Resolves a scanned QR value against the local cache. Works offline. */
    suspend fun resolve(code: String): CheckpointResolution

    /** One cached checkpoint by id. Names the post a stationed guard timed in at. */
    suspend fun byId(id: Long): Checkpoint?

    fun observeActive(): Flow<List<Checkpoint>>

    suspend fun refresh(): ApiResult<Unit>
}

interface DutyRepository {
    /** The duties revision the guard must acknowledge. Null before the first refresh. */
    suspend fun activeDuty(): Duty?

    suspend fun refresh(): ApiResult<Unit>
}

interface SettingsRepository {
    /** Emits defaults until a refresh has succeeded at least once. */
    fun observe(): Flow<AppSettings>

    suspend fun current(): AppSettings

    suspend fun refresh(): ApiResult<Unit>
}

interface AttendanceRepository {
    /** Records that still owe the server an upload. Drives Home's pending badge. */
    fun observeUnsyncedCount(): Flow<Int>

    /**
     * Unsynced records captured by a *different* guard on this device.
     *
     * The sync worker uploads only the current guard's records, so a previous account's captures sit
     * here, invisible to [observeUnsyncedCount], until their owner signs back in. Surfaced on Home so
     * they are never silently stuck.
     */
    fun observeOtherAccountUnsyncedCount(): Flow<Int>

    fun observeHistory(limit: Int = 50): Flow<List<AttendanceRecord>>

    /**
     * Downloads the guard's own records from the server into the local table.
     *
     * History and Reports read only from Room, which is right — they must work in a basement. But
     * it also means a device with an empty table shows a guard *nothing*, and reads as though their
     * attendance were lost: clear the app's data, reinstall, or hand them a new phone, and a year of
     * records simply is not there. The records were never lost; this device had just never been told
     * about them.
     *
     * Fills gaps only. Nothing already on the device is overwritten, so a capture still queued for
     * upload is never displaced by the server's older view of the world.
     */
    suspend fun refreshHistory(): ApiResult<Int>

    /** One record, live: the detail screen follows its sync status as the worker runs. */
    fun observeRecord(id: String): Flow<AttendanceRecord?>

    /** Re-queues a failed or rejected record and requests a sync. No-op for other states. */
    suspend fun retry(id: String)

    /** Records the server would not take. Never hidden: a rejected attendance is the guard's problem to see. */
    fun observeStuckCount(): Flow<Int>

    /** Of those, the ones the server refused outright. Only these can be discarded. */
    fun observeRejectedCount(): Flow<Int>

    /**
     * Throws away every rejected record and its selfie, permanently. Returns how many went.
     *
     * The escape hatch of last resort. A rejected record cannot sync — the server has given a
     * reason and will give the same one again — so without this the count sits on the Account
     * screen forever, and a guard learns to ignore a warning that never clears. That is worse than
     * losing the record: it hides the next one.
     *
     * Nothing else in the app deletes an attendance. This is not a tidy-up, it is destroying
     * evidence the server never accepted, and it must stay behind an explicit confirmation.
     */
    suspend fun discardRejected(): Int

    /**
     * Catches this device up in both directions.
     *
     * Re-queues every failed or rejected record, drains the queue, and pulls back any records the
     * server holds that this device does not.
     */
    suspend fun syncNow(): SyncOutcome

    /** Local records captured within [fromMillis, toMillis). The source for Reports. */
    fun observeInRange(fromMillis: Long, toMillis: Long): Flow<List<AttendanceRecord>>

    /*
     * The three below take the shift they are asking about.
     *
     * They used to say `…Today` and mean it — bounded midnight to midnight — which is the bug that
     * lost the first half of every night shift the moment the date rolled over. The name is part of
     * the fix: "today" was the lie, and leaving it would invite the next person to reintroduce it.
     */

    /**
     * Visits per checkpoint during [window], from this device, keyed by checkpoint id.
     *
     * A post the guard has not reached is simply absent from the map. Decides whether the round is
     * walked, and the rule is two visits to each post rather than two scans anywhere.
     */
    suspend fun checkpointVisitsIn(window: ShiftWindow): Map<Long, Int>

    /**
     * The post this guard visited most recently in [window], or null if they have not visited one.
     *
     * Two visits to the same post back to back are not a patrol — they are a guard standing at one
     * door scanning it twice. The round requires them to go somewhere else in between.
     */
    suspend fun lastVisitedCheckpointIn(window: ShiftWindow): Long?

    /**
     * Whether the guard has already closed this shift.
     *
     * Once true, scanning offers nothing: the shift is done, and Time In returns only with the next
     * one. Read locally so it holds without a signal.
     */
    suspend fun hasTimedOutIn(window: ShiftWindow): Boolean

    /**
     * Commits a captured attendance record to the local database, then requests a sync. The
     * record is durable the instant this returns — the write is local-first, never
     * network-first, so a valid attendance is never lost to connectivity.
     *
     * @param id the client-generated UUID that is also the idempotency key.
     */
    suspend fun submit(id: String, draft: AttendanceDraft)
}

interface ProfileRepository {
    /** Null until a profile has been fetched. Cached so Home renders a name offline. */
    fun observe(): Flow<GuardProfile?>

    suspend fun refresh(): ApiResult<Unit>

    suspend fun clear()
}

interface AnnouncementRepository {
    fun observe(): Flow<List<Announcement>>

    suspend fun refresh(): ApiResult<Unit>
}

interface AuthRepository {
    /** True while a session token is stored. Drives the auth gate. */
    val isAuthenticated: Flow<Boolean>

    suspend fun login(username: String, password: String): ApiResult<Unit>

    /** Clears the local session. Never touches the attendance queue. */
    suspend fun logout()
}

/**
 * The guard's duty roster.
 *
 * Cached, and read from the cache, because the scanner needs to know whether this guard is stationed
 * or roving *before* it can offer them the right buttons — and it needs to know that in a basement.
 */
interface ScheduleRepository {
    /**
     * The duty that applies right now. Null when the office has filed nothing for today.
     *
     * Not "today's duty": a shift that began yesterday and runs past midnight is still the one
     * being worked, and is what this answers until it ends. A rest day is a duty like any other —
     * [DutyType.OFF] — and is distinct from null, which means nobody scheduled anything.
     */
    fun observeCurrentDuty(): Flow<DutyAssignment?>

    /** Every scheduled day the phone knows about, in order. What the guard is shown. */
    fun observeAll(): Flow<List<DutyAssignment>>

    suspend fun currentDuty(): DutyAssignment?

    /** False when nobody has joined this login to a guard. Not the same as "no shift". */
    val isLinked: Flow<Boolean>

    /**
     * The checkpoint this guard opened the current shift at, if they have.
     *
     * A stationed guard's post. Nobody assigns it — the first Time In of the shift defines it, and
     * the shift must end where it began.
     */
    suspend fun postTimedInAt(window: ShiftWindow): Long?

    suspend fun refresh(): ApiResult<Unit>
}

/**
 * PDFs the office publishes — the guard handbook, and whatever follows it.
 *
 * Only the address is dealt with here. The file is opened by whatever the handset uses for PDFs,
 * because a guard's phone already reads them and this app carrying its own viewer would be a lot
 * of build for a document most guards open twice.
 */
interface DocumentRepository {
    /** The published URL of [identifier], or null if there is none or it could not be reached. */
    suspend fun url(identifier: String): String?

    companion object {
        /** The one the Home tile opens. Slugged from the document's title, server-side. */
        const val GUARD_HANDBOOK = "sg_handbook"
    }
}

/**
 * The self-evaluation questions, for both ends of a shift.
 *
 * Cached, because a guard closing a shift at a perimeter post has no more signal than one opening
 * it — and a Time Out they cannot complete is a shift they cannot clock out of.
 */
interface EvaluationRepository {
    /**
     * The questions asked at [type], in the order the office wants them asked.
     *
     * The whole set is downloaded in one call and filtered here rather than fetched per type: a
     * guard opens a shift with signal and closes it without, so the Time Out questions have to
     * already be on the phone by the time they are needed.
     */
    /**
     * @param duty what the guard is working. Questions the office asks only of the *other* duty are
     *   left out, and a change of duty since the set was cached is what [isStaleFor] reports.
     */
    suspend fun questions(type: AttendanceType, duty: DutyType?): List<EvaluationQuestion>

    /**
     * True when the cached set was fetched for a different duty than the one being worked.
     *
     * The server filters the set by the guard's duty at the moment of the request, so a set cached
     * on a stationed day is missing every roving-only question. Answering the wrong set is not a
     * cosmetic error: the server checks completeness on submission and answers a short evaluation
     * with a 422, which the sync queue treats as permanent.
     */
    suspend fun isStaleFor(duty: DutyType?): Boolean

    suspend fun refresh(duty: DutyType?): ApiResult<Unit>
}
