package io.legado.app.model.review

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphReviewReaderStateTest {

    /** 验证全部失败只暴露固定诊断代码且不拼接动态敏感内容。 */
    @Test
    fun diagnostics_reduceAllErrorsToStableRedactedCategories() {
        val cases = listOf(
            ReviewException.UnsupportedSource() to
                ParagraphReviewDiagnostic.UNSUPPORTED_SOURCE,
            ReviewException.InvalidTemplate("https://secret.invalid/path?token=credential") to
                ParagraphReviewDiagnostic.INVALID_TEMPLATE,
            ReviewException.InvalidArgument("secret-cursor") to
                ParagraphReviewDiagnostic.INVALID_ARGUMENT,
            ReviewException.Authentication() to
                ParagraphReviewDiagnostic.AUTHENTICATION_ERROR,
            ReviewException.Network() to
                ParagraphReviewDiagnostic.NETWORK_ERROR,
            ReviewException.Http(
                status = 599,
                retryable = true,
                retryAfterSeconds = 123,
            ) to ParagraphReviewDiagnostic.HTTP_ERROR,
            ReviewException.Api(
                status = 598,
                type = "secret-response-body",
                retryable = false,
                parameter = "secret-id",
            ) to ParagraphReviewDiagnostic.API_ERROR,
            ReviewException.Protocol("secret-response-body") to
                ParagraphReviewDiagnostic.PROTOCOL_ERROR,
            IllegalStateException("secret-unknown-message") to
                ParagraphReviewDiagnostic.UNKNOWN_ERROR,
        )

        cases.forEach { (error, expected) ->
            val diagnostic = paragraphReviewDiagnosticFor(error)
            assertEquals(expected, diagnostic)
            val output = "paragraph_review:${diagnostic?.code}"
            listOf(
                "secret",
                "credential",
                "599",
                "598",
                "123",
            ).forEach { sensitiveValue ->
                assertFalse(output.contains(sensitiveValue))
            }
        }
        assertEquals(null, paragraphReviewDiagnosticFor(CancellationException("secret-cursor")))
    }

    /** 验证映射诊断只暴露固定分类而不包含动态映射内容。 */
    @Test
    fun diagnostics_reduceMappingsToStableCategories() {
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
