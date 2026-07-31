package io.legado.app.model.review

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.help.http.StrResponse
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import org.junit.Assert.assertFalse

class ParagraphReviewRepositoryTest {

    /** 验证无规则、未知合同和不完整规则都在零网络调用前短路。 */
    @Test
    fun unsupportedRules_shortCircuitWithoutNetwork() {
        val calls = AtomicInteger()
        val repository = DefaultParagraphReviewRepository(
            httpExecutor = ReviewHttpExecutor { _, _, _ ->
                calls.incrementAndGet()
                response(200, indexJson())
            }
        )
        val sources = listOf(
            BookSource("https://fanqie.example.invalid", "none"),
            BookSource(
                "https://fanqie.example.invalid",
                "unknown",
                ruleReview = completeRule().copy(contractVersion = "fanqie.paragraph-comments.v2"),
            ),
            BookSource(
                "https://fanqie.example.invalid",
                "incomplete",
                ruleReview = ReviewRule(
                    contractVersion = ReviewRule.PARAGRAPH_COMMENTS_V1_CONTRACT,
                    reviewIndexUrl = "/api/book/paragraph_comments",
                ),
            ),
        )

        sources.forEach { source ->
            assertThrows(ReviewException.UnsupportedSource::class.java) {
                runBlocking {
                    repository.loadIndex(source, ReviewIndexRequest("1001", "2002"))
                }
            }
        }
        assertEquals(0, calls.get())
    }

    /** 验证完整规则只生成安全 GET URL 并映射索引成功响应。 */
    @Test
    fun loadIndex_expandsTemplateAndParsesSuccess() = runBlocking {
        var requestedUrl = ""
        val source = source()
        val repository = DefaultParagraphReviewRepository(
            httpExecutor = ReviewHttpExecutor { url, _, _ ->
                requestedUrl = url
                response(200, indexJson(), url)
            }
        )

        val index = repository.loadIndex(source, ReviewIndexRequest("1001", "2002"))

        assertEquals("2002", index.itemId)
        assertTrue(requestedUrl.startsWith("https://fanqie.example.invalid/api/book/paragraph_comments?"))
        assertTrue(requestedUrl.contains("detail_limit=0"))
    }

    /** 验证 repository 会把书源显式 debug-http 声明传入远程 HTTP 安全门禁。 */
    @Test
    fun loadIndex_allowsRemoteHttpOnlyWithExplicitDebugPolicy() = runBlocking {
        val calls = AtomicInteger()
        val repository = DefaultParagraphReviewRepository(
            httpExecutor = ReviewHttpExecutor { url, _, _ ->
                calls.incrementAndGet()
                response(200, indexJson(), url)
            }
        )
        val remoteHttpSource = source().copy(
            bookSourceUrl = "http://remote.example.invalid",
            ruleReview = completeRule().copy(
                transportPolicy = ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY
            ),
        )

        val index = repository.loadIndex(
            remoteHttpSource,
            ReviewIndexRequest("1001", "2002"),
        )

        assertEquals("2002", index.itemId)
        assertEquals(1, calls.get())
    }

    /** 验证远程 HTTP 缺少显式策略时在 HTTP executor 前被拒绝。 */
    @Test
    fun loadIndex_rejectsRemoteHttpBeforeExecutorWithoutOptIn() {
        val calls = AtomicInteger()
        val repository = DefaultParagraphReviewRepository(
            httpExecutor = ReviewHttpExecutor { _, _, _ ->
                calls.incrementAndGet()
                response(200, indexJson())
            }
        )
        val remoteHttpSource = source().copy(bookSourceUrl = "http://remote.example.invalid")

        assertThrows(ReviewException.InvalidTemplate::class.java) {
            runBlocking {
                repository.loadIndex(remoteHttpSource, ReviewIndexRequest("1001", "2002"))
            }
        }
        assertEquals(0, calls.get())
    }

    /** 验证 401 无需 contract 即映射认证错误且不解析响应正文。 */
    @Test
    fun loadIndex_mapsGlobal401BeforeEnvelopeParsing() {
        val repository = DefaultParagraphReviewRepository(
            httpExecutor = ReviewHttpExecutor { _, _, _ ->
                response(401, """{"message":"unauthorized"}""")
            }
        )
        assertThrows(ReviewException.Authentication::class.java) {
            runBlocking {
                repository.loadIndex(source(), ReviewIndexRequest("1001", "2002"))
            }
        }
    }

    /** 验证 429 合同错误保留合法 Retry-After 且不自动重试。 */
    @Test
    fun loadIndex_mapsRateLimitAndRetryAfter() {
        val calls = AtomicInteger()
        val repository = DefaultParagraphReviewRepository(
            httpExecutor = ReviewHttpExecutor { _, _, _ ->
                calls.incrementAndGet()
                response(
                    status = 429,
                    body = """{
                      "contract":"fanqie.paragraph-comments.v1","code":-1,"message":"限流",
                      "error":{"type":"rate_limited","retryable":true,"parameter":""}
                    }""".trimIndent(),
                    headers = Headers.Builder().add("Retry-After", "7").build(),
                )
            }
        )
        val error = assertThrows(ReviewException.Api::class.java) {
            runBlocking {
                repository.loadIndex(source(), ReviewIndexRequest("1001", "2002"))
            }
        }
        assertEquals(1, calls.get())
        assertEquals(429, error.status)
        assertEquals(7L, error.retryAfterSeconds)
        assertTrue(error.retryable)
    }

    /** 验证响应最终 URL 跨 origin 时在解析正文前拒绝。 */
    @Test
    fun loadIndex_rejectsCrossOriginFinalUrl() {
        val repository = DefaultParagraphReviewRepository(
            httpExecutor = ReviewHttpExecutor { _, _, _ ->
                response(
                    200,
                    indexJson(),
                    finalUrl = "https://other.example.invalid/api/book/paragraph_comments",
                )
            }
        )
        assertThrows(ReviewException.Protocol::class.java) {
            runBlocking {
                repository.loadIndex(source(), ReviewIndexRequest("1001", "2002"))
            }
        }
    }

    /** 验证网络传输异常映射为可重试错误且不暴露底层消息。 */
    @Test
    fun loadIndex_mapsTransportFailureWithoutSensitiveMessage() {
        val repository = DefaultParagraphReviewRepository(
            httpExecutor = ReviewHttpExecutor { _, _, _ ->
                throw IOException("secret cursor and response body")
            }
        )
        val error = assertThrows(ReviewException.Network::class.java) {
            runBlocking {
                repository.loadIndex(source(), ReviewIndexRequest("1001", "2002"))
            }
        }
        assertTrue(error.retryable)
        assertEquals("段评服务网络请求失败", error.message)
    }

    /** 验证 opaque 游标和版本中的规则前缀在进入 AnalyzeUrl 前被编码隔离。 */
    @Test
    fun loadCommentPage_encodesAnalyzeUrlRulePrefix() {
        val cursor = "opaque@js:java.put('secret','value')"
        val itemVersion = "version@js:java.put('secret','value')"
        var requestedUrl = ""
        val repository = DefaultParagraphReviewRepository(
            httpExecutor = ReviewHttpExecutor { url, _, _ ->
                requestedUrl = url
                throw IOException("synthetic")
            }
        )

        assertThrows(ReviewException.Network::class.java) {
            runBlocking {
                repository.loadCommentPage(
                    source(),
                    ParagraphCommentPageRequest(
                        "1001",
                        "2002",
                        12,
                        itemVersion = itemVersion,
                        cursor = cursor,
                    ),
                )
            }
        }

        assertFalse(requestedUrl.contains("@js:", ignoreCase = true))
        assertEquals(itemVersion, requestedUrl.toHttpUrl().queryParameter("item_version"))
        assertEquals(cursor, requestedUrl.toHttpUrl().queryParameter("cursor"))
    }

    /** 验证 Retry-After 支持非负秒数和 RFC 1123 日期。 */
    @Test
    fun parseRetryAfter_supportsSecondsAndHttpDate() {
        val now = Instant.parse("2026-07-30T00:00:00Z")
        val later = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            now.plusSeconds(9).atZone(ZoneOffset.UTC)
        )
        assertEquals(5L, DefaultParagraphReviewRepository.parseRetryAfter("5", now.toEpochMilli()))
        assertEquals(9L, DefaultParagraphReviewRepository.parseRetryAfter(later, now.toEpochMilli()))
        assertEquals(null, DefaultParagraphReviewRepository.parseRetryAfter("bad", now.toEpochMilli()))
        assertEquals(null, DefaultParagraphReviewRepository.parseRetryAfter("-1", now.toEpochMilli()))
    }

    /** 创建具备全部只读 v1 字段的书源。 */
    private fun source(): BookSource = BookSource(
        bookSourceUrl = "https://fanqie.example.invalid",
        bookSourceName = "test",
        ruleReview = completeRule(),
    )

    /** 创建具备能力判断全部必填字段的只读规则。 */
    private fun completeRule(): ReviewRule = ReviewRule(
        contractVersion = ReviewRule.PARAGRAPH_COMMENTS_V1_CONTRACT,
        reviewIndexUrl = "/api/book/paragraph_comments" +
            "?book_id={{bookId}}&item_id={{itemId}}&detail_limit=0",
        reviewUrl = "/api/book/paragraph_comment_page" +
            "?book_id={{bookId}}&item_id={{itemId}}&para_id={{paraId}}" +
            "&item_version={{itemVersion}}&count={{pageSize}}&cursor={{cursor}}",
        reviewQuoteUrl = "/api/book/paragraph_comment_replies" +
            "?book_id={{bookId}}&item_id={{itemId}}&comment_id={{commentId}}" +
            "&count={{pageSize}}&cursor={{cursor}}",
        paragraphListRule = "$.data.paragraphs",
        paragraphIdRule = "$.para_id",
        paragraphCountRule = "$.count",
        commentListRule = "$.data.comments",
        commentIdRule = "$.comment_id",
        contentRule = "$.text",
        postTimeRule = "$.create_timestamp",
        voteUpCountRule = "$.digg_count",
        quoteCountRule = "$.reply_count",
        hasMoreRule = "$.data.has_more",
        nextCursorRule = "$.data.next_cursor",
        quoteListRule = "$.data.replies",
        quoteIdRule = "$.reply_id",
        quoteContentRule = "$.text",
        quotePostTimeRule = "$.create_timestamp",
        quoteVoteUpCountRule = "$.digg_count",
    )

    /** 创建合成 StrResponse 并可指定最终 URL 与响应头。 */
    private fun response(
        status: Int,
        body: String,
        finalUrl: String = "https://fanqie.example.invalid/api/book/paragraph_comments",
        headers: Headers = Headers.Builder().build(),
    ): StrResponse {
        val raw = Response.Builder()
            .request(Request.Builder().url(finalUrl).build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("synthetic")
            .headers(headers)
            .build()
        return StrResponse(raw, body)
    }

    /** 创建章节索引成功 envelope。 */
    private fun indexJson(): String = """{
      "contract":"fanqie.paragraph-comments.v1","code":0,"message":"SUCCESS",
      "data":{
        "item_id":"2002","book_id":"1001","item_version":"0",
        "paragraphs":[],"partial":false,"warnings":[]
      }
    }""".trimIndent()
}
