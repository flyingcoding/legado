package io.legado.app.ui.book.read.review

import io.legado.app.model.review.ParagraphComment
import io.legado.app.model.review.ParagraphCommentImage
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

    /** 验证图片投影只保留 HTTPS，并保持有效图片的服务端顺序。 */
    @Test
    fun imagePresenter_filtersUnsafeUrlsAndPreservesOrder() {
        val images = listOf(
            image("https://example.invalid/first.webp", 100, 200),
            image("http://example.invalid/plain.jpg", 100, 100),
            image("/relative.png", 100, 100),
            image("data:image/png;base64,AA", 100, 100),
            image("file:///tmp/image.png", 100, 100),
            image("content://images/1", 100, 100),
            image("https://example.invalid/second.webp", 200, 100),
        )

        val presentation = presentParagraphReviewImages(images)

        assertEquals(
            listOf(
                "https://example.invalid/first.webp",
                "https://example.invalid/second.webp",
            ),
            presentation.map(ParagraphReviewImagePresentation::url),
        )
        assertEquals(listOf(0.5f, 2f), presentation.map { it.aspectRatio })
    }

    /** 验证零尺寸回退一比一，极端尺寸被限制在安全比例内。 */
    @Test
    fun imageAspectRatio_usesDefaultAndSafeBounds() {
        assertEquals(1f, paragraphReviewImageAspectRatio(0, 100), 0f)
        assertEquals(1f, paragraphReviewImageAspectRatio(100, 0), 0f)
        assertEquals(0.5f, paragraphReviewImageAspectRatio(1, Long.MAX_VALUE), 0f)
        assertEquals(2f, paragraphReviewImageAspectRatio(Long.MAX_VALUE, 1), 0f)
        assertEquals(1.5f, paragraphReviewImageAspectRatio(300, 200), 0f)
    }

    /** 验证纯图片主评保留空正文和安全图片列表。 */
    @Test
    fun commentPresenter_keepsImageOnlyContent() {
        val presentation = presentParagraphReviewComment(
            comment = comment(null, null, 0, 0, 0).copy(
                text = "",
                images = listOf(image("https://example.invalid/only.png", 0, 0)),
            ),
            anonymousUser = "匿名用户",
            unknownTime = "未知时间",
            repliesClickable = false,
        )

        assertEquals("", presentation.content)
        assertEquals(1, presentation.images.size)
        assertEquals(1f, presentation.images.single().aspectRatio, 0f)
    }

    /** 验证纯图片回复同样保留空正文和安全图片列表。 */
    @Test
    fun replyPresenter_keepsImageOnlyContent() {
        val presentation = presentParagraphReviewReply(
            reply = reply("image-only").copy(
                text = "",
                images = listOf(image("https://example.invalid/reply.png", 200, 100)),
            ),
            anonymousUser = "匿名用户",
            unknownTime = "未知时间",
        )

        assertEquals("", presentation.content)
        assertEquals("https://example.invalid/reply.png", presentation.images.single().url)
        assertEquals(2f, presentation.images.single().aspectRatio, 0f)
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
        assertFalse(
            presentParagraphReviewComment(
                comment = selectedComment.copy(replyCount = 0),
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

    /** 验证分页 total 成功后覆盖索引计数，加载前和非法索引保持安全值。 */
    @Test
    fun commentTotal_prefersServerTotalAfterFirstPage() {
        assertEquals(8, resolveParagraphReviewCommentTotal(indexCount = 8, serverTotal = null))
        assertEquals(11, resolveParagraphReviewCommentTotal(indexCount = 8, serverTotal = 11))
        assertEquals(0, resolveParagraphReviewCommentTotal(indexCount = -1, serverTotal = null))
    }

    /** 验证零回复和已选主评忽略，另一主评切换为展开。 */
    @Test
    fun replyToggle_enforcesSingleExpandableComment() {
        assertEquals(
            ParagraphReviewReplyToggleAction.IGNORE,
            paragraphReviewReplyToggleAction("first", "empty", 0),
        )
        assertEquals(
            ParagraphReviewReplyToggleAction.IGNORE,
            paragraphReviewReplyToggleAction("first", "first", 3),
        )
        assertEquals(
            ParagraphReviewReplyToggleAction.EXPAND,
            paragraphReviewReplyToggleAction("first", "second", 2),
        )
    }

    /** 验证首次展开只显示三条，已预取的其余回复保持隐藏并提示下一批十条。 */
    @Test
    fun replyWindow_firstExpansionHidesPrefetchedRepliesAfterThree() {
        val window = paragraphReviewReplyWindow(
            loadedCount = 20,
            serverTotal = 20,
            serverHasMore = false,
            visibleLimit = initialParagraphReviewReplyVisibleLimit(),
        )

        assertEquals(3, window.visibleCount)
        assertEquals(10, window.nextBatchSize)
        assertEquals(ParagraphReviewReplyFooterAction.REVEAL_MORE, window.footerAction)
        assertFalse(window.shouldLoadMore)
    }

    /** 验证每次推进最多增加十条，并继续隐藏超过新上限的预取回复。 */
    @Test
    fun replyWindow_nextBatchAdvancesByTen() {
        val nextLimit = advanceParagraphReviewReplyVisibleLimit(
            initialParagraphReviewReplyVisibleLimit()
        )
        val window = paragraphReviewReplyWindow(
            loadedCount = 30,
            serverTotal = 30,
            serverHasMore = false,
            visibleLimit = nextLimit,
        )

        assertEquals(13, nextLimit)
        assertEquals(13, window.visibleCount)
        assertEquals(10, window.nextBatchSize)
        assertEquals(ParagraphReviewReplyFooterAction.REVEAL_MORE, window.footerAction)
        assertFalse(window.shouldLoadMore)
    }

    /** 验证已加载数量不足本批次且服务端有更多时继续请求独立 cursor 页。 */
    @Test
    fun replyWindow_requestsPagesUntilCurrentBatchIsAvailable() {
        val window = paragraphReviewReplyWindow(
            loadedCount = 5,
            serverTotal = 25,
            serverHasMore = true,
            visibleLimit = 13,
        )

        assertEquals(5, window.visibleCount)
        assertEquals(10, window.nextBatchSize)
        assertEquals(ParagraphReviewReplyFooterAction.REVEAL_MORE, window.footerAction)
        assertTrue(window.shouldLoadMore)
    }

    /** 验证最终不足十条的余量显示实际数量，全部可见后 footer 才切换为收起。 */
    @Test
    fun replyWindow_finalRemainderThenCollapse() {
        val beforeFinalBatch = paragraphReviewReplyWindow(
            loadedCount = 8,
            serverTotal = 8,
            serverHasMore = false,
            visibleLimit = 3,
        )
        val afterFinalBatch = paragraphReviewReplyWindow(
            loadedCount = 8,
            serverTotal = 8,
            serverHasMore = false,
            visibleLimit = 13,
        )

        assertEquals(3, beforeFinalBatch.visibleCount)
        assertEquals(5, beforeFinalBatch.nextBatchSize)
        assertEquals(
            ParagraphReviewReplyFooterAction.REVEAL_MORE,
            beforeFinalBatch.footerAction,
        )
        assertEquals(8, afterFinalBatch.visibleCount)
        assertEquals(0, afterFinalBatch.nextBatchSize)
        assertEquals(ParagraphReviewReplyFooterAction.COLLAPSE, afterFinalBatch.footerAction)
        assertFalse(afterFinalBatch.shouldLoadMore)
    }

    /** 验证 footer 显示最终五条时只推进五条，不会越过文案对应批次。 */
    @Test
    fun replyVisibleLimit_advancesByActualFooterBatch() {
        assertEquals(
            8,
            advanceParagraphReviewReplyVisibleLimit(
                currentLimit = 3,
                requestedBatchSize = 5,
            ),
        )
        assertEquals(
            13,
            advanceParagraphReviewReplyVisibleLimit(
                currentLimit = 3,
                requestedBatchSize = 50,
            ),
        )
    }

    /** 验证末页 total 大于已加载数时先展示隐藏项，随后报告不一致而不伪装收起。 */
    @Test
    fun replyWindow_terminalTotalMismatchDoesNotCollapseOrLoop() {
        val hiddenLoaded = paragraphReviewReplyWindow(
            loadedCount = 5,
            serverTotal = 8,
            serverHasMore = false,
            visibleLimit = 3,
        )
        val exhausted = paragraphReviewReplyWindow(
            loadedCount = 5,
            serverTotal = 8,
            serverHasMore = false,
            visibleLimit = 5,
        )

        assertEquals(2, hiddenLoaded.nextBatchSize)
        assertEquals(ParagraphReviewReplyFooterAction.REVEAL_MORE, hiddenLoaded.footerAction)
        assertFalse(hiddenLoaded.terminalInconsistent)
        assertEquals(ParagraphReviewReplyFooterAction.NONE, exhausted.footerAction)
        assertFalse(exhausted.shouldLoadMore)
        assertTrue(exhausted.terminalInconsistent)
    }

    /** 验证还有下一 cursor、但合并后节点数未增加时识别为停滞页。 */
    @Test
    fun replyPageProgress_rejectsStalledCursorPage() {
        assertTrue(isParagraphReviewReplyPageStalled(3, 3, serverHasMore = true))
        assertFalse(isParagraphReviewReplyPageStalled(3, 4, serverHasMore = true))
        assertFalse(isParagraphReviewReplyPageStalled(3, 3, serverHasMore = false))
    }

    /** 验证切换主评重新使用三条上限，不继承旧主评已推进的批次。 */
    @Test
    fun replyVisibleLimit_resetsWhenSwitchingComments() {
        val oldCommentLimit = advanceParagraphReviewReplyVisibleLimit(
            initialParagraphReviewReplyVisibleLimit()
        )
        val switchedCommentLimit = initialParagraphReviewReplyVisibleLimit()

        assertEquals(13, oldCommentLimit)
        assertEquals(3, switchedCommentLimit)
        assertEquals(
            ParagraphReviewReplyToggleAction.EXPAND,
            paragraphReviewReplyToggleAction("first", "second", 18),
        )
    }

    /** 验证旧 epoch 或不同主评的回复结果都不能提交到当前内联区域。 */
    @Test
    fun replyCommit_requiresMatchingEpochAndComment() {
        assertTrue(canCommitParagraphReviewReplyResult(3, 3, "comment", "comment"))
        assertFalse(canCommitParagraphReviewReplyResult(2, 3, "comment", "comment"))
        assertFalse(canCommitParagraphReviewReplyResult(3, 3, "old", "comment"))
        assertFalse(canCommitParagraphReviewReplyResult(3, 3, "comment", null))
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
        images = emptyList(),
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
        images = emptyList(),
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

    /** 创建合成段评图片领域对象。 */
    private fun image(url: String, width: Long, height: Long) = ParagraphCommentImage(
        url = url,
        width = width,
        height = height,
        format = null,
    )
}
