package io.legado.app.ui.book.read.review

import io.legado.app.model.review.ParagraphReply
import io.legado.app.ui.book.read.page.entities.column.formatParagraphReviewCount
import io.legado.app.ui.book.read.page.entities.column.reviewColumnLocalBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class ParagraphReviewUiModelsTest {

    /** 验证 Unix 秒按指定时区格式化，非正时间使用未知占位。 */
    @Test
    fun timeFormatter_usesUnixSecondsAndUnknownFallback() {
        assertEquals("未知", formatParagraphReviewTime(0, "未知", ZoneId.of("UTC")))
        assertEquals(
            "2000-01-01 00:00",
            formatParagraphReviewTime(946684800, "未知", ZoneId.of("UTC")),
        )
    }

    /** 验证头像只接受 HTTPS，HTTP、相对地址和畸形值统一回退占位。 */
    @Test
    fun avatarValidator_acceptsOnlyHttps() {
        assertEquals("https://example.invalid/avatar.png", safeParagraphReviewAvatar(
            "https://example.invalid/avatar.png"
        ))
        assertNull(safeParagraphReviewAvatar("http://example.invalid/avatar.png"))
        assertNull(safeParagraphReviewAvatar("/avatar.png"))
        assertNull(safeParagraphReviewAvatar("not a url"))
    }

    /** 验证完整回复树不截断，视觉缩进最多为三层并保持先序。 */
    @Test
    fun replyFlatten_keepsAllNodesAndCapsVisualDepth() {
        val level4 = reply("4")
        val level3 = reply("3", listOf(level4))
        val level2 = reply("2", listOf(level3))
        val level1 = reply("1", listOf(level2))
        val tree = listOf(reply("0", listOf(level1)))

        val flattened = flattenParagraphReviewReplies(tree)

        assertEquals(listOf("0", "1", "2", "3", "4"), flattened.map { it.reply.replyId })
        assertEquals(listOf(0, 1, 2, 3, 3), flattened.map { it.visualDepth })
    }

    /** 验证 count 0/1/999/1000 的气泡文本边界。 */
    @Test
    fun countFormatter_capsOnlyValuesAbove999() {
        assertEquals("0", formatParagraphReviewCount(0))
        assertEquals("1", formatParagraphReviewCount(1))
        assertEquals("999", formatParagraphReviewCount(999))
        assertEquals("999+", formatParagraphReviewCount(1000))
    }

    /** 验证气泡在行内离屏画布中使用相对基线，避免重复叠加行偏移。 */
    @Test
    fun reviewColumn_usesLineLocalBaseline() {
        assertEquals(52f, reviewColumnLocalBaseline(lineBase = 412f, lineTop = 360f), 0f)
    }

    /** 创建最小回复树节点。 */
    private fun reply(id: String, children: List<ParagraphReply> = emptyList()) = ParagraphReply(
        replyId = id,
        parentReplyId = null,
        replyToCommentId = "comment",
        replyToReplyId = null,
        text = id,
        userId = null,
        userName = null,
        userAvatar = null,
        replyToUserName = null,
        createTimestamp = 0,
        diggCount = 0,
        replyCount = 0,
        children = children,
    )
}
