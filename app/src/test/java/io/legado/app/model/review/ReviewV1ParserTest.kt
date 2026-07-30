package io.legado.app.model.review

import io.legado.app.model.review.wire.ReviewV1Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewV1ParserTest {

    /** 验证空索引和 partial warning 都映射为严格领域模型。 */
    @Test
    fun parseIndex_mapsEmptyAndPartialResponses() {
        val request = ReviewIndexRequest("1001", "2002", "0")
        val empty = ReviewV1Parser.parseIndex(indexJson(), request)
        assertTrue(empty.paragraphs.isEmpty())
        assertFalse(empty.partial)

        val partial = ReviewV1Parser.parseIndex(
            indexJson(
                paragraphs = """[
                    {
                      "para_id":12,"count":3,"hot":"1","user_count":3,
                      "detail_loaded":false,"comments":[]
                    }
                ]""".trimIndent(),
                partial = true,
                warnings = """[
                    {"scope":"paragraph","type":"upstream_timeout","retryable":true,"para_id":12}
                ]""".trimIndent(),
            ),
            request,
        )
        assertTrue(partial.partial)
        assertEquals(12, partial.paragraphs.single().paraId)
        assertEquals(ReviewWarningScope.PARAGRAPH, partial.warnings.single().scope)
    }

    /** 验证 eager 回复无下一页时允许省略 reply_next_cursor 并规范化为空串。 */
    @Test
    fun parseIndex_allowsMissingEagerCursorWhenComplete() {
        val index = ReviewV1Parser.parseIndex(
            indexJson(
                paragraphs = """[
                    {
                      "para_id":12,"count":1,"hot":"1","user_count":1,
                      "detail_loaded":true,"comments":[{
                        "comment_id":"3003","text":"合成主评","create_timestamp":1700000000,
                        "digg_count":0,"reply_count":0,"replies_loaded":true,
                        "replies":[],"reply_total":0,"reply_has_more":false
                      }]
                    }
                ]""".trimIndent(),
            ),
            ReviewIndexRequest("1001", "2002", "0"),
        )

        assertEquals("", index.paragraphs.single().comments.single().replyNextCursor)
    }

    /** 验证完整主评分页保留 String ID、Unix 秒和 opaque cursor。 */
    @Test
    fun parseCommentPage_mapsCompletePage() {
        val request = ParagraphCommentPageRequest("1001", "2002", 12, "0", 20)
        val page = ReviewV1Parser.parseCommentPage(commentPageJson(), request)

        assertEquals("3003", page.comments.single().commentId)
        assertEquals(1_700_000_000L, page.comments.single().createTimestamp)
        assertTrue(page.hasMore)
        assertEquals("opaque/+中文", page.nextCursor)
    }

    /** 验证回复树 wire 数据和无更多时的 cursor 规范化。 */
    @Test
    fun parseReplyPage_mapsNestedRepliesAndNormalizesCursor() {
        val request = ParagraphReplyPageRequest("1001", "2002", "3003", 20)
        val page = ReviewV1Parser.parseReplyPage(replyPageJson(nextCursor = "stale"), request)

        assertFalse(page.hasMore)
        assertEquals("", page.nextCursor)
        assertEquals("5005", page.replies.single().replyId)
        assertEquals("5006", page.replies.single().children.single().replyId)
    }

    /** 验证缺字段、类型错误、身份不一致和负计数均成为协议错误。 */
    @Test
    fun parser_rejectsMalformedSuccessMatrix() {
        val request = ParagraphCommentPageRequest("1001", "2002", 12, "0", 20)
        val invalidBodies = listOf(
            commentPageJson().replace("\"total\":1", "\"total\":\"1\""),
            commentPageJson().replace("\"comments\":[", "\"missing_comments\":["),
            commentPageJson().replace("\"item_id\":\"2002\"", "\"item_id\":\"9999\""),
            commentPageJson().replace("\"digg_count\":2", "\"digg_count\":-1"),
        )
        invalidBodies.forEach { body ->
            assertThrows(ReviewException.Protocol::class.java) {
                ReviewV1Parser.parseCommentPage(body, request)
            }
        }
    }

    /** 验证下一页 cursor 为空、重复或超长时停止分页。 */
    @Test
    fun parser_rejectsInvalidNextCursor() {
        val request = ParagraphCommentPageRequest(
            "1001",
            "2002",
            12,
            "0",
            20,
            cursor = "current",
        )
        val invalidCursors = listOf("", "current", "seen", "x".repeat(4097))
        invalidCursors.forEach { cursor ->
            assertThrows(ReviewException.Protocol::class.java) {
                ReviewV1Parser.parseCommentPage(
                    commentPageJson(nextCursor = cursor),
                    request,
                    usedCursors = setOf("seen"),
                )
            }
        }
    }

    /** 验证合法错误 envelope 被映射且畸形错误不泄露为 API 错误。 */
    @Test
    fun parseApiError_mapsOnlyStrictErrorEnvelope() {
        val error = ReviewV1Parser.parseApiErrorOrNull(
            """{
              "contract":"fanqie.paragraph-comments.v1","code":-1,"message":"限流",
              "error":{"type":"rate_limited","retryable":true,"parameter":""}
            }""".trimIndent(),
            status = 429,
            retryAfterSeconds = 3,
        )
        assertNotNull(error)
        assertEquals("rate_limited", error?.type)
        assertEquals(3L, error?.retryAfterSeconds)
        assertNull(
            ReviewV1Parser.parseApiErrorOrNull(
                """{"code":-1,"message":"bad"}""",
                status = 503,
            )
        )
        assertNull(
            ReviewV1Parser.parseApiErrorOrNull(
                """{
                  "contract":"fanqie.paragraph-comments.v1","code":-1,"message":"bad",
                  "error":{"type":"rate_limited","retryable":false,"parameter":"cursor-secret"}
                }""".trimIndent(),
                status = 503,
            )
        )
    }

    /** 创建章节索引合成响应。 */
    private fun indexJson(
        paragraphs: String = "[]",
        partial: Boolean = false,
        warnings: String = "[]",
    ): String = """{
      "contract":"fanqie.paragraph-comments.v1","code":0,"message":"SUCCESS",
      "data":{
        "item_id":"2002","book_id":"1001","item_version":"0",
        "paragraphs":$paragraphs,"partial":$partial,"warnings":$warnings
      }
    }""".trimIndent()

    /** 创建单段主评分页合成响应。 */
    private fun commentPageJson(nextCursor: String = "opaque/+中文"): String = """{
      "contract":"fanqie.paragraph-comments.v1","code":0,"message":"SUCCESS",
      "data":{
        "item_id":"2002","book_id":"1001","item_version":"0","para_id":12,
        "comments":[{
          "comment_id":"3003","text":"合成主评","user_id":"4004","user_name":"用户",
          "user_avatar":"https://cdn.example.invalid/a.png","create_timestamp":1700000000,
          "digg_count":2,"reply_count":1,"replies_loaded":false
        }],
        "total":1,"has_more":true,"next_cursor":"$nextCursor"
      }
    }""".trimIndent()

    /** 创建带同页 children 的回复分页合成响应。 */
    private fun replyPageJson(nextCursor: String): String = """{
      "contract":"fanqie.paragraph-comments.v1","code":0,"message":"SUCCESS",
      "data":{
        "item_id":"2002","book_id":"1001","comment_id":"3003",
        "replies":[{
          "reply_id":"5005","reply_to_comment_id":"3003","text":"一级回复",
          "create_timestamp":1700000001,"digg_count":1,"reply_count":1,
          "children":[{
            "reply_id":"5006","parent_reply_id":"5005","reply_to_comment_id":"3003",
            "reply_to_reply_id":"5005","text":"二级回复","create_timestamp":1700000002,
            "digg_count":0,"reply_count":0
          }]
        }],
        "total":2,"has_more":false,"next_cursor":"$nextCursor"
      }
    }""".trimIndent()
}
