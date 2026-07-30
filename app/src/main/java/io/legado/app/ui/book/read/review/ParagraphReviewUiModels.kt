package io.legado.app.ui.book.read.review

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.legado.app.model.review.ParagraphComment
import io.legado.app.model.review.ParagraphReply
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/** 描述主评列表可直接渲染的分页状态。 */
data class ParagraphReviewCommentUiState(
    val items: List<ParagraphComment> = emptyList(),
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val partial: Boolean = false,
    val error: ParagraphReviewUiError? = null,
)

/** 描述回复树中的一个可渲染节点及受限视觉层级。 */
data class ParagraphReviewReplyListItem(
    val reply: ParagraphReply,
    val visualDepth: Int,
)

/** 描述当前选中主评及其回复分页状态。 */
data class ParagraphReviewReplyUiState(
    val comment: ParagraphComment? = null,
    val items: List<ParagraphReviewReplyListItem> = emptyList(),
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: ParagraphReviewUiError? = null,
)

/** 保存脱敏错误分类和手动重试能力。 */
data class ParagraphReviewUiError(
    val kind: ParagraphReviewUiErrorKind,
    val retryable: Boolean,
)

/** 限定 UI 可见的稳定错误分类，避免展示上游响应正文。 */
enum class ParagraphReviewUiErrorKind {
    AUTHENTICATION,
    NETWORK,
    PROTOCOL,
    GENERIC,
}

/** 把 Unix 秒格式化为本地时间；0 或负数返回调用方提供的未知占位。 */
fun formatParagraphReviewTime(
    timestampSeconds: Long,
    unknown: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    if (timestampSeconds <= 0L) return unknown
    return runCatching {
        REVIEW_TIME_FORMATTER.format(Instant.ofEpochSecond(timestampSeconds).atZone(zoneId))
    }.getOrDefault(unknown)
}

/** 只允许 HTTPS 头像地址进入 Glide，其他地址统一使用本地占位。 */
fun safeParagraphReviewAvatar(url: String?): String? {
    val parsed = url?.toHttpUrlOrNull() ?: return null
    return parsed.toString().takeIf { parsed.scheme == "https" }
}

/** 以先序遍历展开完整回复树，仅限制视觉缩进而不截断内部关系。 */
fun flattenParagraphReviewReplies(
    roots: List<ParagraphReply>,
    maxVisualDepth: Int = DEFAULT_MAX_REPLY_VISUAL_DEPTH,
): List<ParagraphReviewReplyListItem> {
    require(maxVisualDepth >= 0) { "maxVisualDepth must not be negative" }
    val result = ArrayList<ParagraphReviewReplyListItem>()
    val stack = ArrayDeque<Pair<ParagraphReply, Int>>()
    roots.asReversed().forEach { stack.addLast(it to 0) }
    while (stack.isNotEmpty()) {
        val (reply, depth) = stack.removeLast()
        result += ParagraphReviewReplyListItem(reply, depth.coerceAtMost(maxVisualDepth))
        reply.children.asReversed().forEach { child -> stack.addLast(child to depth + 1) }
    }
    return result
}

private val REVIEW_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val DEFAULT_MAX_REPLY_VISUAL_DEPTH = 3
