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
    val total: Int = 0,
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

/** 描述点击主评回复入口后应忽略还是切换到对应主评。 */
internal enum class ParagraphReviewReplyToggleAction {
    IGNORE,
    EXPAND,
}

/** 限定内联回复列表末尾当前可执行的用户动作。 */
enum class ParagraphReviewReplyFooterAction {
    NONE,
    REVEAL_MORE,
    COLLAPSE,
}

/** 保存已加载回复到当前可见批次之间的纯窗口计算结果。 */
internal data class ParagraphReviewReplyWindow(
    val visibleCount: Int,
    val nextBatchSize: Int,
    val footerAction: ParagraphReviewReplyFooterAction,
    val shouldLoadMore: Boolean,
    val terminalInconsistent: Boolean = false,
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
    val loadedCount: Int = 0,
    val total: Int = 0,
    val visibleLimit: Int = 0,
    val nextBatchSize: Int = 0,
    val footerAction: ParagraphReviewReplyFooterAction =
        ParagraphReviewReplyFooterAction.NONE,
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

/** 保留 HTTPS 头像并只升级合法 HTTP 地址的 scheme，其他地址回退占位。 */
fun safeParagraphReviewAvatar(url: String?): String? {
    val parsed = url?.toHttpUrlOrNull() ?: return null
    return when (parsed.scheme) {
        "https" -> parsed.toString()
        "http" -> parsed.newBuilder().scheme("https").build().toString()
        else -> null
    }
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

/** 以服务端分页 total 覆盖索引计数，分页尚未成功时保留索引计数。 */
internal fun resolveParagraphReviewCommentTotal(indexCount: Int, serverTotal: Int?): Int =
    (serverTotal ?: indexCount).coerceAtLeast(0)

/** 根据当前选中主评和点击目标生成单展开项的 toggle 动作。 */
internal fun paragraphReviewReplyToggleAction(
    selectedCommentId: String?,
    clickedCommentId: String,
    replyCount: Int,
): ParagraphReviewReplyToggleAction = when {
    replyCount <= 0 -> ParagraphReviewReplyToggleAction.IGNORE
    selectedCommentId == clickedCommentId -> ParagraphReviewReplyToggleAction.IGNORE
    else -> ParagraphReviewReplyToggleAction.EXPAND
}

/** 返回每条主评第一次展开时固定使用的三条可见上限。 */
internal fun initialParagraphReviewReplyVisibleLimit(): Int = INITIAL_REPLY_VISIBLE_LIMIT

/** 按 footer 声明的实际批次数推进可见上限，且单次最多增加十条。 */
internal fun advanceParagraphReviewReplyVisibleLimit(
    currentLimit: Int,
    requestedBatchSize: Int = REPLY_VISIBLE_BATCH_SIZE,
): Int = currentLimit.coerceAtLeast(0).let { safeLimit ->
    val safeBatchSize = requestedBatchSize.coerceIn(0, REPLY_VISIBLE_BATCH_SIZE)
    if (safeLimit > Int.MAX_VALUE - safeBatchSize) Int.MAX_VALUE
    else safeLimit + safeBatchSize
}

/** 检测声明还有下一页、但合并后没有新增可见节点的停滞分页。 */
internal fun isParagraphReviewReplyPageStalled(
    previousLoadedCount: Int,
    loadedCount: Int,
    serverHasMore: Boolean,
): Boolean = serverHasMore && loadedCount <= previousLoadedCount.coerceAtLeast(0)

/** 区分已加载隐藏项、远端下一页和全部可见后的 footer 动作。 */
internal fun paragraphReviewReplyWindow(
    loadedCount: Int,
    serverTotal: Int,
    serverHasMore: Boolean,
    visibleLimit: Int,
): ParagraphReviewReplyWindow {
    val safeLoadedCount = loadedCount.coerceAtLeast(0)
    val safeServerTotal = serverTotal.coerceAtLeast(0)
    val safeVisibleLimit = visibleLimit.coerceAtLeast(0)
    val visibleCount = minOf(safeLoadedCount, safeVisibleLimit)
    val allLoadedRepliesVisible = visibleCount == safeLoadedCount
    if (!serverHasMore && allLoadedRepliesVisible && safeLoadedCount < safeServerTotal) {
        return ParagraphReviewReplyWindow(
            visibleCount = visibleCount,
            nextBatchSize = 0,
            footerAction = ParagraphReviewReplyFooterAction.NONE,
            shouldLoadMore = false,
            terminalInconsistent = true,
        )
    }
    val allServerRepliesVisible = !serverHasMore && allLoadedRepliesVisible
    if (allServerRepliesVisible) {
        return ParagraphReviewReplyWindow(
            visibleCount = visibleCount,
            nextBatchSize = 0,
            footerAction = ParagraphReviewReplyFooterAction.COLLAPSE,
            shouldLoadMore = false,
        )
    }
    val nextBatchSize = if (serverHasMore) {
        val knownTotal = maxOf(safeServerTotal, safeLoadedCount)
        val knownRemaining = (knownTotal - visibleCount).coerceAtLeast(0)
        if (knownRemaining > 0) minOf(REPLY_VISIBLE_BATCH_SIZE, knownRemaining)
        else REPLY_VISIBLE_BATCH_SIZE
    } else {
        minOf(REPLY_VISIBLE_BATCH_SIZE, safeLoadedCount - visibleCount)
    }
    return ParagraphReviewReplyWindow(
        visibleCount = visibleCount,
        nextBatchSize = nextBatchSize,
        footerAction = ParagraphReviewReplyFooterAction.REVEAL_MORE,
        shouldLoadMore = serverHasMore && safeVisibleLimit > safeLoadedCount,
    )
}

/** 同时校验请求 epoch 与主评归属，拒绝旧请求提交到新展开项。 */
internal fun canCommitParagraphReviewReplyResult(
    loadEpoch: Long,
    currentEpoch: Long,
    loadedCommentId: String,
    selectedCommentId: String?,
): Boolean = loadEpoch == currentEpoch && loadedCommentId == selectedCommentId

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
private const val INITIAL_REPLY_VISIBLE_LIMIT = 3
private const val REPLY_VISIBLE_BATCH_SIZE = 10
private const val DEFAULT_REVIEW_IMAGE_ASPECT_RATIO = 1f
private const val MIN_REVIEW_IMAGE_ASPECT_RATIO = 0.5f
private const val MAX_REVIEW_IMAGE_ASPECT_RATIO = 2f
