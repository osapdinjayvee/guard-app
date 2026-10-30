package com.minsu.guardapp.data

import com.minsu.guardapp.domain.model.EvaluationAnswer
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test

class EvaluationJsonTest {
    private val json = EvaluationJson(Moshi.Builder().build())

    @Test
    fun `encodes every answer with its question id in the evaluations request`() {
        val encoded = json.encode(
            listOf(
                EvaluationAnswer(123, true),
                EvaluationAnswer(456, false),
            ),
        )

        assertEquals(
            "{\"evaluations\":[{\"question_id\":123,\"answer\":true},{\"question_id\":456,\"answer\":false}]}",
            encoded,
        )
    }
}
