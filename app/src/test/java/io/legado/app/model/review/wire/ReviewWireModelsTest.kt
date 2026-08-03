package io.legado.app.model.review.wire

import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ReviewWireModelsTest {

    private val expectedSchema = linkedMapOf(
        ReviewEnvelopeWire::class.java to linkedMapOf(
            "contract" to "contract",
            "code" to "code",
            "message" to "message",
            "data" to "data",
            "error" to "error",
        ),
        ReviewErrorWire::class.java to linkedMapOf(
            "type" to "type",
            "retryable" to "retryable",
            "parameter" to "parameter",
        ),
        ReviewWarningWire::class.java to linkedMapOf(
            "scope" to "scope",
            "type" to "type",
            "retryable" to "retryable",
            "paraId" to "para_id",
            "commentId" to "comment_id",
        ),
        ReviewIndexWire::class.java to linkedMapOf(
            "itemId" to "item_id",
            "bookId" to "book_id",
            "itemVersion" to "item_version",
            "paragraphs" to "paragraphs",
            "partial" to "partial",
            "warnings" to "warnings",
        ),
        ReviewParagraphWire::class.java to linkedMapOf(
            "paraId" to "para_id",
            "count" to "count",
            "hot" to "hot",
            "userCount" to "user_count",
            "detailLoaded" to "detail_loaded",
            "comments" to "comments",
        ),
        ParagraphCommentPageWire::class.java to linkedMapOf(
            "itemId" to "item_id",
            "bookId" to "book_id",
            "itemVersion" to "item_version",
            "paraId" to "para_id",
            "comments" to "comments",
            "total" to "total",
            "hasMore" to "has_more",
            "nextCursor" to "next_cursor",
        ),
        ParagraphCommentWire::class.java to linkedMapOf(
            "commentId" to "comment_id",
            "text" to "text",
            "images" to "images",
            "userId" to "user_id",
            "userName" to "user_name",
            "userAvatar" to "user_avatar",
            "createTimestamp" to "create_timestamp",
            "diggCount" to "digg_count",
            "replyCount" to "reply_count",
            "repliesLoaded" to "replies_loaded",
            "replies" to "replies",
            "replyTotal" to "reply_total",
            "replyHasMore" to "reply_has_more",
            "replyNextCursor" to "reply_next_cursor",
        ),
        ParagraphCommentImageWire::class.java to linkedMapOf(
            "url" to "url",
            "width" to "width",
            "height" to "height",
            "format" to "format",
        ),
        ParagraphReplyPageWire::class.java to linkedMapOf(
            "itemId" to "item_id",
            "bookId" to "book_id",
            "commentId" to "comment_id",
            "replies" to "replies",
            "total" to "total",
            "hasMore" to "has_more",
            "nextCursor" to "next_cursor",
        ),
        ParagraphReplyWire::class.java to linkedMapOf(
            "replyId" to "reply_id",
            "parentReplyId" to "parent_reply_id",
            "replyToCommentId" to "reply_to_comment_id",
            "replyToReplyId" to "reply_to_reply_id",
            "text" to "text",
            "images" to "images",
            "userId" to "user_id",
            "userName" to "user_name",
            "userAvatar" to "user_avatar",
            "replyToUserName" to "reply_to_user_name",
            "createTimestamp" to "create_timestamp",
            "diggCount" to "digg_count",
            "replyCount" to "reply_count",
            "children" to "children",
        ),
    )

    /** 验证全部 wire 实例字段都拥有唯一且准确的显式 JSON 名。 */
    @Test
    fun wireFields_declareCompleteSerializedNameSchema() {
        assertEquals("wire DTO 数量发生变化，请同步契约表", 10, expectedSchema.size)

        expectedSchema.forEach { (wireClass, expectedFields) ->
            val actualFields = wireClass.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .associateBy { it.name }
            assertEquals(
                "${wireClass.simpleName} 出现未登记字段或缺少合同字段",
                expectedFields.keys,
                actualFields.keys,
            )

            val serializedNames = actualFields.map { (fieldName, field) ->
                val annotation = field.getAnnotation(SerializedName::class.java)
                    ?: throw AssertionError("${wireClass.simpleName}.$fieldName 缺少 @SerializedName")
                assertEquals(
                    "${wireClass.simpleName}.$fieldName 的 JSON 名不符合合同",
                    expectedFields.getValue(fieldName),
                    annotation.value,
                )
                annotation.value
            }
            assertEquals(
                "${wireClass.simpleName} 存在重复 JSON 名",
                serializedNames.size,
                serializedNames.toSet().size,
            )
        }

        assertTrue("wire schema 不应为空", expectedSchema.values.any { it.isNotEmpty() })
    }
}
