package io.legado.app.model.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAggregationTest {

    /** 验证分页成功状态不依赖列表非空，供首屏与加载更多重试正确分流。 */
    @Test
    fun accumulator_tracksLoadedEmptyPage() {
        val accumulator = CursorPageAccumulator<String> { it }
        assertFalse(accumulator.hasLoadedPage())

        accumulator.append(null, emptyList(), 0, false, "")

        assertTrue(accumulator.hasLoadedPage())
        accumulator.reset()
        assertFalse(accumulator.hasLoadedPage())
    }

    /** 验证主评跨页按 ID 去重、更新内容且保持首次出现顺序。 */
    @Test
    fun accumulator_deduplicatesAndPreservesServerOrder() {
        val accumulator = CursorPageAccumulator<ParagraphComment>(ParagraphComment::commentId)
        accumulator.append(
            requestedCursor = null,
            items = listOf(comment("1", "old"), comment("2", "second")),
            total = 3,
            hasMore = true,
            nextCursor = "next-1",
        )
        accumulator.append(
            requestedCursor = "next-1",
            items = listOf(comment("1", "new"), comment("3", "third")),
            total = 3,
            hasMore = false,
            nextCursor = "ignored",
        )

        val snapshot = accumulator.snapshot()
        assertEquals(listOf("1", "2", "3"), snapshot.items.map(ParagraphComment::commentId))
        assertEquals("new", snapshot.items.first().text)
        assertEquals("", snapshot.nextCursor)
    }

    /** 验证空 cursor、重复 cursor 和跳过串行 cursor 都成为协议错误。 */
    @Test
    fun accumulator_rejectsInvalidCursorChains() {
        val empty = CursorPageAccumulator<String> { it }
        assertThrows(ReviewException.Protocol::class.java) {
            empty.append(null, emptyList(), 0, true, "")
        }

        val repeated = CursorPageAccumulator<String> { it }
        repeated.append(null, listOf("1"), 2, true, "next")
        assertThrows(ReviewException.Protocol::class.java) {
            repeated.append("next", listOf("2"), 2, true, "next")
        }

        val skipped = CursorPageAccumulator<String> { it }
        skipped.append(null, listOf("1"), 2, true, "expected")
        assertThrows(ReviewException.Protocol::class.java) {
            skipped.append("other", listOf("2"), 2, false, "")
        }
    }

    /** 验证父回复在后页到达后会把先到的 orphan 重新挂接。 */
    @Test
    fun replyTree_attachesChildWhenParentArrivesLater() {
        val child = reply("child", parentId = "parent")
        val parent = reply("parent")
        val tree = ParagraphReplyTreeBuilder.build(listOf(child, parent))

        assertEquals(listOf("parent"), tree.map(ParagraphReply::replyId))
        assertEquals(listOf("child"), tree.single().children.map(ParagraphReply::replyId))
    }

    /** 验证父缺失、自环和双节点环均保留为稳定根节点。 */
    @Test
    fun replyTree_preservesOrphansAndBreaksCycles() {
        val orphanTree = ParagraphReplyTreeBuilder.build(listOf(reply("orphan", "missing")))
        assertEquals("orphan", orphanTree.single().replyId)

        val cyclic = ParagraphReplyTreeBuilder.build(
            listOf(
                reply("self", "self"),
                reply("a", "b"),
                reply("b", "a"),
            )
        )
        assertEquals(listOf("self", "a", "b"), cyclic.map(ParagraphReply::replyId))
        assertTrue(cyclic.all { it.children.isEmpty() })
    }

    /** 验证重复回复使用后页内容但保持首次出现位置。 */
    @Test
    fun replyTree_updatesDuplicateWithoutReordering() {
        val tree = ParagraphReplyTreeBuilder.build(
            listOf(
                reply("1", text = "old"),
                reply("2", text = "second"),
                reply("1", text = "new"),
            )
        )
        assertEquals(listOf("1", "2"), tree.map(ParagraphReply::replyId))
        assertEquals("new", tree.first().text)
    }

    /** 创建最小主评领域对象。 */
    private fun comment(id: String, text: String): ParagraphComment = ParagraphComment(
        commentId = id,
        text = text,
        userId = null,
        userName = null,
        userAvatar = null,
        createTimestamp = 0,
        diggCount = 0,
        replyCount = 0,
        repliesLoaded = false,
        replies = emptyList(),
        replyTotal = null,
        replyHasMore = null,
        replyNextCursor = null,
    )

    /** 创建最小回复领域对象。 */
    private fun reply(
        id: String,
        parentId: String? = null,
        text: String = id,
    ): ParagraphReply = ParagraphReply(
        replyId = id,
        parentReplyId = null,
        replyToCommentId = "3003",
        replyToReplyId = parentId,
        text = text,
        userId = null,
        userName = null,
        userAvatar = null,
        replyToUserName = null,
        createTimestamp = 0,
        diggCount = 0,
        replyCount = 0,
        children = emptyList(),
    )
}
