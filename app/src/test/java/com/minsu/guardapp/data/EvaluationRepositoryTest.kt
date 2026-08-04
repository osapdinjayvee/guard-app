package com.minsu.guardapp.data

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.EvaluationQuestionDao
import com.minsu.guardapp.core.database.EvaluationQuestionEntity
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.dto.EvaluationQuestionDto
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.EvaluationTiming
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private fun row(id: Long, timing: EvaluationTiming, sortOrder: Int = id.toInt()) =
        EvaluationQuestionEntity(
            id = id,
            question = "Question $id",
            timing = timing.name,
            sortOrder = sortOrder,
            updatedAt = 0,
        )

    private fun repo(rows: MutableList<EvaluationQuestionEntity>) = DefaultEvaluationRepository(
        dao = FakeDao(rows),
        api = FakeGuardApi(),
        errors = ApiErrorMapper(Moshi.Builder().build()),
        clock = Clock { 1_000L },
    )

    private val everyTiming = mutableListOf(
        row(1, EvaluationTiming.TIME_IN),
        row(2, EvaluationTiming.TIME_OUT),
        row(3, EvaluationTiming.BOTH),
    )

    @Test
    fun `a time in is asked its own questions and the shared ones`() = runTest {
        val asked = repo(everyTiming).questions(AttendanceType.TIME_IN)

        assertEquals(listOf(1L, 3L), asked.map { it.id })
    }

    @Test
    fun `a time out is asked its own questions and the shared ones`() = runTest {
        val asked = repo(everyTiming).questions(AttendanceType.TIME_OUT)

        assertEquals(listOf(2L, 3L), asked.map { it.id })
    }

    /** A visit happens mid-round. It is not a boundary, so it describes nothing. */
    @Test
    fun `a checkpoint visit is asked nothing`() = runTest {
        assertTrue(repo(everyTiming).questions(AttendanceType.CHECKPOINT).isEmpty())
    }

    /** The office's order, not the database's. */
    @Test
    fun `questions keep the order the office set`() = runTest {
        val rows = mutableListOf(
            row(10, EvaluationTiming.TIME_IN, sortOrder = 3),
            row(11, EvaluationTiming.BOTH, sortOrder = 1),
            row(12, EvaluationTiming.TIME_IN, sortOrder = 2),
        )

        assertEquals(listOf(11L, 12L, 10L), repo(rows).questions(AttendanceType.TIME_IN).map { it.id })
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

        assertTrue(repo(rows).questions(AttendanceType.TIME_IN).isEmpty())
        assertEquals(listOf(1L), repo(rows).questions(AttendanceType.TIME_OUT).map { it.id })
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
}
