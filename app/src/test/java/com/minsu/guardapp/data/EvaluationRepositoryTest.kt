package com.minsu.guardapp.data

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.EvaluationQuestionDao
import com.minsu.guardapp.core.database.EvaluationQuestionEntity
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.dto.Envelope
import com.minsu.guardapp.core.network.dto.EvaluationQuestionDto
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.model.GuardTarget
import com.minsu.guardapp.domain.model.EvaluationTiming
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which questions get asked at which end of a shift.
 *
 * The filtering is the whole feature. Getting it wrong is not a crash — it is a guard being asked
 * "was the logbook handed over properly?" as they arrive for a shift that has not started.
 */
class EvaluationRepositoryTest {

    private class FakeDao(private val rows: MutableList<EvaluationQuestionEntity>) : EvaluationQuestionDao {
        override suspend fun upsertAll(questions: List<EvaluationQuestionEntity>) { rows += questions }
        override fun observeAll(): Flow<List<EvaluationQuestionEntity>> = MutableStateFlow(rows)
        override suspend fun all(): List<EvaluationQuestionEntity> = rows.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        override suspend fun clear() { rows.clear() }
        override suspend fun count(): Int = rows.size
    }

    /** An in-memory DataStore. The repository records which duty a set was fetched for. */
    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(state.value).also { state.value = it }
    }

    private fun row(
        id: Long,
        timing: EvaluationTiming,
        sortOrder: Int = id.toInt(),
        asksOf: GuardTarget = GuardTarget.BOTH,
    ) = EvaluationQuestionEntity(
        id = id,
        question = "Question $id",
        timing = timing.name,
        targetGuardType = asksOf.name,
        sortOrder = sortOrder,
        updatedAt = 0,
    )

    private fun repo(
        rows: MutableList<EvaluationQuestionEntity>,
        store: DataStore<Preferences> = FakeDataStore(),
    ) = DefaultEvaluationRepository(
        dao = FakeDao(rows),
        api = FakeGuardApi(),
        errors = ApiErrorMapper(Moshi.Builder().build()),
        dataStore = store,
        clock = Clock { 1_000L },
    )

    private val everyTiming = mutableListOf(
        row(1, EvaluationTiming.TIME_IN),
        row(2, EvaluationTiming.TIME_OUT),
        row(3, EvaluationTiming.BOTH),
    )

    @Test
    fun `a time in is asked its own questions and the shared ones`() = runTest {
        val asked = repo(everyTiming).questions(AttendanceType.TIME_IN, DutyType.ROVING)

        assertEquals(listOf(1L, 3L), asked.map { it.id })
    }

    @Test
    fun `a time out is asked its own questions and the shared ones`() = runTest {
        val asked = repo(everyTiming).questions(AttendanceType.TIME_OUT, DutyType.ROVING)

        assertEquals(listOf(2L, 3L), asked.map { it.id })
    }

    /** A visit happens mid-round. It is not a boundary, so it describes nothing. */
    @Test
    fun `a checkpoint visit is asked nothing`() = runTest {
        assertTrue(repo(everyTiming).questions(AttendanceType.CHECKPOINT, DutyType.ROVING).isEmpty())
    }

    /** The office's order, not the database's. */
    @Test
    fun `questions keep the order the office set`() = runTest {
        val rows = mutableListOf(
            row(10, EvaluationTiming.TIME_IN, sortOrder = 3),
            row(11, EvaluationTiming.BOTH, sortOrder = 1),
            row(12, EvaluationTiming.TIME_IN, sortOrder = 2),
        )

        assertEquals(listOf(11L, 12L, 10L), repo(rows).questions(AttendanceType.TIME_IN, DutyType.ROVING).map { it.id })
    }

    /**
     * A row cached by a build that knew a timing this one does not.
     *
     * Falling back to TIME_OUT puts it where every question lived before timings existed. The
     * alternative — dropping it — would silently shorten an evaluation, and a missing question is
     * indistinguishable from one the guard was never meant to answer.
     */
    @Test
    fun `an unrecognised timing is treated as end of shift`() = runTest {
        val rows = mutableListOf(row(1, EvaluationTiming.TIME_IN).copy(timing = "MID_SHIFT"))

        assertTrue(repo(rows).questions(AttendanceType.TIME_IN, DutyType.ROVING).isEmpty())
        assertEquals(listOf(1L), repo(rows).questions(AttendanceType.TIME_OUT, DutyType.ROVING).map { it.id })
    }

    /** The wire uses the server's lowercase spelling; unknown values must not vanish. */
    @Test
    fun `wire values map onto timings, and anything else ends up at time out`() {
        assertEquals(EvaluationTiming.TIME_IN, EvaluationTiming.fromWire("time_in"))
        assertEquals(EvaluationTiming.TIME_OUT, EvaluationTiming.fromWire("time_out"))
        assertEquals(EvaluationTiming.BOTH, EvaluationTiming.fromWire("both"))
        // A server that has not been updated to send the field at all.
        assertEquals(EvaluationTiming.TIME_OUT, EvaluationTiming.fromWire(null))
        assertEquals(EvaluationTiming.TIME_OUT, EvaluationTiming.fromWire("something_new"))
    }

    /** The DTO defaults, so a server without the field still yields a working Time Out set. */
    @Test
    fun `a question with no type on the wire is asked at time out`() {
        val dto = EvaluationQuestionDto(id = 1, question = "Q", sortOrder = 0)

        assertEquals(EvaluationTiming.TIME_OUT, EvaluationTiming.fromWire(dto.type))
    }

    // --- Which duty a question is asked of ---

    private val everyDuty
        get() = mutableListOf(
            row(1, EvaluationTiming.TIME_OUT, asksOf = GuardTarget.STATIONED),
            row(2, EvaluationTiming.TIME_OUT, asksOf = GuardTarget.ROVING),
            row(3, EvaluationTiming.TIME_OUT, asksOf = GuardTarget.BOTH),
        )

    /** A guard at one post is not asked how the round went. */
    @Test
    fun `a stationed guard is asked its own questions and the shared ones`() = runTest {
        val asked = repo(everyDuty).questions(AttendanceType.TIME_OUT, DutyType.STATIONED)

        assertEquals(listOf(1L, 3L), asked.map { it.id })
    }

    @Test
    fun `a roving guard is asked its own questions and the shared ones`() = runTest {
        val asked = repo(everyDuty).questions(AttendanceType.TIME_OUT, DutyType.ROVING)

        assertEquals(listOf(2L, 3L), asked.map { it.id })
    }

    /**
     * No duty on the phone is not a reason to ask nothing.
     *
     * The office's shared questions still apply, and a Time Out with no answers at all is one the
     * server refuses outright.
     */
    @Test
    fun `an unknown duty is still asked the shared questions`() = runTest {
        val asked = repo(everyDuty).questions(AttendanceType.TIME_OUT, duty = null)

        assertEquals(listOf(3L), asked.map { it.id })
    }

    /** The wire uses the server's lowercase spelling; anything unknown is asked of everyone. */
    @Test
    fun `wire values map onto duties, and anything else is asked of both`() {
        assertEquals(GuardTarget.STATIONED, GuardTarget.fromWire("stationed"))
        assertEquals(GuardTarget.ROVING, GuardTarget.fromWire("roving"))
        assertEquals(GuardTarget.BOTH, GuardTarget.fromWire("both"))
        // A server that predates the field, and a value this build has never heard of.
        assertEquals(GuardTarget.BOTH, GuardTarget.fromWire(null))
        assertEquals(GuardTarget.BOTH, GuardTarget.fromWire("supervisor"))
    }

    // --- A set fetched for the wrong duty ---

    /*
     * The failure this guards against.
     *
     * The server filters the set by the duty it sees at request time, so a set cached on a
     * stationed day is missing every roving-only question. Answering the short set is not a
     * cosmetic error: the server checks completeness again on submission and answers with a 422,
     * which the sync queue treats as permanent. The guard cannot close their shift and is given no
     * way to fix it.
     */
    /** Answers the question list, so a refresh can actually succeed and record what it fetched. */
    private class ServingApi : FakeGuardApi() {
        override suspend fun evaluationQuestions(): Envelope<List<EvaluationQuestionDto>> =
            Envelope(emptyList())
    }

    @Test
    fun `a set fetched for the other duty is stale`() = runTest {
        val store = FakeDataStore()
        val repository = DefaultEvaluationRepository(
            dao = FakeDao(mutableListOf()),
            api = ServingApi(),
            errors = ApiErrorMapper(Moshi.Builder().build()),
            dataStore = store,
            clock = Clock { 1_000L },
        )

        repository.refresh(DutyType.STATIONED)

        assertTrue("roving today, stationed set", repository.isStaleFor(DutyType.ROVING))
        assertFalse("still stationed", repository.isStaleFor(DutyType.STATIONED))
    }

    /**
     * A phone that has never recorded a duty is upgrading into this build.
     *
     * Reported stale on purpose: one wasted refresh is cheaper than one refused Time Out.
     */
    @Test
    fun `a set fetched before this build knew about duties is stale`() = runTest {
        assertTrue(repo(everyDuty).isStaleFor(DutyType.ROVING))
    }

    /** Nothing to compare against. Refreshing on every capture would be worse than not knowing. */
    @Test
    fun `an unknown duty is never stale`() = runTest {
        assertFalse(repo(everyDuty).isStaleFor(null))
    }
}
