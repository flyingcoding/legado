package io.legado.app.ui.book.source.edit

import io.legado.app.data.entities.rule.ReviewRule

/** 将编辑器字段构建为段评规则；全部为空时保留未配置语义。 */
internal fun Map<String, String?>.toReviewRuleOrNull(): ReviewRule? {
    if (values.none { !it.isNullOrBlank() }) return null
    return ReviewRule(
        contractVersion = value("contractVersion"),
        transportPolicy = value("transportPolicy"),
        paragraphMappingMode = value("paragraphMappingMode"),
        reviewIndexUrl = value("reviewIndexUrl"),
        reviewUrl = value("reviewUrl"),
        reviewQuoteUrl = value("reviewQuoteUrl"),
        paragraphListRule = value("paragraphListRule"),
        paragraphIdRule = value("paragraphIdRule"),
        paragraphCountRule = value("paragraphCountRule"),
        commentListRule = value("commentListRule"),
        commentIdRule = value("commentIdRule"),
        userIdRule = value("userIdRule"),
        userNameRule = value("userNameRule"),
        avatarRule = value("avatarRule"),
        contentRule = value("contentRule"),
        postTimeRule = value("postTimeRule"),
        voteUpCountRule = value("voteUpCountRule"),
        quoteCountRule = value("quoteCountRule"),
        hasMoreRule = value("hasMoreRule"),
        nextCursorRule = value("nextCursorRule"),
        quoteListRule = value("quoteListRule"),
        quoteIdRule = value("quoteIdRule"),
        quoteParentIdRule = value("quoteParentIdRule"),
        quoteUserIdRule = value("quoteUserIdRule"),
        quoteUserNameRule = value("quoteUserNameRule"),
        quoteAvatarRule = value("quoteAvatarRule"),
        quoteContentRule = value("quoteContentRule"),
        quotePostTimeRule = value("quotePostTimeRule"),
        quoteVoteUpCountRule = value("quoteVoteUpCountRule"),
        quoteChildrenRule = value("quoteChildrenRule"),
    )
}

/** 判断当前段评规则是否可沿用、清除或作为完整 v1 规则写出。 */
internal fun ReviewRule?.isValidEditFrom(originalRule: ReviewRule?): Boolean =
    this == null || supportsParagraphCommentsV1() || this == originalRule

/** 读取非空编辑值并将空白内容统一为 null。 */
private fun Map<String, String?>.value(key: String): String? =
    get(key)?.takeIf { it.isNotBlank() }
