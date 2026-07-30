package io.legado.app.model.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphReviewMappingTest {

    /** 验证默认 registry 即使收到连续 paraId 也不猜测本地段序。 */
    @Test
    fun defaultRegistry_neverGuessesParagraphOrder() {
        val result = ParagraphReviewMapperRegistry().map(input())

        assertEquals(
            ParagraphReviewMappingResult.Unavailable(
                ParagraphReviewMappingUnavailableReason.NO_VERIFIED_MAPPER
            ),
            result,
        )
    }

    /** 验证注册 mapper 的 hash、边界、重复、服务端 ID 和计数均受统一门禁。 */
    @Test
    fun registry_rejectsInvalidVerifiedMappings() {
        assertInvalid(
            verified(mappings = listOf(mapping(local = 2))),
            ParagraphReviewMappingInvalidReason.LOCAL_PARAGRAPH_OUT_OF_BOUNDS,
        )
        assertInvalid(
            verified(mappings = listOf(mapping(local = 0), mapping(local = 0, paraId = 2, count = 4))),
            ParagraphReviewMappingInvalidReason.DUPLICATE_LOCAL_PARAGRAPH,
        )
        assertInvalid(
            verified(mappings = listOf(mapping(local = 0), mapping(local = 1))),
            ParagraphReviewMappingInvalidReason.DUPLICATE_SERVER_PARAGRAPH,
        )
        assertInvalid(
            verified(mappings = listOf(mapping(local = 0, paraId = 99))),
            ParagraphReviewMappingInvalidReason.UNKNOWN_SERVER_PARAGRAPH,
        )
        assertInvalid(
            verified(mappings = listOf(mapping(local = 0, count = 999))),
            ParagraphReviewMappingInvalidReason.COUNT_MISMATCH,
        )
        assertInvalid(
            verified(contentHash = "old"),
            ParagraphReviewMappingInvalidReason.CONTENT_HASH_CHANGED,
        )
    }

    /** 验证只有 Verified 正计数映射会生成携带 generation 的布局数据。 */
    @Test
    fun layoutData_keepsOnlyPositiveVerifiedEntries() {
        val result = verified(
            mappings = listOf(
                mapping(local = 0, count = 3),
                mapping(local = 1, paraId = 2, count = 0),
            )
        )
        val generation = generation()

        val layoutData = ParagraphReviewLayoutData.fromVerified(generation, result)

        assertEquals(1, layoutData.entries.size)
        assertEquals(ParagraphReviewLayoutEntry(1, 3, 7), layoutData.entryFor(0))
        assertEquals(null, layoutData.entryFor(1))
        assertTrue(
            ParagraphReviewLayoutData.fromVerified(
                generation.copy(contentHash = "changed"),
                result,
            ).entries.isEmpty()
        )
    }

    /** 验证无上下文和零计数不改正文，正计数才追加内部标记。 */
    @Test
    fun appendMarker_requiresPositiveLayoutEntry() {
        assertEquals("正文", appendParagraphReviewMarker("正文", null, "#"))
        assertEquals(
            "正文",
            appendParagraphReviewMarker("正文", ParagraphReviewLayoutEntry(1, 0, 7), "#")
        )
        assertEquals(
            "正文#",
            appendParagraphReviewMarker("正文", ParagraphReviewLayoutEntry(1, 3, 7), "#")
        )
    }

    /** 验证指定 mapper 输出并返回期望的 Invalid 原因。 */
    private fun assertInvalid(
        candidate: ParagraphReviewMappingResult,
        reason: ParagraphReviewMappingInvalidReason,
    ) {
        val registry = ParagraphReviewMapperRegistry(
            mapOf(SOURCE_URL to ParagraphReviewMapper { candidate })
        )
        assertEquals(ParagraphReviewMappingResult.Invalid(reason), registry.map(input()))
    }

    /** 创建带证据的映射结果。 */
    private fun verified(
        mappings: List<ParagraphReviewMapping> = listOf(mapping(local = 0)),
        contentHash: String = CONTENT_HASH,
    ): ParagraphReviewMappingResult.Verified = ParagraphReviewMappingResult.Verified(
        mappings = mappings,
        evidence = ParagraphReviewMappingEvidence.VERIFIED_SOURCE_FIXTURE,
        contentHash = contentHash,
    )

    /** 创建最小映射条目。 */
    private fun mapping(
        local: Int,
        paraId: Int = 1,
        count: Int = 3,
    ) = ParagraphReviewMapping(local, paraId, count)

    /** 创建最小映射输入。 */
    private fun input() = ParagraphReviewMappingInput(
        sourceUrl = SOURCE_URL,
        itemId = "2002",
        itemVersion = "v1",
        contentHash = CONTENT_HASH,
        localParagraphCount = 2,
        reviewIndex = ReviewIndex(
            itemId = "2002",
            bookId = "1001",
            itemVersion = "v1",
            paragraphs = listOf(paragraph(1, 3), paragraph(2, 4)),
            partial = false,
            warnings = emptyList(),
        ),
    )

    /** 创建最小索引段落。 */
    private fun paragraph(paraId: Int, count: Int) = ReviewParagraph(
        paraId = paraId,
        count = count,
        hot = "",
        userCount = 0,
        detailLoaded = false,
        comments = emptyList(),
    )

    /** 创建与映射输入匹配的 generation。 */
    private fun generation() = ReviewGeneration(
        token = 7,
        sourceUrl = SOURCE_URL,
        bookUrl = "book",
        chapterIndex = 0,
        itemId = "2002",
        contentHash = CONTENT_HASH,
    )

    companion object {
        private const val SOURCE_URL = "https://fanqie.example.invalid"
        private const val CONTENT_HASH = "sha256:content"
    }
}
