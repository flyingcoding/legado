package io.legado.app.model.review

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ReviewMemoryCacheTest {

    /** 验证索引 60 秒、分页 30 秒 TTL 使用可注入单调时钟。 */
    @Test
    fun cache_appliesEndpointTtl() = withCacheScope { scope ->
        var now = 0L
        val cache = ReviewMemoryCache(scope, clockMillis = { now })
        val indexKey = ReviewCacheKey.Index("source", "book", "item", "0")
        val pageKey = ReviewCacheKey.Comments("source", "book", "item", "0", 12, "")
        val firstIndex = index("first")
        val secondIndex = index("second")
        val firstPage = commentPage("first")
        val secondPage = commentPage("second")

        assertSame(firstIndex, cache.getOrLoad(indexKey) { firstIndex })
        assertSame(firstPage, cache.getOrLoad(pageKey) { firstPage })
        now = 30_000L
        assertSame(firstIndex, cache.getOrLoad(indexKey) { secondIndex })
        assertSame(secondPage, cache.getOrLoad(pageKey) { secondPage })
        now = 60_000L
        assertSame(secondIndex, cache.getOrLoad(indexKey) { secondIndex })
    }

    /** 验证同 key 并发只执行一次 loader 并共享同一结果。 */
    @Test
    fun cache_singleFlightSharesConcurrentLoad() = withCacheScope { scope ->
        val cache = ReviewMemoryCache(scope)
        val key = ReviewCacheKey.Index("source", "book", "item", "0")
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val expected = index("shared")

        val first = async {
            cache.getOrLoad(key) {
                calls.incrementAndGet()
                gate.await()
                expected
            }
        }
        val second = async {
            cache.getOrLoad(key) {
                calls.incrementAndGet()
                gate.await()
                index("unexpected")
            }
        }
        while (calls.get() == 0) yield()
        yield()
        assertEquals(1, calls.get())
        gate.complete(Unit)
        assertSame(expected, first.await())
        assertSame(expected, second.await())
    }

    /** 验证失败值不进入缓存且后续调用可以重新加载。 */
    @Test
    fun cache_doesNotStoreFailures() = withCacheScope { scope ->
        val cache = ReviewMemoryCache(scope)
        val key = ReviewCacheKey.Index("source", "book", "item", "0")
        val calls = AtomicInteger()
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                cache.getOrLoad(key) {
                    calls.incrementAndGet()
                    error("synthetic")
                }
            }
        }

        val success = cache.getOrLoad(key) {
            calls.incrementAndGet()
            index("success")
        }
        assertEquals("success", success.itemVersion)
        assertEquals(2, calls.get())
    }

    /** 验证失效中的请求即使延迟结束也不能把旧成功值重新写入缓存。 */
    @Test
    fun cache_invalidationPreventsStaleFlightWriteBack() = withCacheScope { scope ->
        val cache = ReviewMemoryCache(scope)
        val key = ReviewCacheKey.Index("source", "book", "item", "0")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleLoad = async {
            runCatching {
                cache.getOrLoad(key) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    index("stale")
                }
            }
        }

        started.await()
        cache.invalidateResource("source", "book", "item")
        release.complete(Unit)
        staleLoad.await()

        assertNull(cache.get(key))
        assertEquals("fresh", cache.getOrLoad(key) { index("fresh") }.itemVersion)
    }

    /** 验证主动刷新不复用旧请求，且被替换请求不能覆盖新缓存。 */
    @Test
    fun cache_forceStartsFreshFlightAndPreventsStaleWriteBack() = withCacheScope { scope ->
        val cache = ReviewMemoryCache(scope)
        val key = ReviewCacheKey.Index("source", "book", "item", "0")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleLoad = async {
            runCatching {
                cache.getOrLoad(key) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    index("stale")
                }
            }
        }

        started.await()
        val fresh = cache.getOrLoad(key, force = true) { index("fresh") }
        release.complete(Unit)

        assertTrue(staleLoad.await().isFailure)
        assertEquals("fresh", fresh.itemVersion)
        assertSame(fresh, cache.get(key))
    }

    /** 验证阅读生命周期关闭时会实际取消缓存 scope 中仍在执行的网络 loader。 */
    @Test
    fun cache_cancelInFlightActuallyCancelsLoader() = withCacheScope { scope ->
        val cache = ReviewMemoryCache(scope)
        val key = ReviewCacheKey.Index("source", "book", "item", "0")
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val load = async {
            runCatching {
                cache.getOrLoad(key) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
        }

        started.await()
        cache.cancelInFlight()

        cancelled.await()
        assertTrue(load.await().isFailure)
        assertNull(cache.get(key))
    }

    /** 验证取消后立即同 key 请求会启动新 loader，旧 flight 不能被复用或写回。 */
    @Test
    fun cache_cancelInFlightAllowsImmediateReplacement() = withCacheScope { scope ->
        val cache = ReviewMemoryCache(scope)
        val key = ReviewCacheKey.Index("source", "book", "item", "0")
        val staleStarted = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val staleLoad = async {
            runCatching {
                cache.getOrLoad(key) {
                    staleStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { releaseStale.await() }
                    }
                }
            }
        }

        staleStarted.await()
        cache.cancelInFlight()
        val freshLoad = async {
            cache.getOrLoad(key) {
                replacementStarted.complete(Unit)
                index("fresh")
            }
        }

        try {
            withTimeout(1_000L) { replacementStarted.await() }
            val fresh = freshLoad.await()
            assertEquals("fresh", fresh.itemVersion)
            assertSame(fresh, cache.get(key))
        } finally {
            releaseStale.complete(Unit)
        }
        assertTrue(staleLoad.await().isFailure)
    }

    /** 验证资源失效只清理目标章节并保留其他 source。 */
    @Test
    fun cache_invalidatesResourceNamespace() = withCacheScope { scope ->
        val cache = ReviewMemoryCache(scope)
        val targetIndex = ReviewCacheKey.Index("source-a", "book", "item", "0")
        val targetPage = ReviewCacheKey.Comments("source-a", "book", "item", "0", 12, "")
        val targetReplies = ReviewCacheKey.Replies("source-a", "book", "item", "comment", "")
        val other = ReviewCacheKey.Index("source-b", "book", "item", "0")

        cache.getOrLoad(targetIndex) { index("target") }
        cache.getOrLoad(targetPage) { commentPage("target") }
        cache.getOrLoad(targetReplies) { replyPage("target") }
        val otherValue = cache.getOrLoad(other) { index("other") }
        cache.invalidateResource("source-a", "book", "item")

        assertNull(cache.get(targetIndex))
        assertNull(cache.get(targetPage))
        assertNull(cache.get(targetReplies))
        assertSame(otherValue, cache.get(other))
    }

    /** 验证容量上限按最近访问顺序淘汰。 */
    @Test
    fun cache_evictsLeastRecentlyUsedEntry() = withCacheScope { scope ->
        val cache = ReviewMemoryCache(scope, capacity = 2)
        val first = ReviewCacheKey.Index("source", "book", "1", "0")
        val second = ReviewCacheKey.Index("source", "book", "2", "0")
        val third = ReviewCacheKey.Index("source", "book", "3", "0")
        cache.getOrLoad(first) { index("1") }
        cache.getOrLoad(second) { index("2") }
        cache.get(first)
        cache.getOrLoad(third) { index("3") }

        assertNull(cache.get(second))
        assertEquals("1", cache.get(first)?.itemVersion)
        assertEquals("3", cache.get(third)?.itemVersion)
    }

    /** 在独立 SupervisorJob 中运行缓存测试并确保清理后台 scope。 */
    private fun withCacheScope(block: suspend kotlinx.coroutines.CoroutineScope.(CoroutineScope) -> Unit) {
        val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { block(cacheScope) }
        } finally {
            cacheScope.cancel()
        }
    }

    /** 创建最小章节索引缓存值。 */
    private fun index(version: String): ReviewIndex = ReviewIndex(
        itemId = "item",
        bookId = "book",
        itemVersion = version,
        paragraphs = emptyList(),
        partial = false,
        warnings = emptyList(),
    )

    /** 创建最小主评分页缓存值。 */
    private fun commentPage(version: String): ParagraphCommentPage = ParagraphCommentPage(
        itemId = "item",
        bookId = "book",
        itemVersion = version,
        paraId = 12,
        comments = emptyList(),
        total = 0,
        hasMore = false,
        nextCursor = "",
    )

    /** 创建最小回复分页缓存值。 */
    private fun replyPage(id: String): ParagraphReplyPage = ParagraphReplyPage(
        itemId = "item",
        bookId = "book",
        commentId = id,
        replies = emptyList(),
        total = 0,
        hasMore = false,
        nextCursor = "",
    )
}
