package io.legado.app.help.book

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphOrderEvidenceTest {

    /** 验证纯排版文字变化不会破坏等长、非空且顺序不变的源段序证据。 */
    @Test
    fun preservesOrder_acceptsOneToOneParagraphsWithTextFormattingChanges() {
        assertTrue(
            preservesSourceParagraphOrder(
                input(
                    originalContent = "第一段\n第二段",
                    finalParagraphs = listOf("　　第一段", "　　第二段"),
                )
            )
        )
    }

    /** 验证重分段、实际替换和重新包含标题任一发生时均保守失效。 */
    @Test
    fun preservesOrder_rejectsStructuralOrContentProcessing() {
        assertFalse(preservesSourceParagraphOrder(input(reSegmentApplied = true)))
        assertFalse(preservesSourceParagraphOrder(input(replacementChangedContent = true)))
        assertFalse(preservesSourceParagraphOrder(input(includeTitle = true)))
    }

    /** 验证已确认匹配的首行标题删除后，剩余正文仍保持服务端从零开始的段序。 */
    @Test
    fun preservesOrder_acceptsMatchedLeadingTitleRemoval() {
        assertTrue(
            preservesSourceParagraphOrder(
                input(
                    originalContent = "章节标题\n第一段\n第二段",
                    finalParagraphs = listOf("第一段", "第二段"),
                    sameTitleRemoved = true,
                )
            )
        )
        assertFalse(
            preservesSourceParagraphOrder(
                input(
                    originalContent = "章节标题\n第一段\n第二段",
                    finalParagraphs = listOf("第一段"),
                    sameTitleRemoved = true,
                )
            )
        )
    }

    /** 验证无效正文、空段和边界数量变化都不能形成可复用证据。 */
    @Test
    fun preservesOrder_rejectsInvalidBlankOrChangedBoundaries() {
        assertFalse(preservesSourceParagraphOrder(input(originalContent = "null")))
        assertFalse(preservesSourceParagraphOrder(input(originalContent = "第一段\n\n第二段")))
        assertFalse(preservesSourceParagraphOrder(input(finalParagraphs = listOf("第一段"))))
        assertFalse(
            preservesSourceParagraphOrder(
                input(finalParagraphs = listOf("第一段", " "))
            )
        )
    }

    /** 创建最小可比较的段序证据输入。 */
    private fun input(
        originalContent: String = "第一段\n第二段",
        finalParagraphs: List<String> = listOf("第一段", "第二段"),
        includeTitle: Boolean = false,
        sameTitleRemoved: Boolean = false,
        reSegmentApplied: Boolean = false,
        replacementChangedContent: Boolean = false,
    ) = ParagraphOrderEvidenceInput(
        originalContent = originalContent,
        finalParagraphs = finalParagraphs,
        includeTitle = includeTitle,
        sameTitleRemoved = sameTitleRemoved,
        reSegmentApplied = reSegmentApplied,
        replacementChangedContent = replacementChangedContent,
    )
}
