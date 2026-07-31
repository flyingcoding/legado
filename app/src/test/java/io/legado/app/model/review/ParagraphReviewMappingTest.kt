package io.legado.app.model.review

import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.utils.INITIAL_GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /** 验证未知映射模式和有效 mode 但段序证据失效时继续安全隐藏。 */
    @Test
    fun defaultRegistry_requiresSupportedModeAndParagraphOrderEvidence() {
        val unknown = ParagraphReviewMapperRegistry().map(
            input(paragraphMappingMode = "future-mapper", paragraphOrderPreserved = true)
        )
        val unsafeContent = ParagraphReviewMapperRegistry().map(
            input(
                paragraphMappingMode = ReviewRule.FANQIE_PARAGRAPH_INDEX_MAPPING_MODE,
                paragraphOrderPreserved = false,
            )
        )

        assertEquals(
            ParagraphReviewMappingResult.Unavailable(
                ParagraphReviewMappingUnavailableReason.NO_VERIFIED_MAPPER
            ),
            unknown,
        )
        assertEquals(
            ParagraphReviewMappingResult.Unavailable(
                ParagraphReviewMappingUnavailableReason.UNSUPPORTED_CONTENT
            ),
            unsafeContent,
        )
    }

    /** 验证显式番茄 mode 只映射正计数 paraId，并绑定当前正文 hash。 */
    @Test
    fun fanqieMapper_mapsPositiveParagraphIdsAsVerifiedLocalIndices() {
        val input = input(
            paragraphMappingMode = ReviewRule.FANQIE_PARAGRAPH_INDEX_MAPPING_MODE,
            paragraphOrderPreserved = true,
        )

        val result = ParagraphReviewMapperRegistry().map(input)

        assertEquals(
            ParagraphReviewMappingResult.Verified(
                mappings = listOf(
                    ParagraphReviewMapping(localParagraphIndex = 1, paraId = 1, count = 3),
                    ParagraphReviewMapping(localParagraphIndex = 2, paraId = 2, count = 4),
                ),
                evidence = ParagraphReviewMappingEvidence.VERIFIED_SOURCE_FIXTURE,
                contentHash = CONTENT_HASH,
            ),
            result,
        )
    }

    /** 验证脱敏 20 章抽样 fixture 的代表性段落可得到 Verified 映射。 */
    @Test
    fun fanqieMapper_sanitizedFixtureProducesVerifiedMapping() {
        val fixture = INITIAL_GSON.fromJson(
            fixtureFile().readText(),
            SanitizedFanqieFixture::class.java,
        )
        val representative = fixture.representative
        val input = input(
            paragraphMappingMode = fixture.mappingMode,
            paragraphOrderPreserved = true,
            contentHash = representative.contentHash,
            localParagraphCount = representative.localParagraphCount,
            paragraphs = representative.positiveParagraphs.map {
                paragraph(it.paraId, it.count)
            },
        )

        val result = ParagraphReviewMapperRegistry().map(input)

        assertTrue(fixture.sanitized)
        assertEquals(20, fixture.sampledChapters)
        assertTrue(result is ParagraphReviewMappingResult.Verified)
        assertEquals(2, (result as ParagraphReviewMappingResult.Verified).mappings.size)
    }

    /** 验证内置 mapper 的越界和重复服务端段落仍由 registry 拒绝。 */
    @Test
    fun fanqieMapper_isStillBoundByRegistryInvariants() {
        val outOfBounds = ParagraphReviewMapperRegistry().map(
            input(
                paragraphMappingMode = ReviewRule.FANQIE_PARAGRAPH_INDEX_MAPPING_MODE,
                paragraphOrderPreserved = true,
                localParagraphCount = 2,
                paragraphs = listOf(paragraph(2, 4)),
            )
        )
        val duplicate = ParagraphReviewMapperRegistry().map(
            input(
                paragraphMappingMode = ReviewRule.FANQIE_PARAGRAPH_INDEX_MAPPING_MODE,
                paragraphOrderPreserved = true,
                localParagraphCount = 3,
                paragraphs = listOf(paragraph(1, 3), paragraph(1, 3)),
            )
        )

        assertEquals(
            ParagraphReviewMappingResult.Invalid(
                ParagraphReviewMappingInvalidReason.LOCAL_PARAGRAPH_OUT_OF_BOUNDS
            ),
            outOfBounds,
        )
        assertEquals(
            ParagraphReviewMappingResult.Invalid(
                ParagraphReviewMappingInvalidReason.DUPLICATE_LOCAL_PARAGRAPH
            ),
            duplicate,
        )
    }

    /** 验证注册 mapper 的 hash、边界、重复、服务端 ID 和计数均受统一门禁。 */
    @Test
    fun registry_rejectsInvalidVerifiedMappings() {
        assertInvalid(
            verified(mappings = listOf(mapping(local = 3))),
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
            mapOf(TEST_MAPPING_MODE to ParagraphReviewMapper { candidate })
        )
        assertEquals(
            ParagraphReviewMappingResult.Invalid(reason),
            registry.map(input(paragraphMappingMode = TEST_MAPPING_MODE)),
        )
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
    private fun input(
        paragraphMappingMode: String? = null,
        paragraphOrderPreserved: Boolean = false,
        contentHash: String = CONTENT_HASH,
        localParagraphCount: Int = 3,
        paragraphs: List<ReviewParagraph> = listOf(paragraph(1, 3), paragraph(2, 4)),
    ) = ParagraphReviewMappingInput(
        sourceUrl = SOURCE_URL,
        paragraphMappingMode = paragraphMappingMode,
        itemId = "2002",
        itemVersion = "v1",
        contentHash = contentHash,
        localParagraphCount = localParagraphCount,
        paragraphOrderPreserved = paragraphOrderPreserved,
        reviewIndex = ReviewIndex(
            itemId = "2002",
            bookId = "1001",
            itemVersion = "v1",
            paragraphs = paragraphs,
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

    /** 定位测试资源中的脱敏番茄段序样本。 */
    private fun fixtureFile(): File = sequenceOf(
        File("app/src/test/resources/paragraph-review/fanqie-paragraph-index-v1.json"),
        File("src/test/resources/paragraph-review/fanqie-paragraph-index-v1.json"),
    ).firstOrNull(File::isFile) ?: error("未找到番茄段序 fixture")

    private data class SanitizedFanqieFixture(
        val mappingMode: String,
        val sanitized: Boolean,
        val sampledChapters: Int,
        val representative: SanitizedRepresentative,
    )

    private data class SanitizedRepresentative(
        val localParagraphCount: Int,
        val contentHash: String,
        val positiveParagraphs: List<SanitizedParagraph>,
    )

    private data class SanitizedParagraph(
        val paraId: Int,
        val count: Int,
    )

    companion object {
        private const val SOURCE_URL = "https://fanqie.example.invalid"
        private const val CONTENT_HASH = "sha256:content"
        private const val TEST_MAPPING_MODE = "test-mapper"
    }
}
