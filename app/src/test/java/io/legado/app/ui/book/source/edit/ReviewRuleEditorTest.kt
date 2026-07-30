package io.legado.app.ui.book.source.edit

import io.legado.app.data.entities.rule.ReviewRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRuleEditorTest {

    /** 验证全空编辑器保持 ruleReview 为 null。 */
    @Test
    fun toReviewRuleOrNull_returnsNullWhenAllFieldsAreBlank() {
        assertNull(emptyMap<String, String?>().toReviewRuleOrNull())
        assertNull(mapOf("contractVersion" to "  ").toReviewRuleOrNull())
    }

    /** 验证编辑器可构建完整 v1 规则并保留可选字段。 */
    @Test
    fun toReviewRuleOrNull_buildsCompleteReadOnlyRule() {
        val values = requiredValues() + mapOf(
            "userIdRule" to "$.user_id",
            "quoteParentIdRule" to "$.reply_to_reply_id",
            "quoteChildrenRule" to "$.children",
        )

        val rule = values.toReviewRuleOrNull()
        assertTrue(rule?.supportsParagraphCommentsV1() == true)
        assertEquals("$.user_id", rule?.userIdRule)
        assertEquals("$.reply_to_reply_id", rule?.quoteParentIdRule)
        assertEquals("$.children", rule?.quoteChildrenRule)
    }

    /** 验证任一非空但不完整的编辑内容会构建为不支持规则供保存层拦截。 */
    @Test
    fun toReviewRuleOrNull_keepsIncompleteRuleForValidation() {
        val rule = mapOf("reviewUrl" to "/comments").toReviewRuleOrNull()
        assertFalse(rule?.supportsParagraphCommentsV1() == true)
    }

    /** 验证未修改的旧规则可沿用，而新建或改动后的不完整规则会被拒绝。 */
    @Test
    fun isValidEditFrom_preservesLegacyRulesAndRejectsInvalidChanges() {
        val legacyRule = ReviewRule(reviewUrl = "/legacy-comments")
        val changedLegacyRule = legacyRule.copy(reviewUrl = "/changed-comments")
        val completeRule = requiredValues().toReviewRuleOrNull()

        assertTrue(legacyRule.isValidEditFrom(legacyRule.copy()))
        assertFalse(legacyRule.isValidEditFrom(null))
        assertFalse(changedLegacyRule.isValidEditFrom(legacyRule))
        assertTrue(completeRule.isValidEditFrom(legacyRule))
        assertTrue((null as ReviewRule?).isValidEditFrom(legacyRule))
    }

    /** 创建能力判断要求的全部编辑器字段。 */
    private fun requiredValues(): Map<String, String?> = mapOf(
        "contractVersion" to ReviewRule.PARAGRAPH_COMMENTS_V1_CONTRACT,
        "reviewIndexUrl" to "/index",
        "reviewUrl" to "/comments",
        "reviewQuoteUrl" to "/replies",
        "paragraphListRule" to "$.paragraphs",
        "paragraphIdRule" to "$.para_id",
        "paragraphCountRule" to "$.count",
        "commentListRule" to "$.comments",
        "commentIdRule" to "$.comment_id",
        "contentRule" to "$.text",
        "postTimeRule" to "$.created_at",
        "voteUpCountRule" to "$.likes",
        "quoteCountRule" to "$.replies",
        "hasMoreRule" to "$.has_more",
        "nextCursorRule" to "$.next_cursor",
        "quoteListRule" to "$.reply_list",
        "quoteIdRule" to "$.reply_id",
        "quoteContentRule" to "$.text",
        "quotePostTimeRule" to "$.created_at",
        "quoteVoteUpCountRule" to "$.likes",
    )
}
