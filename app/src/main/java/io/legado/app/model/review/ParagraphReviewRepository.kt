package io.legado.app.model.review

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.review.wire.ReviewV1Parser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** 定义段评 v1 的三个只读网络操作。 */
interface ParagraphReviewRepository {

    /** 获取章节段评索引。 */
    suspend fun loadIndex(source: BookSource, request: ReviewIndexRequest): ReviewIndex

    /** 获取单段主评的一个 cursor 分页。 */
    suspend fun loadCommentPage(
        source: BookSource,
        request: ParagraphCommentPageRequest,
        usedCursors: Set<String> = emptySet(),
    ): ParagraphCommentPage

    /** 获取单条主评回复的一个 cursor 分页。 */
    suspend fun loadReplyPage(
        source: BookSource,
        request: ParagraphReplyPageRequest,
        usedCursors: Set<String> = emptySet(),
    ): ParagraphReplyPage
}

/** 抽象现有 AnalyzeUrl GET 链，便于纯 JVM 测试注入合成响应。 */
fun interface ReviewHttpExecutor {

    /** 使用书源配置和当前协程上下文执行只读 GET。 */
    suspend fun execute(
        url: String,
        source: BookSource,
        coroutineContext: CoroutineContext,
    ): StrResponse
}

/** 基于 AnalyzeUrl 执行严格 v1 合同解析的默认 repository。 */
class DefaultParagraphReviewRepository(
    private val httpExecutor: ReviewHttpExecutor = AnalyzeUrlReviewHttpExecutor,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ParagraphReviewRepository {

    /** 校验能力后获取章节段评索引。 */
    override suspend fun loadIndex(source: BookSource, request: ReviewIndexRequest): ReviewIndex {
        val rule = source.requireParagraphReviewRule()
        val values = ReviewTemplateValues(
            bookId = request.bookId,
            itemId = request.itemId,
            itemVersion = request.itemVersion,
        )
        return request(
            source = source,
            rule = rule,
            endpoint = ReviewEndpoint.INDEX,
            template = rule.reviewIndexUrl!!,
            values = values,
        ) { body ->
            ReviewV1Parser.parseIndex(body, request)
        }
    }

    /** 校验能力后获取单段主评 cursor 页。 */
    override suspend fun loadCommentPage(
        source: BookSource,
        request: ParagraphCommentPageRequest,
        usedCursors: Set<String>,
    ): ParagraphCommentPage {
        val rule = source.requireParagraphReviewRule()
        val values = ReviewTemplateValues(
            bookId = request.bookId,
            itemId = request.itemId,
            paraId = request.paraId,
            itemVersion = request.itemVersion,
            pageSize = request.pageSize,
            cursor = request.cursor,
        )
        return request(
            source = source,
            rule = rule,
            endpoint = ReviewEndpoint.COMMENT_PAGE,
            template = rule.reviewUrl!!,
            values = values,
        ) { body ->
            ReviewV1Parser.parseCommentPage(body, request, usedCursors)
        }
    }

    /** 校验能力后获取单条主评回复 cursor 页。 */
    override suspend fun loadReplyPage(
        source: BookSource,
        request: ParagraphReplyPageRequest,
        usedCursors: Set<String>,
    ): ParagraphReplyPage {
        val rule = source.requireParagraphReviewRule()
        val values = ReviewTemplateValues(
            bookId = request.bookId,
            itemId = request.itemId,
            commentId = request.commentId,
            pageSize = request.pageSize,
            cursor = request.cursor,
        )
        return request(
            source = source,
            rule = rule,
            endpoint = ReviewEndpoint.REPLY_PAGE,
            template = rule.reviewQuoteUrl!!,
            values = values,
        ) { body ->
            ReviewV1Parser.parseReplyPage(body, request, usedCursors)
        }
    }

    /** 通过现有请求链执行 GET 并统一处理状态、重定向和错误 envelope。 */
    private suspend fun <T> request(
        source: BookSource,
        rule: ReviewRule,
        endpoint: ReviewEndpoint,
        template: String,
        values: ReviewTemplateValues,
        parseSuccess: (String?) -> T,
    ): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        val transportPolicy = ReviewTransportPolicy.fromRule(rule)
        val safeUrl = ReviewTemplateExpander.expand(
            sourceUrl = source.bookSourceUrl,
            endpoint = endpoint,
            template = template,
            values = values,
            transportPolicy = transportPolicy,
        )
        val response = try {
            httpExecutor.execute(ReviewTemplateExpander.toAnalyzeUrlInput(safeUrl), source, context)
        } catch (_: IOException) {
            context.ensureActive()
            throw ReviewException.Network()
        }
        context.ensureActive()
        ReviewTemplateExpander.requireSameOrigin(
            source.bookSourceUrl,
            response.url,
            transportPolicy,
        )

        val status = response.code()
        if (status == 401) throw ReviewException.Authentication()
        if (status != 200) {
            val retryAfter = parseRetryAfter(
                value = response.headers()["Retry-After"],
                nowEpochMillis = nowEpochMillis(),
            )
            ReviewV1Parser.parseApiErrorOrNull(response.body, status, retryAfter)?.let { throw it }
            throw ReviewException.Http(
                status = status,
                retryable = status == 429 || status == 503 || status == 504,
                retryAfterSeconds = retryAfter.takeIf { status == 429 },
            )
        }
        return parseSuccess(response.body)
    }

    companion object {

        /** 解析合法的 Retry-After 秒数或 RFC 1123 HTTP-date。 */
        fun parseRetryAfter(value: String?, nowEpochMillis: Long): Long? {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return null
            text.toLongOrNull()?.let { seconds ->
                return seconds.takeIf { it >= 0L }
            }
            return try {
                val retryAtMillis = ZonedDateTime.parse(
                    text,
                    DateTimeFormatter.RFC_1123_DATE_TIME,
                ).toInstant().toEpochMilli()
                val deltaMillis = Math.subtractExact(retryAtMillis, nowEpochMillis)
                if (deltaMillis <= 0L) {
                    0L
                } else {
                    deltaMillis / 1000L + if (deltaMillis % 1000L == 0L) 0L else 1L
                }
            } catch (_: DateTimeParseException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
        }
    }
}

/** 使用已存在的 AnalyzeUrl 和书源配置执行评论 GET。 */
private object AnalyzeUrlReviewHttpExecutor : ReviewHttpExecutor {

    /** 把安全展开后的 URL 交给 AnalyzeUrl 且显式禁用 WebView。 */
    override suspend fun execute(
        url: String,
        source: BookSource,
        coroutineContext: CoroutineContext,
    ): StrResponse = AnalyzeUrl(
        mUrl = url,
        baseUrl = source.bookSourceUrl,
        source = source,
        coroutineContext = coroutineContext,
    ).getStrResponseAwait(useWebView = false)
}

/** 返回完整 v1 规则；无规则、未知或不完整规则直接短路。 */
private fun BookSource.requireParagraphReviewRule(): ReviewRule {
    val rule = ruleReview
    if (rule?.supportsParagraphCommentsV1() != true) {
        throw ReviewException.UnsupportedSource()
    }
    return rule
}
