package io.legado.app.model.review.wire

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import io.legado.app.model.review.PARAGRAPH_REVIEW_CONTRACT_V1
import io.legado.app.model.review.ParagraphComment
import io.legado.app.model.review.ParagraphCommentPage
import io.legado.app.model.review.ParagraphCommentPageRequest
import io.legado.app.model.review.ParagraphReply
import io.legado.app.model.review.ParagraphReplyPage
import io.legado.app.model.review.ParagraphReplyPageRequest
import io.legado.app.model.review.ReviewException
import io.legado.app.model.review.ReviewIndex
import io.legado.app.model.review.ReviewIndexRequest
import io.legado.app.model.review.ReviewParagraph
import io.legado.app.model.review.ReviewWarning
import io.legado.app.model.review.ReviewWarningScope
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.fromJsonObject

/** 严格解析并映射 fanqie 段评 v1 响应。 */
object ReviewV1Parser {

    private const val MAX_CURSOR_BYTES = 4096
    private const val MAX_REPLY_NODES = 4096
    private val decimalIdRegex = Regex("^[0-9]+$")
    private val knownErrorTypes = setOf(
        "invalid_argument",
        "method_not_allowed",
        "rate_limited",
        "upstream_not_ready",
        "upstream_unavailable",
        "upstream_business",
        "upstream_protocol",
        "upstream_timeout",
        "internal_error",
    )
    private val expectedErrorStatuses = mapOf(
        "invalid_argument" to 400,
        "method_not_allowed" to 405,
        "rate_limited" to 429,
        "upstream_not_ready" to 503,
        "upstream_unavailable" to 503,
        "upstream_business" to 503,
        "upstream_protocol" to 502,
        "upstream_timeout" to 504,
        "internal_error" to 500,
    )
    private val fixedRetryable = mapOf(
        "invalid_argument" to false,
        "method_not_allowed" to false,
        "rate_limited" to true,
        "upstream_not_ready" to true,
        "upstream_unavailable" to true,
        "upstream_protocol" to false,
        "upstream_timeout" to true,
        "internal_error" to false,
    )
    private val publicParameters = setOf(
        "item_id",
        "book_id",
        "item_version",
        "detail_limit",
        "comment_count",
        "include_replies",
        "reply_count",
        "para_id",
        "count",
        "cursor",
        "comment_id",
    )

    /** 解析并校验章节段评索引响应。 */
    fun parseIndex(body: String?, request: ReviewIndexRequest): ReviewIndex {
        val root = parseRoot(body)
        validateSuccessEnvelope(root)
        val dataObject = root.requiredObject("data", "data")
        validateIndexShape(dataObject)
        val envelope = decodeEnvelope<ReviewIndexWire>(root)
        val wire = envelope.data ?: protocol("data 缺失")
        requireIdentity("item_id", request.itemId, wire.itemId)
        requireIdentity("book_id", request.bookId, wire.bookId)
        requireIdentity("item_version", request.itemVersion, wire.itemVersion)

        val paragraphs = wire.paragraphs.orEmpty().mapIndexed { index, paragraph ->
            paragraph.toDomain("data.paragraphs[$index]")
        }
        if (paragraphs.zipWithNext().any { (left, right) -> left.paraId >= right.paraId }) {
            protocol("paragraphs 未按 para_id 严格升序")
        }
        val warnings = wire.warnings.orEmpty().mapIndexed { index, warning ->
            warning.toDomain("data.warnings[$index]")
        }
        validateWarnings(wire.partial == true, warnings, paragraphs)
        return ReviewIndex(
            itemId = wire.itemId!!,
            bookId = wire.bookId!!,
            itemVersion = wire.itemVersion!!,
            paragraphs = paragraphs,
            partial = wire.partial!!,
            warnings = warnings,
        )
    }

    /** 解析并校验单段主评分页响应。 */
    fun parseCommentPage(
        body: String?,
        request: ParagraphCommentPageRequest,
        usedCursors: Set<String> = emptySet(),
    ): ParagraphCommentPage {
        val root = parseRoot(body)
        validateSuccessEnvelope(root)
        val dataObject = root.requiredObject("data", "data")
        validateCommentPageShape(dataObject)
        val wire = decodeEnvelope<ParagraphCommentPageWire>(root).data
            ?: protocol("data 缺失")
        requireIdentity("item_id", request.itemId, wire.itemId)
        requireIdentity("book_id", request.bookId, wire.bookId)
        requireIdentity("item_version", request.itemVersion, wire.itemVersion)
        if (wire.paraId != request.paraId) protocol("para_id 与请求不一致")

        val comments = wire.comments.orEmpty().mapIndexed { index, comment ->
            comment.toDomain("data.comments[$index]")
        }
        val cursor = normalizeCursor(wire.hasMore!!, wire.nextCursor!!, request.cursor, usedCursors)
        return ParagraphCommentPage(
            itemId = wire.itemId!!,
            bookId = wire.bookId!!,
            itemVersion = wire.itemVersion!!,
            paraId = wire.paraId,
            comments = comments,
            total = wire.total!!,
            hasMore = wire.hasMore,
            nextCursor = cursor,
        )
    }

    /** 解析并校验单条主评回复分页响应。 */
    fun parseReplyPage(
        body: String?,
        request: ParagraphReplyPageRequest,
        usedCursors: Set<String> = emptySet(),
    ): ParagraphReplyPage {
        val root = parseRoot(body)
        validateSuccessEnvelope(root)
        val dataObject = root.requiredObject("data", "data")
        validateReplyPageShape(dataObject)
        val wire = decodeEnvelope<ParagraphReplyPageWire>(root).data
            ?: protocol("data 缺失")
        requireIdentity("item_id", request.itemId, wire.itemId)
        requireIdentity("book_id", request.bookId, wire.bookId)
        requireIdentity("comment_id", request.commentId, wire.commentId)

        val counter = intArrayOf(0)
        val replies = wire.replies.orEmpty().mapIndexed { index, reply ->
            reply.toDomain("data.replies[$index]", request.commentId, counter)
        }
        val cursor = normalizeCursor(wire.hasMore!!, wire.nextCursor!!, request.cursor, usedCursors)
        return ParagraphReplyPage(
            itemId = wire.itemId!!,
            bookId = wire.bookId!!,
            commentId = wire.commentId!!,
            replies = replies,
            total = wire.total!!,
            hasMore = wire.hasMore,
            nextCursor = cursor,
        )
    }

    /** 尝试把非成功响应映射为合同内 API 错误。 */
    fun parseApiErrorOrNull(
        body: String?,
        status: Int,
        retryAfterSeconds: Long? = null,
    ): ReviewException.Api? = runCatching {
        val root = parseRoot(body)
        validateErrorEnvelope(root)
        val envelope = decodeEnvelope<JsonObject>(root)
        val error = envelope.error ?: protocol("error 缺失")
        if (expectedErrorStatuses[error.type] != status) protocol("error.type 与 HTTP 状态不一致")
        ReviewException.Api(
            status = status,
            type = error.type!!,
            retryable = error.retryable!!,
            parameter = error.parameter!!,
            retryAfterSeconds = retryAfterSeconds.takeIf { status == 429 },
        )
    }.getOrNull()

    /** 将 JSON 文本严格解析为顶层对象。 */
    private fun parseRoot(body: String?): JsonObject =
        GSONStrict.fromJsonObject<JsonObject>(body).getOrElse {
            protocol("响应不是合法 JSON 对象")
        }

    /** 以保留泛型参数的方式把已校验对象解码为 wire envelope。 */
    private inline fun <reified T> decodeEnvelope(root: JsonObject): ReviewEnvelopeWire<T> {
        val type = object : TypeToken<ReviewEnvelopeWire<T>>() {}.type
        return runCatching { GSONStrict.fromJson<ReviewEnvelopeWire<T>>(root, type) }
            .getOrElse { protocol("响应字段无法映射") }
    }

    /** 校验成功 envelope 的固定字段和数据分支。 */
    private fun validateSuccessEnvelope(root: JsonObject) {
        if (root.requiredString("contract", "contract") != PARAGRAPH_REVIEW_CONTRACT_V1) {
            protocol("contract 不受支持")
        }
        if (root.requiredInt("code", "code") != 0) protocol("成功响应 code 非 0")
        if (root.requiredString("message", "message") != "SUCCESS") {
            protocol("成功响应 message 非 SUCCESS")
        }
        root.requiredObject("data", "data")
    }

    /** 校验错误 envelope 的固定字段和稳定分类。 */
    private fun validateErrorEnvelope(root: JsonObject) {
        if (root.requiredString("contract", "contract") != PARAGRAPH_REVIEW_CONTRACT_V1) {
            protocol("contract 不受支持")
        }
        if (root.requiredInt("code", "code") != -1) protocol("错误响应 code 非 -1")
        root.requiredString("message", "message")
        val error = root.requiredObject("error", "error")
        val type = error.requiredString("type", "error.type")
        if (type !in knownErrorTypes) protocol("error.type 未知")
        val retryable = error.requiredBoolean("retryable", "error.retryable")
        validateRetryable(type, retryable, "error.retryable")
        val parameter = error.requiredString("parameter", "error.parameter")
        if (type == "invalid_argument") {
            if (parameter !in publicParameters) protocol("error.parameter 未知")
        } else if (parameter.isNotEmpty()) {
            protocol("非参数错误不得包含 parameter")
        }
    }

    /** 校验章节索引 data 的必返 JSON 类型。 */
    private fun validateIndexShape(data: JsonObject) {
        data.requiredId("item_id", "data.item_id")
        data.requiredId("book_id", "data.book_id", allowEmpty = true)
        data.requiredString("item_version", "data.item_version")
        data.requiredArray("paragraphs", "data.paragraphs").forEachIndexed { index, element ->
            val paragraph = element.requiredObject("data.paragraphs[$index]")
            paragraph.requiredInt("para_id", "data.paragraphs[$index].para_id", nonNegative = true)
            paragraph.requiredInt("count", "data.paragraphs[$index].count", nonNegative = true)
            paragraph.requiredString("hot", "data.paragraphs[$index].hot")
            paragraph.requiredInt("user_count", "data.paragraphs[$index].user_count", nonNegative = true)
            paragraph.requiredBoolean("detail_loaded", "data.paragraphs[$index].detail_loaded")
            paragraph.requiredArray("comments", "data.paragraphs[$index].comments")
                .forEachIndexed { commentIndex, comment ->
                    validateCommentShape(
                        comment.requiredObject("data.paragraphs[$index].comments[$commentIndex]"),
                        "data.paragraphs[$index].comments[$commentIndex]",
                    )
                }
        }
        data.requiredBoolean("partial", "data.partial")
        data.requiredArray("warnings", "data.warnings").forEachIndexed { index, element ->
            val warning = element.requiredObject("data.warnings[$index]")
            warning.requiredString("scope", "data.warnings[$index].scope")
            warning.requiredString("type", "data.warnings[$index].type")
            warning.requiredBoolean("retryable", "data.warnings[$index].retryable")
            warning.optionalInt("para_id", "data.warnings[$index].para_id", nonNegative = true)
            warning.optionalId("comment_id", "data.warnings[$index].comment_id")
        }
    }

    /** 校验主评分页 data 的必返 JSON 类型。 */
    private fun validateCommentPageShape(data: JsonObject) {
        data.requiredId("item_id", "data.item_id")
        data.requiredId("book_id", "data.book_id")
        data.requiredString("item_version", "data.item_version")
        data.requiredInt("para_id", "data.para_id", nonNegative = true)
        data.requiredArray("comments", "data.comments").forEachIndexed { index, comment ->
            validateCommentShape(comment.requiredObject("data.comments[$index]"), "data.comments[$index]")
        }
        data.requiredInt("total", "data.total", nonNegative = true)
        data.requiredBoolean("has_more", "data.has_more")
        data.requiredString("next_cursor", "data.next_cursor")
    }

    /** 校验主评字段以及 eager 回复字段的一致性。 */
    private fun validateCommentShape(comment: JsonObject, path: String) {
        val commentId = comment.requiredId("comment_id", "$path.comment_id")
        comment.requiredString("text", "$path.text")
        comment.optionalId("user_id", "$path.user_id")
        comment.optionalString("user_name", "$path.user_name")
        comment.optionalString("user_avatar", "$path.user_avatar")
        comment.requiredLong("create_timestamp", "$path.create_timestamp", nonNegative = true)
        comment.requiredInt("digg_count", "$path.digg_count", nonNegative = true)
        comment.requiredInt("reply_count", "$path.reply_count", nonNegative = true)
        val repliesLoaded = comment.requiredBoolean("replies_loaded", "$path.replies_loaded")
        if (repliesLoaded) {
            val counter = intArrayOf(0)
            comment.requiredArray("replies", "$path.replies").forEachIndexed { index, reply ->
                validateReplyShape(
                    reply.requiredObject("$path.replies[$index]"),
                    "$path.replies[$index]",
                    commentId,
                    counter,
                )
            }
            comment.requiredInt("reply_total", "$path.reply_total", nonNegative = true)
            val hasMore = comment.requiredBoolean("reply_has_more", "$path.reply_has_more")
            val nextCursor = comment.optionalString("reply_next_cursor", "$path.reply_next_cursor")
            if (hasMore && nextCursor == null) protocol("$path.reply_next_cursor 缺失")
            normalizeCursor(hasMore, nextCursor.orEmpty(), null, emptySet())
        } else if (listOf("replies", "reply_total", "reply_has_more", "reply_next_cursor")
                .any(comment::has)
        ) {
            protocol("$path eager 回复字段不一致")
        }
    }

    /** 校验回复页 data 的必返 JSON 类型。 */
    private fun validateReplyPageShape(data: JsonObject) {
        data.requiredId("item_id", "data.item_id")
        data.requiredId("book_id", "data.book_id")
        val commentId = data.requiredId("comment_id", "data.comment_id")
        val counter = intArrayOf(0)
        data.requiredArray("replies", "data.replies").forEachIndexed { index, reply ->
            validateReplyShape(
                reply.requiredObject("data.replies[$index]"),
                "data.replies[$index]",
                commentId,
                counter,
            )
        }
        data.requiredInt("total", "data.total", nonNegative = true)
        data.requiredBoolean("has_more", "data.has_more")
        data.requiredString("next_cursor", "data.next_cursor")
    }

    /** 递归校验单条回复并限制单页节点总数。 */
    private fun validateReplyShape(
        reply: JsonObject,
        path: String,
        expectedCommentId: String,
        counter: IntArray,
    ) {
        counter[0]++
        if (counter[0] > MAX_REPLY_NODES) protocol("单页回复节点超过上限")
        reply.requiredId("reply_id", "$path.reply_id")
        reply.optionalId("parent_reply_id", "$path.parent_reply_id")
        reply.optionalId("reply_to_reply_id", "$path.reply_to_reply_id")
        reply.optionalId("user_id", "$path.user_id")
        val replyToCommentId = reply.optionalId("reply_to_comment_id", "$path.reply_to_comment_id")
        if (replyToCommentId != null && replyToCommentId != expectedCommentId) {
            protocol("$path.reply_to_comment_id 与主评不一致")
        }
        reply.requiredString("text", "$path.text")
        reply.optionalString("user_name", "$path.user_name")
        reply.optionalString("user_avatar", "$path.user_avatar")
        reply.optionalString("reply_to_user_name", "$path.reply_to_user_name")
        reply.requiredLong("create_timestamp", "$path.create_timestamp", nonNegative = true)
        reply.requiredInt("digg_count", "$path.digg_count", nonNegative = true)
        reply.requiredInt("reply_count", "$path.reply_count", nonNegative = true)
        reply.optionalArray("children", "$path.children")?.forEachIndexed { index, child ->
            validateReplyShape(
                child.requiredObject("$path.children[$index]"),
                "$path.children[$index]",
                expectedCommentId,
                counter,
            )
        }
    }

    /** 把段落 wire 数据映射为非空领域模型。 */
    private fun ReviewParagraphWire.toDomain(path: String): ReviewParagraph = ReviewParagraph(
        paraId = paraId ?: protocol("$path.para_id 缺失"),
        count = count ?: protocol("$path.count 缺失"),
        hot = hot ?: protocol("$path.hot 缺失"),
        userCount = userCount ?: protocol("$path.user_count 缺失"),
        detailLoaded = detailLoaded ?: protocol("$path.detail_loaded 缺失"),
        comments = comments.orEmpty().mapIndexed { index, comment ->
            comment.toDomain("$path.comments[$index]")
        },
    )

    /** 把主评 wire 数据映射为非空领域模型。 */
    private fun ParagraphCommentWire.toDomain(path: String): ParagraphComment {
        val commentId = commentId ?: protocol("$path.comment_id 缺失")
        val counter = intArrayOf(0)
        return ParagraphComment(
            commentId = commentId,
            text = text ?: protocol("$path.text 缺失"),
            userId = userId,
            userName = userName,
            userAvatar = userAvatar,
            createTimestamp = createTimestamp ?: protocol("$path.create_timestamp 缺失"),
            diggCount = diggCount ?: protocol("$path.digg_count 缺失"),
            replyCount = replyCount ?: protocol("$path.reply_count 缺失"),
            repliesLoaded = repliesLoaded ?: protocol("$path.replies_loaded 缺失"),
            replies = replies.orEmpty().mapIndexed { index, reply ->
                reply.toDomain("$path.replies[$index]", commentId, counter)
            },
            replyTotal = replyTotal,
            replyHasMore = replyHasMore,
            replyNextCursor = replyNextCursor?.takeIf { replyHasMore == true }.orEmpty()
                .takeIf { repliesLoaded == true },
        )
    }

    /** 把回复 wire 数据递归映射为非空领域模型。 */
    private fun ParagraphReplyWire.toDomain(
        path: String,
        expectedCommentId: String,
        counter: IntArray,
    ): ParagraphReply {
        counter[0]++
        if (counter[0] > MAX_REPLY_NODES) protocol("单页回复节点超过上限")
        if (replyToCommentId != null && replyToCommentId != expectedCommentId) {
            protocol("$path.reply_to_comment_id 与主评不一致")
        }
        return ParagraphReply(
            replyId = replyId ?: protocol("$path.reply_id 缺失"),
            parentReplyId = parentReplyId,
            replyToCommentId = replyToCommentId,
            replyToReplyId = replyToReplyId,
            text = text ?: protocol("$path.text 缺失"),
            userId = userId,
            userName = userName,
            userAvatar = userAvatar,
            replyToUserName = replyToUserName,
            createTimestamp = createTimestamp ?: protocol("$path.create_timestamp 缺失"),
            diggCount = diggCount ?: protocol("$path.digg_count 缺失"),
            replyCount = replyCount ?: protocol("$path.reply_count 缺失"),
            children = children.orEmpty().mapIndexed { index, child ->
                child.toDomain("$path.children[$index]", expectedCommentId, counter)
            },
        )
    }

    /** 把 warning wire 数据映射为稳定领域枚举。 */
    private fun ReviewWarningWire.toDomain(path: String): ReviewWarning {
        val mappedScope = when (scope) {
            "paragraph" -> ReviewWarningScope.PARAGRAPH
            "reply" -> ReviewWarningScope.REPLY
            else -> protocol("$path.scope 未知")
        }
        if (type !in knownErrorTypes) protocol("$path.type 未知")
        val retryableValue = retryable ?: protocol("$path.retryable 缺失")
        validateRetryable(type!!, retryableValue, "$path.retryable")
        return ReviewWarning(
            scope = mappedScope,
            type = type,
            retryable = retryableValue,
            paraId = paraId,
            commentId = commentId,
        )
    }

    /** 校验 partial 与 warning 身份范围完整一致。 */
    private fun validateWarnings(
        partial: Boolean,
        warnings: List<ReviewWarning>,
        paragraphs: List<ReviewParagraph>,
    ) {
        if (partial != warnings.isNotEmpty()) protocol("partial 与 warnings 不一致")
        val paragraphsById = paragraphs.associateBy(ReviewParagraph::paraId)
        warnings.forEach { warning ->
            val paraId = warning.paraId ?: protocol("warning 缺少 para_id")
            val paragraph = paragraphsById[paraId] ?: protocol("warning para_id 不在索引中")
            when (warning.scope) {
                ReviewWarningScope.PARAGRAPH -> {
                    if (warning.commentId != null) protocol("paragraph warning 不应包含 comment_id")
                    if (paragraph.detailLoaded) protocol("paragraph warning 与 detail_loaded 冲突")
                }

                ReviewWarningScope.REPLY -> {
                    val commentId = warning.commentId ?: protocol("reply warning 缺少 comment_id")
                    if (paragraph.comments.none { it.commentId == commentId }) {
                        protocol("reply warning comment_id 不在对应段落中")
                    }
                    if (paragraph.comments.first { it.commentId == commentId }.repliesLoaded) {
                        protocol("reply warning 与 replies_loaded 冲突")
                    }
                }
            }
        }
    }

    /** 规范化无下一页 cursor 并拒绝空、超长或重复的下一页 cursor。 */
    private fun normalizeCursor(
        hasMore: Boolean,
        nextCursor: String,
        requestCursor: String?,
        usedCursors: Set<String>,
    ): String {
        if (!hasMore) return ""
        if (nextCursor.isEmpty() || nextCursor.toByteArray(Charsets.UTF_8).size > MAX_CURSOR_BYTES) {
            protocol("next_cursor 无效")
        }
        if (nextCursor == requestCursor || nextCursor in usedCursors) {
            protocol("next_cursor 重复")
        }
        return nextCursor
    }

    /** 校验响应 identity 与请求值精确一致。 */
    private fun requireIdentity(name: String, expected: String, actual: String?) {
        if (actual != expected) protocol("$name 与请求不一致")
    }

    /** 校验固定错误分类没有篡改合同定义的 retryable。 */
    private fun validateRetryable(type: String, retryable: Boolean, path: String) {
        fixedRetryable[type]?.let { expected ->
            if (retryable != expected) protocol("$path 与 error.type 不一致")
        }
    }

    /** 读取必返对象字段并保留字段路径。 */
    private fun JsonObject.requiredObject(name: String, path: String): JsonObject =
        get(name)?.requiredObject(path) ?: protocol("$path 缺失")

    /** 将 JSON 元素校验为对象。 */
    private fun JsonElement.requiredObject(path: String): JsonObject =
        takeIf { isJsonObject }?.asJsonObject ?: protocol("$path 类型错误")

    /** 读取必返数组字段。 */
    private fun JsonObject.requiredArray(name: String, path: String): JsonArray =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: protocol("$path 缺失或类型错误")

    /** 读取可选数组字段。 */
    private fun JsonObject.optionalArray(name: String, path: String): JsonArray? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.takeIf { it.isJsonArray }?.asJsonArray ?: protocol("$path 类型错误")
    }

    /** 读取必返字符串字段。 */
    private fun JsonObject.requiredString(name: String, path: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: protocol("$path 缺失或类型错误")

    /** 读取可选字符串字段。 */
    private fun JsonObject.optionalString(name: String, path: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: protocol("$path 类型错误")
    }

    /** 读取必返十进制字符串 ID。 */
    private fun JsonObject.requiredId(name: String, path: String, allowEmpty: Boolean = false): String {
        val value = requiredString(name, path)
        if ((!allowEmpty || value.isNotEmpty()) && !decimalIdRegex.matches(value)) {
            protocol("$path 不是十进制字符串 ID")
        }
        return value
    }

    /** 读取可选十进制字符串 ID。 */
    private fun JsonObject.optionalId(name: String, path: String): String? {
        val value = optionalString(name, path) ?: return null
        if (!decimalIdRegex.matches(value)) protocol("$path 不是十进制字符串 ID")
        return value
    }

    /** 读取必返布尔字段。 */
    private fun JsonObject.requiredBoolean(name: String, path: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            ?: protocol("$path 缺失或类型错误")

    /** 读取必返 Int 字段并校验整数与范围。 */
    private fun JsonObject.requiredInt(
        name: String,
        path: String,
        nonNegative: Boolean = false,
    ): Int {
        val value = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asString?.toIntOrNull() ?: protocol("$path 缺失或类型错误")
        if (nonNegative && value < 0) protocol("$path 不能为负数")
        return value
    }

    /** 读取可选 Int 字段并校验整数与范围。 */
    private fun JsonObject.optionalInt(name: String, path: String, nonNegative: Boolean = false): Int? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        val value = element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asString?.toIntOrNull() ?: protocol("$path 类型错误")
        if (nonNegative && value < 0) protocol("$path 不能为负数")
        return value
    }

    /** 读取必返 Long 字段并校验整数与范围。 */
    private fun JsonObject.requiredLong(
        name: String,
        path: String,
        nonNegative: Boolean = false,
    ): Long {
        val value = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asString?.toLongOrNull() ?: protocol("$path 缺失或类型错误")
        if (nonNegative && value < 0L) protocol("$path 不能为负数")
        return value
    }

    /** 创建不含响应正文和值内容的稳定协议错误。 */
    private fun protocol(reason: String): Nothing = throw ReviewException.Protocol(reason)
}
