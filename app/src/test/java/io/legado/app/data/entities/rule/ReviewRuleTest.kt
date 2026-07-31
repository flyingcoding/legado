package io.legado.app.data.entities.rule

import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.utils.INITIAL_GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewRuleTest {

    /** 验证完整 v1 规则和缺少任一必填字段时的能力判断。 */
    @Test
    fun supportsParagraphCommentsV1_requiresEveryRequiredField() {
        val completeRule = completeRule()
        assertTrue(completeRule.supportsParagraphCommentsV1())

        val expectedRequiredFields = linkedSetOf(
            "contractVersion",
            "reviewIndexUrl",
            "reviewUrl",
            "reviewQuoteUrl",
            "paragraphListRule",
            "paragraphIdRule",
            "paragraphCountRule",
            "commentListRule",
            "commentIdRule",
            "contentRule",
            "postTimeRule",
            "voteUpCountRule",
            "quoteCountRule",
            "hasMoreRule",
            "nextCursorRule",
            "quoteListRule",
            "quoteIdRule",
            "quoteContentRule",
            "quotePostTimeRule",
            "quoteVoteUpCountRule",
        )
        assertEquals(expectedRequiredFields, completeRule.paragraphCommentsV1RequiredFields.keys)
        val completeJson = INITIAL_GSON.toJsonTree(completeRule).asJsonObject
        expectedRequiredFields.forEach { field ->
            val incompleteJson = completeJson.deepCopy().apply { remove(field) }
            val incompleteRule = INITIAL_GSON.fromJson(incompleteJson, ReviewRule::class.java)
            assertFalse(field, incompleteRule.supportsParagraphCommentsV1())
        }
    }

    /** 验证可选字段为空不影响能力，未知合同和空规则不被支持。 */
    @Test
    fun supportsParagraphCommentsV1_acceptsMissingOptionalFieldsOnly() {
        assertTrue(completeRule().supportsParagraphCommentsV1())
        assertTrue(
            completeRule().copy(
                transportPolicy = ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY,
                paragraphMappingMode = ReviewRule.FANQIE_PARAGRAPH_INDEX_MAPPING_MODE,
            ).supportsParagraphCommentsV1()
        )
        assertTrue(
            completeRule().copy(
                transportPolicy = "future-transport",
                paragraphMappingMode = "future-mapper",
            ).supportsParagraphCommentsV1()
        )
        assertFalse(
            completeRule().copy(contractVersion = "fanqie.paragraph-comments.v2")
                .supportsParagraphCommentsV1()
        )
        assertFalse(ReviewRule().supportsParagraphCommentsV1())
    }

    /** 验证 Room converter 对完整规则、null 和畸形内容的安全往返。 */
    @Test
    fun converter_roundTripsKnownFieldsAndHandlesInvalidJson() {
        val converters = BookSource.Converters()
        val expected = completeRule(includeOptionalFields = true)

        val json = converters.reviewRuleToString(expected)
        assertEquals(expected, converters.stringToReviewRule(json))
        assertFalse(json.contains("paragraphCommentsV1RequiredFields"))
        assertNull(converters.stringToReviewRule(null))
        assertNull(converters.stringToReviewRule("null"))
        assertNull(converters.stringToReviewRule("{invalid"))
        assertEquals("null", converters.reviewRuleToString(null))
    }

    /** 验证仓库示例书源可导入并通过 Room converter 保持完整 v1 规则。 */
    @Test
    fun documentedSample_importsAndRoundTripsReviewRule() {
        val source = GSON.fromJsonArray<BookSource>(sampleSourceFile().readText())
            .getOrThrow()
            .single()
        val reviewRule = source.ruleReview
        assertTrue(reviewRule?.supportsParagraphCommentsV1() == true)
        assertEquals(ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY, reviewRule?.transportPolicy)
        assertEquals(
            ReviewRule.FANQIE_PARAGRAPH_INDEX_MAPPING_MODE,
            reviewRule?.paragraphMappingMode,
        )

        val converters = BookSource.Converters()
        val restored = converters.stringToReviewRule(converters.reviewRuleToString(reviewRule))
        assertEquals(reviewRule, restored)
    }

    /** 验证 Gson 兼容字符串规则、畸形嵌套规则和旧版未知写字段。 */
    @Test
    fun gson_importsCompatibleReviewRuleShapesWithoutCrashing() {
        val ruleJson = GSON.toJson(completeRule(includeOptionalFields = true))
        val quotedRule = GSON.fromJsonObject<ReviewRule>(GSON.toJson(ruleJson)).getOrThrow()
        assertTrue(quotedRule.supportsParagraphCommentsV1())

        val malformedSource = GSON.fromJsonObject<BookSource>(
            """{"bookSourceUrl":"https://example.com","bookSourceName":"test","ruleReview":"{invalid"}"""
        ).getOrThrow()
        assertNull(malformedSource.ruleReview)

        val legacyRule = GSON.fromJsonObject<ReviewRule>(
            """{"reviewUrl":"/comments","voteUpUrl":"/write-like"}"""
        ).getOrThrow()
        assertFalse(legacyRule.supportsParagraphCommentsV1())
        assertFalse(GSON.toJson(legacyRule).contains("voteUpUrl"))
    }

    /** 验证书源 Gson 往返保留完整规则和既有非评论字段。 */
    @Test
    fun bookSource_roundTripPreservesReviewAndExistingRules() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "example",
            searchUrl = "/search?key={{key}}",
            ruleSearch = SearchRule(bookList = "$.data.books", name = "$.name"),
            ruleContent = ContentRule(content = "$.data.content"),
            ruleReview = completeRule(includeOptionalFields = true),
        )

        val restored = GSON.fromJsonObject<BookSource>(GSON.toJson(source)).getOrThrow()
        assertEquals(source.bookSourceUrl, restored.bookSourceUrl)
        assertEquals(source.bookSourceName, restored.bookSourceName)
        assertEquals(source.searchUrl, restored.searchUrl)
        assertEquals(source.ruleReview, restored.ruleReview)
        assertTrue(restored.ruleReview?.supportsParagraphCommentsV1() == true)
        assertEquals(source.ruleSearch, restored.ruleSearch)
        assertEquals(source.ruleContent, restored.ruleContent)
    }

    /** 验证新增可选声明的受支持值和未知值都能原样往返。 */
    @Test
    fun optionalDeclarations_roundTripWithoutChangingCapability() {
        val converters = BookSource.Converters()
        val declarations = listOf(
            ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY to
                ReviewRule.FANQIE_PARAGRAPH_INDEX_MAPPING_MODE,
            "future-transport" to "future-mapper",
        )

        declarations.forEach { (transportPolicy, paragraphMappingMode) ->
            val rule = completeRule().copy(
                transportPolicy = transportPolicy,
                paragraphMappingMode = paragraphMappingMode,
            )
            val restored = converters.stringToReviewRule(converters.reviewRuleToString(rule))

            assertEquals(transportPolicy, restored?.transportPolicy)
            assertEquals(paragraphMappingMode, restored?.paragraphMappingMode)
            assertTrue(restored?.supportsParagraphCommentsV1() == true)
        }
    }

    /** 验证无规则、null、旧规则、未知合同和不完整规则均不破坏书源原有字段。 */
    @Test
    fun bookSource_importsUnsupportedReviewShapesWithoutChangingExistingRules() {
        val reviewShapes = listOf(
            "",
            ",\"ruleReview\":null",
            ",\"ruleReview\":{\"reviewUrl\":\"/comments\",\"voteUpUrl\":\"/write\"}",
            ",\"ruleReview\":{\"contractVersion\":\"fanqie.paragraph-comments.v2\"}",
            ",\"ruleReview\":{\"contractVersion\":\"fanqie.paragraph-comments.v1\"}",
        )

        reviewShapes.forEach { reviewShape ->
            val source = GSON.fromJsonObject<BookSource>(
                """{
                    "bookSourceUrl":"https://example.com",
                    "bookSourceName":"example",
                    "searchUrl":"/search",
                    "ruleSearch":{"bookList":"$.books","name":"$.name"},
                    "ruleContent":{"content":"$.content"}$reviewShape
                }""".trimIndent()
            ).getOrThrow()

            assertEquals("/search", source.searchUrl)
            assertEquals("$.books", source.ruleSearch?.bookList)
            assertEquals("$.name", source.ruleSearch?.name)
            assertEquals("$.content", source.ruleContent?.content)
            assertFalse(source.ruleReview?.supportsParagraphCommentsV1() == true)
            assertFalse(GSON.toJson(source).contains("voteUpUrl"))
        }
    }

    /** 验证 BookSource.equal 能识别段评规则变化。 */
    @Test
    fun bookSourceEqual_includesReviewRule() {
        val source = BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "example",
            ruleReview = completeRule(),
        )
        val changed = source.copy(
            ruleReview = source.ruleReview?.copy(reviewUrl = "/changed")
        )
        val changedTransport = source.copy(
            ruleReview = source.ruleReview?.copy(
                transportPolicy = ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY
            )
        )
        val changedMapping = source.copy(
            ruleReview = source.ruleReview?.copy(
                paragraphMappingMode = ReviewRule.FANQIE_PARAGRAPH_INDEX_MAPPING_MODE
            )
        )

        assertFalse(source.equal(changed))
        assertFalse(source.equal(changedTransport))
        assertFalse(source.equal(changedMapping))
        assertTrue(source.equal(source.copy()))
    }

    /** 创建覆盖合同全部字段的 v1 规则。 */
    private fun completeRule(includeOptionalFields: Boolean = false): ReviewRule = ReviewRule(
        contractVersion = ReviewRule.PARAGRAPH_COMMENTS_V1_CONTRACT,
        transportPolicy = ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY.takeIf { includeOptionalFields },
        paragraphMappingMode = ReviewRule.FANQIE_PARAGRAPH_INDEX_MAPPING_MODE
            .takeIf { includeOptionalFields },
        reviewIndexUrl = "/api/book/paragraph_comments",
        reviewUrl = "/api/book/paragraph_comment_page",
        reviewQuoteUrl = "/api/book/paragraph_comment_replies",
        paragraphListRule = "$.data.paragraphs",
        paragraphIdRule = "$.para_id",
        paragraphCountRule = "$.count",
        commentListRule = "$.data.comments",
        commentIdRule = "$.comment_id",
        userIdRule = "$.user_id".takeIf { includeOptionalFields },
        userNameRule = "$.user_name".takeIf { includeOptionalFields },
        avatarRule = "$.user_avatar".takeIf { includeOptionalFields },
        contentRule = "$.text",
        postTimeRule = "$.create_timestamp",
        voteUpCountRule = "$.digg_count",
        quoteCountRule = "$.reply_count",
        hasMoreRule = "$.data.has_more",
        nextCursorRule = "$.data.next_cursor",
        quoteListRule = "$.data.replies",
        quoteIdRule = "$.reply_id",
        quoteParentIdRule = "$.reply_to_reply_id".takeIf { includeOptionalFields },
        quoteUserIdRule = "$.user_id".takeIf { includeOptionalFields },
        quoteUserNameRule = "$.user_name".takeIf { includeOptionalFields },
        quoteAvatarRule = "$.user_avatar".takeIf { includeOptionalFields },
        quoteContentRule = "$.text",
        quotePostTimeRule = "$.create_timestamp",
        quoteVoteUpCountRule = "$.digg_count",
        quoteChildrenRule = "$.children".takeIf { includeOptionalFields },
    )

    /** 定位版本库中的权威 v1 示例书源。 */
    private fun sampleSourceFile(): File = sequenceOf(
        File("docs/fanqie-integration/fanqie-legado-source.json"),
        File("../docs/fanqie-integration/fanqie-legado-source.json"),
    ).firstOrNull { it.isFile }
        ?: error("未找到 fanqie-legado-source.json")
}
