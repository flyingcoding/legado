package io.legado.app.model.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphReviewReaderStateTest {

    /** 验证阅读页诊断只暴露稳定分类，不拼接异常或映射动态内容。 */
    @Test
    fun diagnostics_reduceErrorsAndMappingsToStableCategories() {
        assertEquals(
            ParagraphReviewDiagnostic.TRANSPORT_REJECTED,
            paragraphReviewDiagnosticFor(
                ReviewException.InvalidTemplate("sensitive-url-must-not-appear")
            ),
        )
        assertEquals(
            ParagraphReviewDiagnostic.MAPPING_UNAVAILABLE,
            paragraphReviewDiagnosticFor(
                ParagraphReviewMappingResult.Unavailable(
                    ParagraphReviewMappingUnavailableReason.NO_VERIFIED_MAPPER
                )
            ),
        )
        assertEquals(
            ParagraphReviewDiagnostic.MAPPING_INVALID,
            paragraphReviewDiagnosticFor(
                ParagraphReviewMappingResult.Invalid(
                    ParagraphReviewMappingInvalidReason.COUNT_MISMATCH
                )
            ),
        )
        assertEquals(null, paragraphReviewDiagnosticFor(ReviewException.Network()))
    }

    /** 验证快速切换三个章节时只有最后 generation 能提交。 */
    @Test
    fun reducer_acceptsOnlyLatestGeneration() {
        val reducer = ParagraphReviewGenerationReducer()
        val first = reducer.begin("source", "book", 0, "100", "hash-0")
        val second = reducer.begin("source", "book", 1, "101", "hash-1")
        val third = reducer.begin("source", "book", 2, "102", "hash-2")

        assertFalse(reducer.commit(first, ReviewIndexLoadState.Error(retryable = true)))
        assertFalse(reducer.commit(second, ReviewIndexLoadState.Error(retryable = true)))
        assertTrue(reducer.commit(third, ReviewIndexLoadState.Error(retryable = false)))
        assertEquals(3L, reducer.state.generation?.token)
        assertEquals(ReviewIndexLoadState.Error(false), reducer.state.indexState)
    }

    /** 验证关闭开关会递增 token、清空状态并拒绝旧点击和结果。 */
    @Test
    fun invalidate_rejectsInFlightCommitAndClick() {
        val reducer = ParagraphReviewGenerationReducer(initialToken = 8)
        val generation = reducer.begin("source", "book", 0, "100", "hash")
        assertTrue(reducer.acceptsClick(generation.token))

        val invalidatedToken = reducer.invalidate()

        assertEquals(10L, invalidatedToken)
        assertFalse(reducer.acceptsClick(generation.token))
        assertFalse(reducer.commit(generation, ReviewIndexLoadState.Error(true)))
        assertEquals(ParagraphReviewReaderState(), reducer.state)
    }
}
