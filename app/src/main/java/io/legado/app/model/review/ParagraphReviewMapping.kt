package io.legado.app.model.review

import io.legado.app.data.entities.rule.ReviewRule
import java.nio.ByteBuffer
import java.security.MessageDigest

/** 描述已获验证的段落映射证据类型。 */
enum class ParagraphReviewMappingEvidence {
    SERVER_METADATA,
    VERIFIED_SOURCE_FIXTURE,
}

/** 把最终本地段落下标关联到真实服务端段落及评论计数。 */
data class ParagraphReviewMapping(
    val localParagraphIndex: Int,
    val paraId: Int,
    val count: Int,
)

/** 描述一次段落映射所需的稳定输入。 */
data class ParagraphReviewMappingInput(
    val sourceUrl: String,
    val paragraphMappingMode: String?,
    val itemId: String,
    val itemVersion: String,
    val contentHash: String,
    val localParagraphCount: Int,
    val paragraphOrderPreserved: Boolean,
    val reviewIndex: ReviewIndex,
)

/** 表示已验证、证据缺失或证据失效三种映射结果。 */
sealed interface ParagraphReviewMappingResult {

    /** 保存可用于排版的映射及其证据。 */
    data class Verified(
        val mappings: List<ParagraphReviewMapping>,
        val evidence: ParagraphReviewMappingEvidence,
        val contentHash: String,
    ) : ParagraphReviewMappingResult

    /** 表示当前来源没有经过验证的映射能力。 */
    data class Unavailable(
        val reason: ParagraphReviewMappingUnavailableReason,
    ) : ParagraphReviewMappingResult

    /** 表示已声明映射证据，但当前输入不再满足该证据。 */
    data class Invalid(
        val reason: ParagraphReviewMappingInvalidReason,
    ) : ParagraphReviewMappingResult
}

/** 枚举无映射能力时的安全隐藏原因。 */
enum class ParagraphReviewMappingUnavailableReason {
    NO_VERIFIED_MAPPER,
    UNSUPPORTED_CONTENT,
}

/** 枚举已声明映射证据失效的原因。 */
enum class ParagraphReviewMappingInvalidReason {
    BLANK_CONTENT_HASH,
    CONTENT_HASH_CHANGED,
    RESPONSE_IDENTITY_MISMATCH,
    LOCAL_PARAGRAPH_OUT_OF_BOUNDS,
    DUPLICATE_LOCAL_PARAGRAPH,
    DUPLICATE_SERVER_PARAGRAPH,
    UNKNOWN_SERVER_PARAGRAPH,
    COUNT_MISMATCH,
}

/** 为经过真实 fixture 或稳定元数据验证的来源提供映射。 */
fun interface ParagraphReviewMapper {

    /** 根据稳定输入返回带证据的映射结果。 */
    fun map(input: ParagraphReviewMappingInput): ParagraphReviewMappingResult
}

/** 按书源显式 mapping mode 注册验证过的 mapper，未注册模式始终安全隐藏。 */
class ParagraphReviewMapperRegistry(
    private val verifiedMappers: Map<String, ParagraphReviewMapper> = defaultMappers(),
) {

    /** 调用来源专属 mapper，并统一验证其身份、hash、边界、重复和计数。 */
    fun map(input: ParagraphReviewMappingInput): ParagraphReviewMappingResult {
        val mapper = input.paragraphMappingMode?.let(verifiedMappers::get)
            ?: return ParagraphReviewMappingResult.Unavailable(
                ParagraphReviewMappingUnavailableReason.NO_VERIFIED_MAPPER
            )
        val result = mapper.map(input)
        return if (result is ParagraphReviewMappingResult.Verified) {
            validateVerified(input, result)
        } else {
            result
        }
    }

    /** 验证 mapper 输出只能引用当前索引中真实存在且计数一致的段落。 */
    private fun validateVerified(
        input: ParagraphReviewMappingInput,
        result: ParagraphReviewMappingResult.Verified,
    ): ParagraphReviewMappingResult {
        if (input.contentHash.isBlank()) {
            return ParagraphReviewMappingResult.Invalid(
                ParagraphReviewMappingInvalidReason.BLANK_CONTENT_HASH
            )
        }
        if (result.contentHash != input.contentHash) {
            return ParagraphReviewMappingResult.Invalid(
                ParagraphReviewMappingInvalidReason.CONTENT_HASH_CHANGED
            )
        }
        if (input.reviewIndex.itemId != input.itemId ||
            input.reviewIndex.itemVersion != input.itemVersion
        ) {
            return ParagraphReviewMappingResult.Invalid(
                ParagraphReviewMappingInvalidReason.RESPONSE_IDENTITY_MISMATCH
            )
        }
        if (result.mappings.any { it.localParagraphIndex !in 0 until input.localParagraphCount }) {
            return ParagraphReviewMappingResult.Invalid(
                ParagraphReviewMappingInvalidReason.LOCAL_PARAGRAPH_OUT_OF_BOUNDS
            )
        }
        if (result.mappings.map { it.localParagraphIndex }.toSet().size != result.mappings.size) {
            return ParagraphReviewMappingResult.Invalid(
                ParagraphReviewMappingInvalidReason.DUPLICATE_LOCAL_PARAGRAPH
            )
        }
        if (result.mappings.map { it.paraId }.toSet().size != result.mappings.size) {
            return ParagraphReviewMappingResult.Invalid(
                ParagraphReviewMappingInvalidReason.DUPLICATE_SERVER_PARAGRAPH
            )
        }
        val paragraphsById = input.reviewIndex.paragraphs.associateBy(ReviewParagraph::paraId)
        if (result.mappings.any { it.paraId !in paragraphsById }) {
            return ParagraphReviewMappingResult.Invalid(
                ParagraphReviewMappingInvalidReason.UNKNOWN_SERVER_PARAGRAPH
            )
        }
        if (result.mappings.any { paragraphsById.getValue(it.paraId).count != it.count }) {
            return ParagraphReviewMappingResult.Invalid(
                ParagraphReviewMappingInvalidReason.COUNT_MISMATCH
            )
        }
        return result
    }

    companion object {

        /** 注册经脱敏真实样本约束的内置番茄段序 mapper。 */
        private fun defaultMappers(): Map<String, ParagraphReviewMapper> = mapOf(
            ReviewRule.FANQIE_PARAGRAPH_INDEX_MAPPING_MODE to
                FanqieParagraphIndexMapper,
        )
    }
}

/** 在书源显式声明且正文段序证据有效时复用番茄 paraId 作为本地下标。 */
private object FanqieParagraphIndexMapper : ParagraphReviewMapper {

    /** 只输出索引中真实存在的正计数段落，其余一致性由 registry 统一校验。 */
    override fun map(input: ParagraphReviewMappingInput): ParagraphReviewMappingResult {
        if (!input.paragraphOrderPreserved) {
            return ParagraphReviewMappingResult.Unavailable(
                ParagraphReviewMappingUnavailableReason.UNSUPPORTED_CONTENT
            )
        }
        return ParagraphReviewMappingResult.Verified(
            mappings = input.reviewIndex.paragraphs.asSequence()
                .filter { it.count > 0 }
                .map { paragraph ->
                    ParagraphReviewMapping(
                        localParagraphIndex = paragraph.paraId,
                        paraId = paragraph.paraId,
                        count = paragraph.count,
                    )
                }
                .toList(),
            evidence = ParagraphReviewMappingEvidence.VERIFIED_SOURCE_FIXTURE,
            contentHash = input.contentHash,
        )
    }
}

/** 保存单个最终本地段落的排版评论上下文。 */
data class ParagraphReviewLayoutEntry(
    val paraId: Int,
    val count: Int,
    val generation: Long,
)

/** 保存一章已验证映射生成的只读排版数据。 */
data class ParagraphReviewLayoutData(
    val contentHash: String,
    val entries: Map<Int, ParagraphReviewLayoutEntry>,
) {

    /** 返回指定最终本地段落的正计数上下文。 */
    fun entryFor(localParagraphIndex: Int): ParagraphReviewLayoutEntry? =
        entries[localParagraphIndex]?.takeIf { it.count > 0 }

    companion object {
        val EMPTY = ParagraphReviewLayoutData(contentHash = "", entries = emptyMap())

        /** 仅从当前 generation 的 Verified 结果创建布局数据。 */
        fun fromVerified(
            generation: ReviewGeneration,
            result: ParagraphReviewMappingResult,
        ): ParagraphReviewLayoutData {
            if (result !is ParagraphReviewMappingResult.Verified ||
                result.contentHash != generation.contentHash
            ) {
                return EMPTY
            }
            return ParagraphReviewLayoutData(
                contentHash = result.contentHash,
                entries = result.mappings.asSequence()
                    .filter { it.count > 0 }
                    .associate { mapping ->
                        mapping.localParagraphIndex to ParagraphReviewLayoutEntry(
                            paraId = mapping.paraId,
                            count = mapping.count,
                            generation = generation.token,
                        )
                    },
            )
        }
    }
}

/** 以长度前缀保留段落边界，生成最终正文的稳定 SHA-256。 */
fun paragraphReviewContentHash(paragraphs: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    paragraphs.forEach { paragraph ->
        val bytes = paragraph.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/** 只为带正计数上下文的正文段尾追加内部排版标记。 */
fun appendParagraphReviewMarker(
    text: String,
    entry: ParagraphReviewLayoutEntry?,
    marker: String,
): String = if (text.isNotBlank() && entry?.count?.let { it > 0 } == true) {
    text + marker
} else {
    text
}
