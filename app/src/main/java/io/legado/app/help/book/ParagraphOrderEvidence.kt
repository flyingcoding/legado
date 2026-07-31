package io.legado.app.help.book

/** 描述正文处理前后能否保守复用源段序的纯函数输入。 */
data class ParagraphOrderEvidenceInput(
    val originalContent: String,
    val finalParagraphs: List<String>,
    val includeTitle: Boolean,
    val sameTitleRemoved: Boolean,
    val reSegmentApplied: Boolean,
    val replacementChangedContent: Boolean,
)

/** 仅在移除可选首行标题后，源正文与最终段落仍一一对应且无结构处理时返回 true。 */
fun preservesSourceParagraphOrder(input: ParagraphOrderEvidenceInput): Boolean {
    if (input.originalContent == "null" || input.originalContent.isBlank() ||
        input.includeTitle || input.reSegmentApplied || input.replacementChangedContent ||
        input.finalParagraphs.isEmpty()
    ) {
        return false
    }
    val originalParagraphs = input.originalContent.lines()
    if (originalParagraphs.any { it.isBlank() }) {
        return false
    }
    val sourceParagraphs = if (input.sameTitleRemoved) {
        originalParagraphs.drop(1)
    } else {
        originalParagraphs
    }
    if (input.finalParagraphs.any { it.isBlank() }) {
        return false
    }
    return sourceParagraphs.size == input.finalParagraphs.size
}
