package io.legado.app.model.review

import io.legado.app.model.review.wire.ReviewParserCapabilities
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

    /** 验证严格图片合同映射纯图片主评、Long 尺寸与格式归一化。 */
    @Test
    fun parseCommentPage_mapsStrictImagesAndImageOnlyContent() {
        val body = commentPageJson().replace(
            "\"text\":\"合成主评\"",
            """"text":"","images":[
              {"url":"https://example.invalid/first.webp","width":4294967296,"height":1440,"format":" webp "},
              {"url":"http://example.invalid/second.jpg","width":0,"height":0}
            ]""".trimIndent(),
        )

        val page = ReviewV1Parser.parseCommentPage(
            body,
            ParagraphCommentPageRequest("1001", "2002", 12, "0", 20),
            capabilities = STRICT_IMAGES,
        )

        val comment = page.comments.single()
        assertEquals("", comment.text)
        assertEquals(2, comment.images.size)
        assertEquals(4_294_967_296L, comment.images.first().width)
        assertEquals("webp", comment.images.first().format)
        assertNull(comment.images.last().format)
    }

    /** 验证索引 eager 主评和回复分页任意 children 均保留图片。 */
    @Test
    fun parser_mapsImagesAcrossIndexAndNestedReplies() {
        val index = ReviewV1Parser.parseIndex(
            indexJson(
                paragraphs = """[{
                  "para_id":12,"count":1,"hot":"1","user_count":1,
                  "detail_loaded":true,"comments":[{
                    "comment_id":"3003","text":"主评","images":[
                      {"url":"https://example.invalid/index.png","width":10,"height":20}
                    ],"create_timestamp":1700000000,"digg_count":0,"reply_count":0,
                    "replies_loaded":true,"replies":[],"reply_total":0,"reply_has_more":false
                  }]
                }]""".trimIndent(),
            ),
            ReviewIndexRequest("1001", "2002", "0"),
            STRICT_IMAGES,
        )
        assertEquals("https://example.invalid/index.png", index.paragraphs.single()
            .comments.single().images.single().url)

        val replyBody = replyPageJson(nextCursor = "").replace(
            "\"text\":\"一级回复\"",
            """"text":"","images":[
              {"url":"https://example.invalid/root.png","width":20,"height":10,"format":"png"}
            ]""".trimIndent(),
        ).replace(
            "\"text\":\"二级回复\"",
            """"text":"二级回复","images":[
              {"url":"https://example.invalid/child.png","width":10,"height":10}
            ]""".trimIndent(),
        )
        val replyPage = ReviewV1Parser.parseReplyPage(
            replyBody,
            ParagraphReplyPageRequest("1001", "2002", "3003", 20),
            capabilities = STRICT_IMAGES,
        )
        assertEquals("", replyPage.replies.single().text)
        assertEquals("https://example.invalid/root.png", replyPage.replies.single().images.single().url)
        assertEquals(
            "https://example.invalid/child.png",
            replyPage.replies.single().children.single().images.single().url,
        )
    }

    /** 验证兼容模式把缺失或 null 图片字段统一为空列表。 */
    @Test
    fun parser_compatibilityMode_normalizesMissingAndNullImages() {
        val request = ParagraphCommentPageRequest("1001", "2002", 12, "0", 20)
        val missing = ReviewV1Parser.parseCommentPage(commentPageJson(), request)
        val explicitNull = ReviewV1Parser.parseCommentPage(
            commentPageJson().replace(
                "\"text\":\"合成主评\"",
                "\"text\":\"合成主评\",\"images\":null",
            ),
            request,
        )

        assertTrue(missing.comments.single().images.isEmpty())
        assertTrue(explicitNull.comments.single().images.isEmpty())
    }

    /** 验证兼容模式只放宽缺失值，已返回的图片数组仍执行完整校验。 */
    @Test
    fun parser_compatibilityMode_stillValidatesPresentImageArrays() {
        val request = ParagraphCommentPageRequest("1001", "2002", 12, "0", 20)
        val body = commentPageJson().withCommentImages(
            """[{"url":"/relative.png","width":1,"height":1}]"""
        )

        assertThrows(ReviewException.Protocol::class.java) {
            ReviewV1Parser.parseCommentPage(body, request)
        }
    }

    /** 验证严格模式拒绝缺失、null 和非数组图片字段。 */
    @Test
    fun parser_strictImages_rejectsMissingNullAndWrongShape() {
        val request = ParagraphCommentPageRequest("1001", "2002", 12, "0", 20)
        val invalidBodies = listOf(
            commentPageJson(),
            commentPageJson().withCommentImages("null"),
            commentPageJson().withCommentImages("{}"),
            commentPageJson().withCommentImages("[null]"),
            commentPageJson().withCommentImages("[1]"),
        )

        invalidBodies.forEach { body ->
            assertThrows(ReviewException.Protocol::class.java) {
                ReviewV1Parser.parseCommentPage(body, request, capabilities = STRICT_IMAGES)
            }
        }
    }

    /** 验证图片 URL、尺寸、数量与格式限制，并确保错误不泄露 URL。 */
    @Test
    fun parser_rejectsInvalidImageFieldsWithoutLeakingValues() {
        val request = ParagraphCommentPageRequest("1001", "2002", 12, "0", 20)
        val sensitiveUrl = "https://secret.example.invalid/private.png?token=secret"
        val tooMany = (0..50).joinToString(prefix = "[", postfix = "]") {
            """{"url":"https://example.invalid/$it.png","width":1,"height":1}"""
        }
        val invalidImages = listOf(
            """[{"url":"","width":1,"height":1}]""",
            """[{"url":"/relative.png","width":1,"height":1}]""",
            """[{"url":"ftp://example.invalid/a.png","width":1,"height":1}]""",
            """[{"url":"https://example.invalid/${"x".repeat(4096)}","width":1,"height":1}]""",
            """[{"url":"$sensitiveUrl","width":-1,"height":1}]""",
            """[{"url":"https://example.invalid/a.png","height":1}]""",
            """[{"url":"https://example.invalid/a.png","width":1.5,"height":1}]""",
            """[{"url":"https://example.invalid/a.png","width":1,"height":1,"format":"${"界".repeat(22)}"}]""",
            tooMany,
        )

        invalidImages.forEach { images ->
            val error = assertThrows(ReviewException.Protocol::class.java) {
                ReviewV1Parser.parseCommentPage(
                    commentPageJson().withCommentImages(images),
                    request,
                    capabilities = STRICT_IMAGES,
                )
            }
            assertFalse(error.message.orEmpty().contains(sensitiveUrl))
            assertFalse(error.message.orEmpty().contains("token=secret"))
        }
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

    /** 向合成主评响应插入指定 images JSON。 */
    private fun String.withCommentImages(images: String): String = replace(
        "\"text\":\"合成主评\"",
        "\"text\":\"合成主评\",\"images\":$images",
    )

    private companion object {
        val STRICT_IMAGES = ReviewParserCapabilities(requireImages = true)
    }
}
