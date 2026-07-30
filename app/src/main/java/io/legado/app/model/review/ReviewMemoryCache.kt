package io.legado.app.model.review

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 为三类段评成功值提供强类型缓存键。 */
sealed interface ReviewCacheKey<T : Any> {
    val sourceUrl: String
    val bookId: String
    val itemId: String

    /** 标识章节索引缓存。 */
    data class Index(
        override val sourceUrl: String,
        override val bookId: String,
        override val itemId: String,
        val itemVersion: String,
    ) : ReviewCacheKey<ReviewIndex>

    /** 标识单段主评 cursor 页缓存。 */
    data class Comments(
        override val sourceUrl: String,
        override val bookId: String,
        override val itemId: String,
        val itemVersion: String,
        val paraId: Int,
        val cursor: String,
    ) : ReviewCacheKey<ParagraphCommentPage>

    /** 标识单条主评回复 cursor 页缓存。 */
    data class Replies(
        override val sourceUrl: String,
        override val bookId: String,
        override val itemId: String,
        val commentId: String,
        val cursor: String,
    ) : ReviewCacheKey<ParagraphReplyPage>
}

/** 提供有界 TTL 缓存和同 key single-flight。 */
class ReviewMemoryCache(
    private val scope: CoroutineScope,
    private val capacity: Int = 128,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    private data class Entry(
        val value: Any,
        val createdAtMillis: Long,
    )

    private data class Flight(
        val token: Any,
        val deferred: Deferred<Any>,
    )

    private data class ActiveFlight(
        val key: ReviewCacheKey<*>,
        val deferred: Deferred<Any>,
    )

    private val mutex = Mutex()
    private val entries = LinkedHashMap<ReviewCacheKey<*>, Entry>(16, 0.75f, true)
    private val flights = mutableMapOf<ReviewCacheKey<*>, Flight>()
    private val activeFlights = ConcurrentHashMap<Any, ActiveFlight>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** 返回未过期成功值，缺失或过期时返回 null。 */
    suspend fun <T : Any> get(key: ReviewCacheKey<T>): T? = mutex.withLock {
        getLocked(key)
    }

    /** 获取缓存或共享同 key 请求，并且只缓存成功结果。 */
    suspend fun <T : Any> getOrLoad(
        key: ReviewCacheKey<T>,
        force: Boolean = false,
        loader: suspend () -> T,
    ): T {
        val deferred = mutex.withLock {
            if (force) {
                entries.remove(key)
                flights.remove(key)?.let { flight ->
                    activeFlights.remove(flight.token)
                    flight.deferred.cancel()
                }
            } else {
                getLocked<T>(key)?.let { return it }
                flights[key]?.let { flight ->
                    if (activeFlights.containsKey(flight.token) &&
                        !flight.deferred.isCompleted
                    ) {
                        return@withLock flight.deferred
                    }
                    flights.remove(key)
                }
            }
            val token = Any()
            val created = scope.async<Any>(start = CoroutineStart.LAZY) {
                try {
                    loader().also { value -> putSuccess(key, value, token) }
                } finally {
                    activeFlights.remove(token)
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (flights[key]?.token === token) flights.remove(key)
                        }
                    }
                }
            }
            activeFlights[token] = ActiveFlight(key, created)
            flights[key] = Flight(token, created)
            created
        }
        deferred.start()
        @Suppress("UNCHECKED_CAST")
        return deferred.await() as T
    }

    /** 立即取消当前缓存拥有的全部在途请求，同时保留已经成功的 TTL 缓存。 */
    fun cancelInFlight() {
        cancelActiveFlights { true }
    }

    /** 立即取消主评分页在途请求，不影响同时加载的回复或已成功缓存。 */
    fun cancelCommentFlights() {
        cancelMatchingFlights { it is ReviewCacheKey.Comments }
    }

    /** 立即取消回复分页在途请求，不影响同时加载的主评或已成功缓存。 */
    fun cancelReplyFlights() {
        cancelMatchingFlights { it is ReviewCacheKey.Replies }
    }

    /** 失效指定 source 下的全部缓存与请求。 */
    suspend fun invalidateSource(sourceUrl: String) {
        invalidateMatching { it.sourceUrl == sourceUrl }
    }

    /** 失效指定章节资源的索引、主评和回复缓存。 */
    suspend fun invalidateResource(sourceUrl: String, bookId: String, itemId: String) {
        invalidateMatching {
            it.sourceUrl == sourceUrl && it.bookId == bookId && it.itemId == itemId
        }
    }

    /** 失效指定单段的全部主评 cursor 页。 */
    suspend fun invalidateComments(
        sourceUrl: String,
        bookId: String,
        itemId: String,
        paraId: Int,
    ) {
        invalidateMatching {
            it is ReviewCacheKey.Comments && it.sourceUrl == sourceUrl && it.bookId == bookId &&
                it.itemId == itemId && it.paraId == paraId
        }
    }

    /** 失效指定主评的全部回复 cursor 页。 */
    suspend fun invalidateReplies(
        sourceUrl: String,
        bookId: String,
        itemId: String,
        commentId: String,
    ) {
        invalidateMatching {
            it is ReviewCacheKey.Replies && it.sourceUrl == sourceUrl && it.bookId == bookId &&
                it.itemId == itemId && it.commentId == commentId
        }
    }

    /** 清空全部缓存并取消仍在执行的 single-flight。 */
    suspend fun clear() {
        invalidateMatching { true }
    }

    /** 在锁内读取未过期值并移除过期条目。 */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> getLocked(key: ReviewCacheKey<T>): T? {
        val entry = entries[key] ?: return null
        val now = clockMillis()
        val age = now - entry.createdAtMillis
        if (age < 0L || age >= ttlMillis(key)) {
            entries.remove(key)
            return null
        }
        return entry.value as T
    }

    /** 在成功后写入缓存并按最近访问顺序淘汰超额条目。 */
    private suspend fun <T : Any> putSuccess(key: ReviewCacheKey<T>, value: T, token: Any) {
        mutex.withLock {
            if (activeFlights[token]?.key != key || flights[key]?.token !== token) {
                return@withLock
            }
            entries[key] = Entry(value, clockMillis())
            while (entries.size > capacity) {
                entries.entries.iterator().run {
                    next()
                    remove()
                }
            }
        }
    }

    /** 返回索引 60 秒、分页 30 秒的固定 TTL。 */
    private fun ttlMillis(key: ReviewCacheKey<*>): Long = when (key) {
        is ReviewCacheKey.Index -> 60_000L
        is ReviewCacheKey.Comments,
        is ReviewCacheKey.Replies,
        -> 30_000L
    }

    /** 原子移除匹配键并取消关联 single-flight。 */
    private suspend fun invalidateMatching(predicate: (ReviewCacheKey<*>) -> Boolean) {
        val cancelled = mutex.withLock {
            entries.keys.removeAll(predicate)
            flights.filterKeys(predicate).also { matched ->
                matched.keys.forEach(flights::remove)
            }.values.toList()
        }
        cancelled.forEach { flight ->
            activeFlights.remove(flight.token)
            flight.deferred.cancel()
        }
    }

    /** 按缓存键同步取消匹配的 loader，供 UI 生命周期在新请求启动前止住旧网络。 */
    private fun cancelMatchingFlights(predicate: (ReviewCacheKey<*>) -> Boolean) {
        cancelActiveFlights(predicate)
    }

    /** 同步摘除并取消匹配 flight，避免立即替换请求复用已取消 loader。 */
    private fun cancelActiveFlights(predicate: (ReviewCacheKey<*>) -> Boolean) {
        activeFlights.entries.toList().forEach { (token, flight) ->
            if (predicate(flight.key) && activeFlights.remove(token, flight)) {
                flight.deferred.cancel()
            }
        }
    }
}
