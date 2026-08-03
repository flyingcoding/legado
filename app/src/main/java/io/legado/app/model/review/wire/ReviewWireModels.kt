package io.legado.app.model.review.wire

import com.google.gson.annotations.SerializedName

/** 保存公共段评 API 的 nullable envelope。 */
data class ReviewEnvelopeWire<T>(
    @SerializedName("contract") val contract: String? = null,
    @SerializedName("code") val code: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("data") val data: T? = null,
    @SerializedName("error") val error: ReviewErrorWire? = null,
)

/** 保存公共段评 API 的 nullable 错误字段。 */
data class ReviewErrorWire(
    @SerializedName("type") val type: String? = null,
    @SerializedName("retryable") val retryable: Boolean? = null,
    @SerializedName("parameter") val parameter: String? = null,
)

/** 保存章节索引中的 nullable warning。 */
data class ReviewWarningWire(
    @SerializedName("scope") val scope: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("retryable") val retryable: Boolean? = null,
    @SerializedName("para_id") val paraId: Int? = null,
    @SerializedName("comment_id") val commentId: String? = null,
)

/** 保存章节段评索引的 nullable wire 数据。 */
data class ReviewIndexWire(
    @SerializedName("item_id") val itemId: String? = null,
    @SerializedName("book_id") val bookId: String? = null,
    @SerializedName("item_version") val itemVersion: String? = null,
    @SerializedName("paragraphs") val paragraphs: List<ReviewParagraphWire>? = null,
    @SerializedName("partial") val partial: Boolean? = null,
    @SerializedName("warnings") val warnings: List<ReviewWarningWire>? = null,
)

/** 保存单个段落摘要的 nullable wire 数据。 */
data class ReviewParagraphWire(
    @SerializedName("para_id") val paraId: Int? = null,
    @SerializedName("count") val count: Int? = null,
    @SerializedName("hot") val hot: String? = null,
    @SerializedName("user_count") val userCount: Int? = null,
    @SerializedName("detail_loaded") val detailLoaded: Boolean? = null,
    @SerializedName("comments") val comments: List<ParagraphCommentWire>? = null,
)

/** 保存单段主评分页的 nullable wire 数据。 */
data class ParagraphCommentPageWire(
    @SerializedName("item_id") val itemId: String? = null,
    @SerializedName("book_id") val bookId: String? = null,
    @SerializedName("item_version") val itemVersion: String? = null,
    @SerializedName("para_id") val paraId: Int? = null,
    @SerializedName("comments") val comments: List<ParagraphCommentWire>? = null,
    @SerializedName("total") val total: Int? = null,
    @SerializedName("has_more") val hasMore: Boolean? = null,
    @SerializedName("next_cursor") val nextCursor: String? = null,
)

/** 保存主评的 nullable wire 数据。 */
data class ParagraphCommentWire(
    @SerializedName("comment_id") val commentId: String? = null,
    @SerializedName("text") val text: String? = null,
    @SerializedName("images") val images: List<ParagraphCommentImageWire>? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("user_name") val userName: String? = null,
    @SerializedName("user_avatar") val userAvatar: String? = null,
    @SerializedName("create_timestamp") val createTimestamp: Long? = null,
    @SerializedName("digg_count") val diggCount: Int? = null,
    @SerializedName("reply_count") val replyCount: Int? = null,
    @SerializedName("replies_loaded") val repliesLoaded: Boolean? = null,
    @SerializedName("replies") val replies: List<ParagraphReplyWire>? = null,
    @SerializedName("reply_total") val replyTotal: Int? = null,
    @SerializedName("reply_has_more") val replyHasMore: Boolean? = null,
    @SerializedName("reply_next_cursor") val replyNextCursor: String? = null,
)

/** 保存公共段评 API 返回的 nullable 图片字段。 */
data class ParagraphCommentImageWire(
    @SerializedName("url") val url: String? = null,
    @SerializedName("width") val width: Long? = null,
    @SerializedName("height") val height: Long? = null,
    @SerializedName("format") val format: String? = null,
)

/** 保存回复分页的 nullable wire 数据。 */
data class ParagraphReplyPageWire(
    @SerializedName("item_id") val itemId: String? = null,
    @SerializedName("book_id") val bookId: String? = null,
    @SerializedName("comment_id") val commentId: String? = null,
    @SerializedName("replies") val replies: List<ParagraphReplyWire>? = null,
    @SerializedName("total") val total: Int? = null,
    @SerializedName("has_more") val hasMore: Boolean? = null,
    @SerializedName("next_cursor") val nextCursor: String? = null,
)

/** 保存单条回复及同页 children 的 nullable wire 数据。 */
data class ParagraphReplyWire(
    @SerializedName("reply_id") val replyId: String? = null,
    @SerializedName("parent_reply_id") val parentReplyId: String? = null,
    @SerializedName("reply_to_comment_id") val replyToCommentId: String? = null,
    @SerializedName("reply_to_reply_id") val replyToReplyId: String? = null,
    @SerializedName("text") val text: String? = null,
    @SerializedName("images") val images: List<ParagraphCommentImageWire>? = null,
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("user_name") val userName: String? = null,
    @SerializedName("user_avatar") val userAvatar: String? = null,
    @SerializedName("reply_to_user_name") val replyToUserName: String? = null,
    @SerializedName("create_timestamp") val createTimestamp: Long? = null,
    @SerializedName("digg_count") val diggCount: Int? = null,
    @SerializedName("reply_count") val replyCount: Int? = null,
    @SerializedName("children") val children: List<ParagraphReplyWire>? = null,
)
