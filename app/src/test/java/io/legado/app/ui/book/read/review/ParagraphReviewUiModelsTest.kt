package io.legado.app.ui.book.read.review

import io.legado.app.model.review.ParagraphComment
import io.legado.app.model.review.ParagraphReply
import io.legado.app.model.review.ParagraphReplyTreeBuilder
import io.legado.app.ui.book.read.page.entities.column.formatParagraphReviewCount
import io.legado.app.ui.book.read.page.entities.column.reviewColumnLocalBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** 验证无父关系的根节点都作为所选主评的一级直接回复。 */
    @Test
    fun replyFlatten_offsetsDirectRepliesFromSelectedComment() {
        val flattened = flattenParagraphReviewReplies(listOf(reply("first"), reply("second")))

        assertEquals(listOf("first", "second"), flattened.map { it.reply.replyId })
        assertEquals(listOf(1, 1), flattened.map { it.visualDepth })
    }

    /** 验证完整回复树不截断，显式子节点递增且视觉缩进最多为三层。 */
    @Test
    fun replyFlatten_keepsAllNodesAndCapsVisualDepth() {
        val level4 = reply("4")
        val level3 = reply("3", listOf(level4))
        val level2 = reply("2", listOf(level3))
        val level1 = reply("1", listOf(level2))
        val tree = listOf(reply("0", listOf(level1)))

        val flattened = flattenParagraphReviewReplies(tree)

        assertEquals(listOf("0", "1", "2", "3", "4"), flattened.map { it.reply.replyId })
        assertEquals(listOf(1, 2, 3, 3, 3), flattened.map { it.visualDepth })
    }

    /** 验证显式 reply_to_reply_id 与嵌套 children 经重建后仍保持父子顺序。 */
    @Test
    fun replyFlatten_preservesExplicitTreeAfterRebuild() {
        val nestedChild = reply("nested").copy(replyToReplyId = "parent")
        val linkedChild = reply("linked").copy(replyToReplyId = "parent")
        val parent = reply("parent", children = listOf(nestedChild))

        val flattened = flattenParagraphReviewReplies(
            ParagraphReplyTreeBuilder.build(listOf(parent, linkedChild))
        )

        assertEquals(
            listOf("parent", "nested", "linked"),
            flattened.map { it.reply.replyId },
        )
        assertEquals(listOf(1, 2, 2), flattened.map { it.visualDepth })
    }

    /** 验证所选主评头部投影复用匿名、时间、计数和安全头像规则。 */
    @Test
    fun commentPresenter_buildsReadOnlySelectedCommentHeader() {
        val selectedComment = comment(
            userName = " ",
            userAvatar = "http://example.invalid/avatar.png",
            createTimestamp = 946684800,
            diggCount = 7,
            replyCount = 8,
        )
        val presentation = presentParagraphReviewComment(
            comment = selectedComment,
            anonymousUser = "匿名用户",
            unknownTime = "未知时间",
            repliesClickable = false,
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals("匿名用户", presentation.userName)
        assertEquals("主评正文", presentation.content)
        assertEquals("2000-01-01 00:00", presentation.time)
        assertEquals(7, presentation.diggCount)
        assertEquals(8, presentation.replyCount)
        assertNull(presentation.avatarUrl)
        assertFalse(presentation.canOpenReplies)
        assertTrue(
            presentParagraphReviewComment(
                comment = selectedComment,
                anonymousUser = "匿名用户",
                unknownTime = "未知时间",
                repliesClickable = true,
                zoneId = ZoneId.of("UTC"),
            ).canOpenReplies
        )
    }

    /** 验证缺失回复目标时不从正文中的 @ 文本伪造用户名。 */
    @Test
    fun replyPresenter_doesNotInferMissingTargetFromContent() {
        val presentation = presentParagraphReviewReply(
            reply = reply("reply").copy(text = "@某用户 仅作为正文", replyToUserName = null),
            anonymousUser = "匿名用户",
            unknownTime = "未知时间",
            zoneId = ZoneId.of("UTC"),
        )

        assertNull(presentation.replyToUserName)
        assertEquals("@某用户 仅作为正文", presentation.content)
    }

    /** 验证首次视图绑定只恢复目标位置，不会覆盖旋转前已经保存的列表位置。 */
    @Test
    fun listModeTransition_doesNotSaveUninitializedViewState() {
        val initial = paragraphReviewListModeTransition(
            currentRepliesMode = null,
            targetRepliesMode = true,
        )
        val unchanged = paragraphReviewListModeTransition(
            currentRepliesMode = true,
            targetRepliesMode = true,
        )
        val changed = paragraphReviewListModeTransition(
            currentRepliesMode = true,
            targetRepliesMode = false,
        )

        assertEquals(ParagraphReviewListModeTransition(true, false), initial)
        assertEquals(ParagraphReviewListModeTransition(false, false), unchanged)
        assertEquals(ParagraphReviewListModeTransition(true, true), changed)
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

    /** 创建用于主评头部展示测试的最小主评。 */
    private fun comment(
        userName: String?,
        userAvatar: String?,
        createTimestamp: Long,
        diggCount: Int,
        replyCount: Int,
    ) = ParagraphComment(
        commentId = "comment",
        text = "主评正文",
        userId = null,
        userName = userName,
        userAvatar = userAvatar,
        createTimestamp = createTimestamp,
        diggCount = diggCount,
        replyCount = replyCount,
        repliesLoaded = false,
        replies = emptyList(),
        replyTotal = null,
        replyHasMore = null,
        replyNextCursor = null,
    )
}
