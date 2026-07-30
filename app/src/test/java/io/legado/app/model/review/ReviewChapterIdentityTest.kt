package io.legado.app.model.review

import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewChapterIdentityTest {

    /** 验证变量优先于 URL 且能保留超出 Long 范围的数字 ID。 */
    @Test
    fun identity_prefersStoredVariablesAndKeepsStringIds() {
        val chapter = chapter(
            url = "/chapter?book_id=1&item_id=2",
        ).apply {
            putReviewIdentity(
                bookId = "1000000000000000000000001",
                itemId = "2000000000000000000000002",
            )
        }

        val identity = chapter.reviewIdentityOrNull()

        assertEquals("1000000000000000000000001", identity?.bookId)
        assertEquals("2000000000000000000000002", identity?.itemId)
    }

    /** 验证历史相对 URL 可从 baseUrl 解析并回填章节变量。 */
    @Test
    fun identity_fallsBackToAbsoluteUrlAndBackfillsVariables() {
        val chapter = chapter(url = "/chapter?book_id=1001&item_id=2002")

        val identity = chapter.reviewIdentityOrNull()

        assertEquals(ReviewChapterIdentity("1001", "2002"), identity)
        assertEquals("1001", chapter.variableMap[REVIEW_BOOK_ID_VARIABLE])
        assertEquals("2002", chapter.variableMap[REVIEW_ITEM_ID_VARIABLE])
    }

    /** 验证缺失、空值、非 ASCII 数字和不完整变量均安全拒绝。 */
    @Test
    fun identity_rejectsMissingOrMalformedIds() {
        assertNull(chapter("/chapter?book_id=1001").reviewIdentityOrNull())
        assertNull(chapter("/chapter?book_id=abc&item_id=2002").reviewIdentityOrNull())
        assertNull(chapter("/chapter?book_id=１００１&item_id=2002").reviewIdentityOrNull())

        val contaminated = chapter("/chapter?book_id=1001&item_id=2002").apply {
            variable = GSON.toJson(mapOf(REVIEW_BOOK_ID_VARIABLE to "bad"))
        }
        assertNull(contaminated.reviewIdentityOrNull())
    }

    /** 验证目录写入函数只接受完整数字对且不会部分写入。 */
    @Test
    fun putIdentity_requiresCompleteNumericPair() {
        val chapter = chapter("/chapter")

        assertFalse(chapter.putReviewIdentity("1001", "bad"))
        assertEquals(null, chapter.variableMap[REVIEW_BOOK_ID_VARIABLE])
        assertTrue(chapter.putReviewIdentity(" 1001 ", " 2002 "))
        assertEquals("1001", chapter.variableMap[REVIEW_BOOK_ID_VARIABLE])
        assertEquals("2002", chapter.variableMap[REVIEW_ITEM_ID_VARIABLE])
    }

    /** 创建带稳定 baseUrl 的最小章节。 */
    private fun chapter(url: String): BookChapter = BookChapter(
        url = url,
        baseUrl = "https://fanqie.example.invalid/catalog/index",
    )
}
