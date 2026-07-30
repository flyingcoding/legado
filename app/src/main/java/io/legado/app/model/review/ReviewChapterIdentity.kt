package io.legado.app.model.review

import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 保存段评请求所需的稳定章节身份。 */
data class ReviewChapterIdentity(
    val bookId: String,
    val itemId: String,
    val itemVersion: String = DEFAULT_ITEM_VERSION,
) {

    init {
        require(bookId.isReviewNumericId()) { "bookId must be numeric" }
        require(itemId.isReviewNumericId()) { "itemId must be numeric" }
        require(itemVersion.isNotBlank()) { "itemVersion must not be blank" }
    }

    companion object {
        const val DEFAULT_ITEM_VERSION = "0"
    }
}

/** 从章节变量优先读取段评身份，并对历史章节使用绝对 URL 查询参数回退。 */
fun BookChapter.reviewIdentityOrNull(): ReviewChapterIdentity? {
    val storedBookId = variableMap[REVIEW_BOOK_ID_VARIABLE].orEmpty()
    val storedItemId = variableMap[REVIEW_ITEM_ID_VARIABLE].orEmpty()
    val absoluteUrl = runCatching { getAbsoluteURL().toHttpUrlOrNull() }.getOrNull()
    val bookId = storedBookId.ifBlank {
        absoluteUrl?.queryParameter(REVIEW_BOOK_ID_QUERY).orEmpty()
    }
    val itemId = storedItemId.ifBlank {
        absoluteUrl?.queryParameter(REVIEW_ITEM_ID_QUERY).orEmpty()
    }
    if (!bookId.isReviewNumericId() || !itemId.isReviewNumericId()) return null
    if (storedBookId.isBlank()) putReviewVariable(REVIEW_BOOK_ID_VARIABLE, bookId)
    if (storedItemId.isBlank()) putReviewVariable(REVIEW_ITEM_ID_VARIABLE, itemId)
    return ReviewChapterIdentity(bookId = bookId, itemId = itemId)
}

/** 把目录解析阶段已验证的数字 ID 写入章节变量。 */
fun BookChapter.putReviewIdentity(bookId: String?, itemId: String?): Boolean {
    val safeBookId = bookId?.trim().orEmpty()
    val safeItemId = itemId?.trim().orEmpty()
    if (!safeBookId.isReviewNumericId() || !safeItemId.isReviewNumericId()) return false
    putReviewVariable(REVIEW_BOOK_ID_VARIABLE, safeBookId)
    putReviewVariable(REVIEW_ITEM_ID_VARIABLE, safeItemId)
    return true
}

/** 把短数字评论身份固定保存在章节变量 JSON 中，不触发 Android 大变量存储。 */
private fun BookChapter.putReviewVariable(key: String, value: String) {
    variableMap[key] = value
    variable = GSON.toJson(variableMap)
}

/** 判断 ID 是否为非空 ASCII 数字，避免 Unicode 数字进入 URL 合同。 */
private fun String.isReviewNumericId(): Boolean =
    isNotEmpty() && all { it in '0'..'9' }

const val REVIEW_BOOK_ID_VARIABLE = "fanqieBookId"
const val REVIEW_ITEM_ID_VARIABLE = "fanqieItemId"
private const val REVIEW_BOOK_ID_QUERY = "book_id"
private const val REVIEW_ITEM_ID_QUERY = "item_id"
