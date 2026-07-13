package com.minsu.guardapp.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A post-shift self-evaluation question, cached.
 *
 * Cached for the same reason as everything else: a guard closing a shift at a perimeter post has no
 * more signal than one opening it, and a Time Out that cannot be completed because the questions
 * failed to download is a shift the guard cannot clock out of.
 */
@Entity(tableName = "evaluation_questions")
data class EvaluationQuestionEntity(
    @PrimaryKey val id: Long,
    val question: String,
    val sortOrder: Int,
    val updatedAt: Long,
)

@Dao
interface EvaluationQuestionDao {

    @Upsert
    suspend fun upsertAll(questions: List<EvaluationQuestionEntity>)

    @Query("SELECT * FROM evaluation_questions ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<EvaluationQuestionEntity>>

    @Query("SELECT * FROM evaluation_questions ORDER BY sortOrder, id")
    suspend fun all(): List<EvaluationQuestionEntity>

    /**
     * Replaced wholesale, not merged. A question the office retired must stop being asked — and a
     * question that lingers on one phone but not another produces two different evaluations of the
     * same shift.
     */
    @Query("DELETE FROM evaluation_questions")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM evaluation_questions")
    suspend fun count(): Int
}
