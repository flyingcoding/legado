package io.legado.app.help.book

import io.legado.app.data.entities.ReplaceRule
import io.legado.app.model.review.ParagraphReviewLayoutData

data class BookContent(
    val sameTitleRemoved: Boolean,
    val textList: List<String>,
    //起效的替换规则
    val effectiveReplaceRules: List<ReplaceRule>?,
    val paragraphReviewLayoutData: ParagraphReviewLayoutData = ParagraphReviewLayoutData.EMPTY,
) {

    override fun toString(): String {
        return textList.joinToString("\n")
    }

}
