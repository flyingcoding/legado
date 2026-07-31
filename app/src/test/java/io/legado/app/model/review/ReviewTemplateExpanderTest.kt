package io.legado.app.model.review

import io.legado.app.data.entities.rule.ReviewRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTemplateExpanderTest {

    /** 验证自由文本变量由 HttpUrl 编码并能无损还原。 */
    @Test
    fun expand_encodesNonAsciiAndReservedValues() {
        val itemVersion = "章节/版本 ?&=中文"
        val cursor = "游标/+?&=中文"
        val commentUrl = ReviewTemplateExpander.expand(
            sourceUrl = "https://fanqie.example.invalid",
            endpoint = ReviewEndpoint.COMMENT_PAGE,
            template = "/api/book/paragraph_comment_page" +
                "?book_id={{bookId}}&item_id={{itemId}}&para_id={{paraId}}" +
                "&item_version={{itemVersion}}&count={{pageSize}}&cursor={{cursor}}",
            values = ReviewTemplateValues(
                bookId = "1001",
                itemId = "2002",
                paraId = 12,
                itemVersion = itemVersion,
                pageSize = 20,
                cursor = cursor,
            ),
        )

        assertEquals("1001", commentUrl.queryParameter("book_id"))
        assertEquals("2002", commentUrl.queryParameter("item_id"))
        assertEquals("12", commentUrl.queryParameter("para_id"))
        assertEquals(itemVersion, commentUrl.queryParameter("item_version"))
        assertEquals("20", commentUrl.queryParameter("count"))
        assertEquals(cursor, commentUrl.queryParameter("cursor"))
        assertFalse(commentUrl.toString().contains("中文"))
    }

    /** 验证主评和回复第一页都会删除整个 cursor query 参数。 */
    @Test
    fun expand_removesCursorForFirstPage() {
        val commentUrl = ReviewTemplateExpander.expand(
            "https://fanqie.example.invalid",
            ReviewEndpoint.COMMENT_PAGE,
            "/api/book/paragraph_comment_page" +
                "?book_id={{bookId}}&item_id={{itemId}}&para_id={{paraId}}" +
                "&item_version={{itemVersion}}&count={{pageSize}}&cursor={{cursor}}",
            ReviewTemplateValues("1", "2", 3, "0", pageSize = 20),
        )
        val replyUrl = ReviewTemplateExpander.expand(
            "https://fanqie.example.invalid",
            ReviewEndpoint.REPLY_PAGE,
            "/api/book/paragraph_comment_replies" +
                "?book_id={{bookId}}&item_id={{itemId}}&comment_id={{commentId}}" +
                "&count={{pageSize}}&cursor={{cursor}}",
            ReviewTemplateValues("1", "2", commentId = "4", pageSize = 20),
        )

        assertFalse("cursor" in commentUrl.queryParameterNames)
        assertFalse("cursor" in replyUrl.queryParameterNames)
    }

    /** 验证未知变量、重复参数、跨域和非本机 HTTP 均在请求前拒绝。 */
    @Test
    fun expand_rejectsUnsafeTemplates() {
        val values = ReviewTemplateValues(bookId = "1", itemId = "2")
        val templates = listOf(
            "/api/book/paragraph_comments?book_id={{bookId}}&item_id={{unknown}}&detail_limit=0",
            "/api/book/paragraph_comments?book_id={{bookId}}&item_id={{itemId}}" +
                "&item_id=2&detail_limit=0",
            "https://other.example.invalid/api/book/paragraph_comments" +
                "?book_id={{bookId}}&item_id={{itemId}}&detail_limit=0",
            "/api/book/paragraph_comments?book_id={{bookId}}&item_id={{itemId}}" +
                "&detail_limit=0,{\"method\":\"POST\"}",
            "/api/book/paragraph_comments?book_id={{bookId}}&item_id={{itemId}}" +
                "&detail_limit=0,{js:'cross-origin'}",
        )
        templates.forEach { template ->
            assertThrows(ReviewException.InvalidTemplate::class.java) {
                ReviewTemplateExpander.expand(
                    "https://fanqie.example.invalid",
                    ReviewEndpoint.INDEX,
                    template,
                    values,
                )
            }
        }
        assertThrows(ReviewException.InvalidTemplate::class.java) {
            ReviewTemplateExpander.expand(
                "http://192.168.1.2",
                ReviewEndpoint.INDEX,
                "/api/book/paragraph_comments" +
                    "?book_id={{bookId}}&item_id={{itemId}}&detail_limit=0",
                values,
            )
        }
    }

    /** 验证本机调试 HTTP 可用但响应重定向仍必须同源。 */
    @Test
    fun origin_allowsLocalHttpAndRejectsCrossOriginResponse() {
        val url = ReviewTemplateExpander.expand(
            "http://127.0.0.1:8080",
            ReviewEndpoint.INDEX,
            "/api/book/paragraph_comments" +
                "?book_id={{bookId}}&item_id={{itemId}}&detail_limit=0",
            ReviewTemplateValues(bookId = "1", itemId = "2"),
        )
        assertEquals("127.0.0.1", url.host)
        assertThrows(ReviewException.Protocol::class.java) {
            ReviewTemplateExpander.requireSameOrigin(
                "https://fanqie.example.invalid",
                "https://other.example.invalid/api/book/paragraph_comments",
            )
        }
    }

    /** 验证远程 HTTP 仅在 debug 且书源显式声明时覆盖三个只读端点。 */
    @Test
    fun transportPolicy_allowsExplicitDebugRemoteHttpForAllReadOnlyEndpoints() {
        val policy = ReviewTransportPolicy(
            isDebugBuild = true,
            declaredPolicy = ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY,
        )
        val cases = listOf(
            Triple(
                ReviewEndpoint.INDEX,
                "/api/book/paragraph_comments?book_id={{bookId}}&item_id={{itemId}}&detail_limit=0",
                ReviewTemplateValues(bookId = "1", itemId = "2"),
            ),
            Triple(
                ReviewEndpoint.COMMENT_PAGE,
                "/api/book/paragraph_comment_page?book_id={{bookId}}&item_id={{itemId}}" +
                    "&para_id={{paraId}}&item_version={{itemVersion}}" +
                    "&count={{pageSize}}&cursor={{cursor}}",
                ReviewTemplateValues("1", "2", 3, "0", pageSize = 20),
            ),
            Triple(
                ReviewEndpoint.REPLY_PAGE,
                "/api/book/paragraph_comment_replies?book_id={{bookId}}&item_id={{itemId}}" +
                    "&comment_id={{commentId}}&count={{pageSize}}&cursor={{cursor}}",
                ReviewTemplateValues("1", "2", commentId = "4", pageSize = 20),
            ),
        )

        cases.forEach { (endpoint, template, values) ->
            val url = ReviewTemplateExpander.expand(
                sourceUrl = "http://remote.example.invalid",
                endpoint = endpoint,
                template = template,
                values = values,
                transportPolicy = policy,
            )
            assertEquals("http", url.scheme)
            ReviewTemplateExpander.requireSameOrigin(
                "http://remote.example.invalid",
                url.toString(),
                policy,
            )
        }
    }

    /** 验证缺少声明、未知声明和 release 构建都在远程 HTTP 请求前拒绝。 */
    @Test
    fun transportPolicy_rejectsRemoteHttpWithoutExactDebugOptIn() {
        val policies = listOf(
            ReviewTransportPolicy(isDebugBuild = true, declaredPolicy = null),
            ReviewTransportPolicy(isDebugBuild = true, declaredPolicy = "future-policy"),
            ReviewTransportPolicy(
                isDebugBuild = false,
                declaredPolicy = ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY,
            ),
        )

        policies.forEach { policy ->
            assertThrows(ReviewException.InvalidTemplate::class.java) {
                ReviewTemplateExpander.expand(
                    sourceUrl = "http://remote.example.invalid",
                    endpoint = ReviewEndpoint.INDEX,
                    template = "/api/book/paragraph_comments" +
                        "?book_id={{bookId}}&item_id={{itemId}}&detail_limit=0",
                    values = ReviewTemplateValues(bookId = "1", itemId = "2"),
                    transportPolicy = policy,
                )
            }
        }

        val releaseLoopback = ReviewTransportPolicy(
            isDebugBuild = false,
            declaredPolicy = ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY,
        )
        assertThrows(ReviewException.InvalidTemplate::class.java) {
            ReviewTemplateExpander.expand(
                sourceUrl = "http://127.0.0.1:8080",
                endpoint = ReviewEndpoint.INDEX,
                template = "/api/book/paragraph_comments" +
                    "?book_id={{bookId}}&item_id={{itemId}}&detail_limit=0",
                values = ReviewTemplateValues(bookId = "1", itemId = "2"),
                transportPolicy = releaseLoopback,
            )
        }
        assertTrue(
            ReviewTransportPolicy(true, ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY)
                .allowsRemoteHttp()
        )
    }

    /** 验证 ID、分页大小、版本和 cursor 的本地边界。 */
    @Test
    fun expand_rejectsInvalidInputs() {
        val template = "/api/book/paragraph_comment_page" +
            "?book_id={{bookId}}&item_id={{itemId}}&para_id={{paraId}}" +
            "&item_version={{itemVersion}}&count={{pageSize}}&cursor={{cursor}}"
        val invalidValues = listOf(
            ReviewTemplateValues("1x", "2", 3, "0", pageSize = 20),
            ReviewTemplateValues("1", "2", -1, "0", pageSize = 20),
            ReviewTemplateValues("1", "2", 3, "0", pageSize = 51),
            ReviewTemplateValues("1", "2", 3, "\n", pageSize = 20),
            ReviewTemplateValues("1", "2", 3, "0", pageSize = 20, cursor = "x".repeat(4097)),
        )
        invalidValues.forEach { values ->
            assertThrows(ReviewException.InvalidArgument::class.java) {
                ReviewTemplateExpander.expand(
                    "https://fanqie.example.invalid",
                    ReviewEndpoint.COMMENT_PAGE,
                    template,
                    values,
                )
            }
        }
    }
}
