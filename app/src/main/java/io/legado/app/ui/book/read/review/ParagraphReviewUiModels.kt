package io.legado.app.ui.book.read.review

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.legado.app.model.review.ParagraphComment
import io.legado.app.model.review.ParagraphCommentImage
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

/** 描述列表模式切换时是否需要保存旧位置并重新绑定目标 adapter。 */
internal data class ParagraphReviewListModeTransition(
    val modeChanged: Boolean,
    val saveCurrentState: Boolean,
)

/** 保存主评行和回复头部共用的只读展示字段。 */
internal data class ParagraphReviewCommentPresentation(
    val userName: String,
    val content: String,
    val time: String,
    val diggCount: Int,
    val replyCount: Int,
    val avatarUrl: String?,
    val images: List<ParagraphReviewImagePresentation>,
    val canOpenReplies: Boolean,
)

/** 保存经过 UI 安全过滤的段评图片。 */
internal data class ParagraphReviewImagePresentation(
    val url: String,
    val aspectRatio: Float,
)

/** 保存回复行的只读展示字段，回复目标仅来自显式服务端字段。 */
internal data class ParagraphReviewReplyPresentation(
    val userName: String,
    val replyToUserName: String?,
    val content: String,
    val time: String,
    val diggCount: Int,
    val replyCount: Int,
    val avatarUrl: String?,
    val images: List<ParagraphReviewImagePresentation>,
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

/** 只允许 HTTPS 段评图片地址进入 Glide。 */
internal fun safeParagraphReviewImageUrl(url: String?): String? {
    val parsed = url?.toHttpUrlOrNull() ?: return null
    return parsed.toString().takeIf { parsed.scheme == "https" }
}

/** 将图片尺寸映射为受限缩略图比例，零尺寸与异常值使用一比一。 */
internal fun paragraphReviewImageAspectRatio(width: Long, height: Long): Float {
    if (width <= 0L || height <= 0L) return DEFAULT_REVIEW_IMAGE_ASPECT_RATIO
    val ratio = width.toDouble() / height.toDouble()
    if (!ratio.isFinite()) return DEFAULT_REVIEW_IMAGE_ASPECT_RATIO
    return ratio.toFloat().coerceIn(MIN_REVIEW_IMAGE_ASPECT_RATIO, MAX_REVIEW_IMAGE_ASPECT_RATIO)
}

/** 过滤不安全地址并保持服务端图片顺序。 */
internal fun presentParagraphReviewImages(
    images: List<ParagraphCommentImage>,
): List<ParagraphReviewImagePresentation> = images.mapNotNull { image ->
    safeParagraphReviewImageUrl(image.url)?.let { safeUrl ->
        ParagraphReviewImagePresentation(
            url = safeUrl,
            aspectRatio = paragraphReviewImageAspectRatio(image.width, image.height),
        )
    }
}

/** 将主评投影为列表行与回复头部共用的安全只读字段。 */
internal fun presentParagraphReviewComment(
    comment: ParagraphComment,
    anonymousUser: String,
    unknownTime: String,
    repliesClickable: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ParagraphReviewCommentPresentation = ParagraphReviewCommentPresentation(
    userName = comment.userName?.takeIf(String::isNotBlank) ?: anonymousUser,
    content = comment.text,
    time = formatParagraphReviewTime(comment.createTimestamp, unknownTime, zoneId),
    diggCount = comment.diggCount,
    replyCount = comment.replyCount,
    avatarUrl = safeParagraphReviewAvatar(comment.userAvatar),
    images = presentParagraphReviewImages(comment.images),
    canOpenReplies = repliesClickable && comment.replyCount > 0,
)

/** 将回复投影为安全只读字段，不从正文、用户名或顺序推断回复目标。 */
internal fun presentParagraphReviewReply(
    reply: ParagraphReply,
    anonymousUser: String,
    unknownTime: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ParagraphReviewReplyPresentation = ParagraphReviewReplyPresentation(
    userName = reply.userName?.takeIf(String::isNotBlank) ?: anonymousUser,
    replyToUserName = reply.replyToUserName?.takeIf(String::isNotBlank),
    content = reply.text,
    time = formatParagraphReviewTime(reply.createTimestamp, unknownTime, zoneId),
    diggCount = reply.diggCount,
    replyCount = reply.replyCount,
    avatarUrl = safeParagraphReviewAvatar(reply.userAvatar),
    images = presentParagraphReviewImages(reply.images),
)

/** 区分视图首次绑定与运行中的模式切换，避免首次绑定覆盖已保存滚动位置。 */
internal fun paragraphReviewListModeTransition(
    currentRepliesMode: Boolean?,
    targetRepliesMode: Boolean,
): ParagraphReviewListModeTransition = ParagraphReviewListModeTransition(
    modeChanged = currentRepliesMode != targetRepliesMode,
    saveCurrentState = currentRepliesMode != null && currentRepliesMode != targetRepliesMode,
)

/** 以主评下一级为起点先序展开回复树，仅限制视觉缩进而不截断关系。 */
fun flattenParagraphReviewReplies(
    roots: List<ParagraphReply>,
    maxVisualDepth: Int = DEFAULT_MAX_REPLY_VISUAL_DEPTH,
): List<ParagraphReviewReplyListItem> {
    require(maxVisualDepth >= REPLY_CONTEXT_VISUAL_DEPTH) {
        "maxVisualDepth must include the comment context depth"
    }
    val result = ArrayList<ParagraphReviewReplyListItem>()
    val stack = ArrayDeque<Pair<ParagraphReply, Int>>()
    roots.asReversed().forEach { stack.addLast(it to REPLY_CONTEXT_VISUAL_DEPTH) }
    while (stack.isNotEmpty()) {
        val (reply, depth) = stack.removeLast()
        result += ParagraphReviewReplyListItem(reply, depth.coerceAtMost(maxVisualDepth))
        reply.children.asReversed().forEach { child -> stack.addLast(child to depth + 1) }
    }
    return result
}

private val REVIEW_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val REPLY_CONTEXT_VISUAL_DEPTH = 1
private const val DEFAULT_MAX_REPLY_VISUAL_DEPTH = 3
private const val DEFAULT_REVIEW_IMAGE_ASPECT_RATIO = 1f
private const val MIN_REVIEW_IMAGE_ASPECT_RATIO = 0.5f
private const val MAX_REVIEW_IMAGE_ASPECT_RATIO = 2f
