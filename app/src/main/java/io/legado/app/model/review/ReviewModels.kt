package io.legado.app.model.review

/** 段评 v1 固定合同标识。 */
const val PARAGRAPH_REVIEW_CONTRACT_V1 = "fanqie.paragraph-comments.v1"

/** 描述段评请求所访问的只读端点。 */
enum class ReviewEndpoint(
    val path: String,
) {
    INDEX("/api/book/paragraph_comments"),
    COMMENT_PAGE("/api/book/paragraph_comment_page"),
    REPLY_PAGE("/api/book/paragraph_comment_replies"),
}

/** 保存章节段评索引及局部失败信息。 */
data class ReviewIndex(
    val itemId: String,
    val bookId: String,
    val itemVersion: String,
    val paragraphs: List<ReviewParagraph>,
    val partial: Boolean,
    val warnings: List<ReviewWarning>,
)

/** 保存单个服务端段落的评论摘要。 */
data class ReviewParagraph(
    val paraId: Int,
    val count: Int,
    val hot: String,
    val userCount: Int,
    val detailLoaded: Boolean,
    val comments: List<ParagraphComment>,
)

/** 保存章节索引中的脱敏局部失败。 */
data class ReviewWarning(
    val scope: ReviewWarningScope,
    val type: String,
    val retryable: Boolean,
    val paraId: Int?,
    val commentId: String?,
)

/** 限定章节索引允许返回的局部失败范围。 */
enum class ReviewWarningScope {
    PARAGRAPH,
    REPLY,
}

/** 保存严格校验后的主评展示数据。 */
data class ParagraphComment(
    val commentId: String,
    val text: String,
    val userId: String?,
    val userName: String?,
    val userAvatar: String?,
    val createTimestamp: Long,
    val diggCount: Int,
    val replyCount: Int,
    val repliesLoaded: Boolean,
    val replies: List<ParagraphReply>,
    val replyTotal: Int?,
    val replyHasMore: Boolean?,
    val replyNextCursor: String?,
)

/** 保存严格校验后的回复及其当前子树。 */
data class ParagraphReply(
    val replyId: String,
    val parentReplyId: String?,
    val replyToCommentId: String?,
    val replyToReplyId: String?,
    val text: String,
    val userId: String?,
    val userName: String?,
    val userAvatar: String?,
    val replyToUserName: String?,
    val createTimestamp: Long,
    val diggCount: Int,
    val replyCount: Int,
    val children: List<ParagraphReply>,
)

/** 保存单段主评的一个 cursor 分页。 */
data class ParagraphCommentPage(
    val itemId: String,
    val bookId: String,
    val itemVersion: String,
    val paraId: Int,
    val comments: List<ParagraphComment>,
    val total: Int,
    val hasMore: Boolean,
    val nextCursor: String,
)

/** 保存单条主评回复的一个 cursor 分页。 */
data class ParagraphReplyPage(
    val itemId: String,
    val bookId: String,
    val commentId: String,
    val replies: List<ParagraphReply>,
    val total: Int,
    val hasMore: Boolean,
    val nextCursor: String,
)

/** 描述章节索引请求的身份。 */
data class ReviewIndexRequest(
    val bookId: String,
    val itemId: String,
    val itemVersion: String = "0",
)

/** 描述单段主评分页请求的身份和游标。 */
data class ParagraphCommentPageRequest(
    val bookId: String,
    val itemId: String,
    val paraId: Int,
    val itemVersion: String = "0",
    val pageSize: Int = 20,
    val cursor: String? = null,
)

/** 描述单条主评回复页请求的身份和游标。 */
data class ParagraphReplyPageRequest(
    val bookId: String,
    val itemId: String,
    val commentId: String,
    val pageSize: Int = 20,
    val cursor: String? = null,
)
