package io.legado.app.model.review

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 统一拥有阅读页索引 job、generation、内存缓存和映射门禁。 */
class ParagraphReviewReaderController(
    private val scope: CoroutineScope,
    private val repository: ParagraphReviewRepository = DefaultParagraphReviewRepository(),
    private val mapperRegistry: ParagraphReviewMapperRegistry = ParagraphReviewMapperRegistry(),
    private val onStateChanged: (ParagraphReviewReaderState) -> Unit = {},
) {

    private val cacheScope = CoroutineScope(
        SupervisorJob(scope.coroutineContext[Job]) + IO
    )
    private val cache = ReviewMemoryCache(cacheScope)
    private val reducer = ParagraphReviewGenerationReducer()
    private var indexJob: Job? = null
    private var sourceUrl: String? = null

    val state: ParagraphReviewReaderState
        get() = reducer.state

    /** 返回当前 generation 可安全用于排版的 Verified 数据。 */
    fun layoutData(): ParagraphReviewLayoutData {
        val generation = reducer.state.generation ?: return ParagraphReviewLayoutData.EMPTY
        return ParagraphReviewLayoutData.fromVerified(generation, reducer.state.mapping)
    }

    /** 在能力、偏好和章节身份均有效时异步加载当前章索引。 */
    fun loadIndex(
        source: BookSource,
        bookUrl: String,
        chapter: BookChapter,
        contentHash: String,
        localParagraphCount: Int,
        force: Boolean = false,
    ) {
        if (!source.supportsParagraphCommentsV1() || contentHash.isBlank()) {
            disable()
            return
        }
        val identity = chapter.reviewIdentityOrNull() ?: run {
            disable()
            return
        }
        val current = reducer.state.generation
        if (!force && current != null &&
            current.sourceUrl == source.bookSourceUrl &&
            current.bookUrl == bookUrl &&
            current.chapterIndex == chapter.index &&
            current.itemId == identity.itemId &&
            current.contentHash == contentHash
        ) {
            return
        }
        val oldSourceUrl = sourceUrl
        sourceUrl = source.bookSourceUrl
        cache.cancelInFlight()
        indexJob?.cancel()
        if (oldSourceUrl != null && oldSourceUrl != source.bookSourceUrl) {
            cacheScope.launch { cache.invalidateSource(oldSourceUrl) }
        }
        val generation = reducer.begin(
            sourceUrl = source.bookSourceUrl,
            bookUrl = bookUrl,
            chapterIndex = chapter.index,
            itemId = identity.itemId,
            contentHash = contentHash,
        )
        onStateChanged(reducer.state)
        indexJob = scope.launch(IO) {
            try {
                if (force) {
                    cache.invalidateResource(
                        sourceUrl = source.bookSourceUrl,
                        bookId = identity.bookId,
                        itemId = identity.itemId,
                    )
                }
                val request = ReviewIndexRequest(
                    bookId = identity.bookId,
                    itemId = identity.itemId,
                    itemVersion = identity.itemVersion,
                )
                val index = cache.getOrLoad(
                    ReviewCacheKey.Index(
                        sourceUrl = source.bookSourceUrl,
                        bookId = identity.bookId,
                        itemId = identity.itemId,
                        itemVersion = identity.itemVersion,
                    ),
                    force = force,
                ) {
                    repository.loadIndex(source, request)
                }
                ensureActive()
                val mapping = mapperRegistry.map(
                    ParagraphReviewMappingInput(
                        sourceUrl = source.bookSourceUrl,
                        itemId = identity.itemId,
                        itemVersion = index.itemVersion,
                        contentHash = contentHash,
                        localParagraphCount = localParagraphCount,
                        reviewIndex = index,
                    )
                )
                if (reducer.commit(generation, ReviewIndexLoadState.Content(index), mapping)) {
                    onStateChanged(reducer.state)
                }
            } catch (_: CancellationException) {
                // 取消是正常生命周期，不提交错误状态。
            } catch (error: Throwable) {
                val retryable = (error as? ReviewException)?.retryable == true
                if (reducer.commit(generation, ReviewIndexLoadState.Error(retryable))) {
                    onStateChanged(reducer.state)
                }
            }
        }
    }

    /** 取消在途索引并使旧 generation、映射和点击全部失效。 */
    fun disable() {
        if (indexJob == null && reducer.state.generation == null) return
        cache.cancelInFlight()
        indexJob?.cancel()
        indexJob = null
        reducer.invalidate()
        onStateChanged(reducer.state)
    }

    /** 判断 ReviewColumn 点击 token 是否仍属于当前章。 */
    fun acceptsClick(generation: Long): Boolean = reducer.acceptsClick(generation)

    /** 取消全部工作并释放 controller 自有缓存 scope。 */
    fun close() {
        disable()
        cacheScope.cancel()
    }
}

/** 判断当前书源是否完整声明 fanqie 段评 v1 只读能力。 */
fun BookSource?.supportsParagraphCommentsV1(): Boolean =
    this?.ruleReview?.supportsParagraphCommentsV1() == true

/** 判断在线书籍、书源能力和用户偏好共同形成的有效启用状态。 */
fun isParagraphReviewEffective(
    isLocalBook: Boolean,
    source: BookSource?,
    userEnabled: Boolean,
): Boolean = !isLocalBook && userEnabled && source.supportsParagraphCommentsV1()
