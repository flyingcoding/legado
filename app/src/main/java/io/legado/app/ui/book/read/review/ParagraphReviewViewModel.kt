package io.legado.app.ui.book.read.review

import android.app.Application
import android.os.Bundle
import android.os.Parcelable
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.model.review.CursorPageAccumulator
import io.legado.app.model.review.DefaultParagraphReviewRepository
import io.legado.app.model.review.ParagraphComment
import io.legado.app.model.review.ParagraphCommentPageRequest
import io.legado.app.model.review.ParagraphReply
import io.legado.app.model.review.ParagraphReplyPageRequest
import io.legado.app.model.review.ParagraphReplyTreeBuilder
import io.legado.app.model.review.ParagraphReviewRepository
import io.legado.app.model.review.ReviewCacheKey
import io.legado.app.model.review.ReviewException
import io.legado.app.model.review.ReviewMemoryCache
import io.legado.app.model.review.supportsParagraphCommentsV1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 为段评底部抽屉维护独立的主评分页和单条内联回复分页状态。 */
class ParagraphReviewViewModel(
    application: Application,
) : BaseViewModel(application) {

    private val repository: ParagraphReviewRepository = DefaultParagraphReviewRepository()
    private val cacheScope = CoroutineScope(
        SupervisorJob(viewModelScope.coroutineContext[Job]) + IO
    )
    private val cache = ReviewMemoryCache(cacheScope)
    private var commentAccumulator = CursorPageAccumulator(ParagraphComment::commentId)
    private var replyAccumulator = CursorPageAccumulator(ParagraphReply::replyId)
    private var commentLoadJob: Job? = null
    private var replyLoadJob: Job? = null
    private var commentLoadEpoch = 0L
    private var replyLoadEpoch = 0L
    private var replyVisibleLimit = 0
    private var initialized = false
    private lateinit var sourceUrl: String
    private lateinit var bookId: String
    private lateinit var itemId: String
    private lateinit var itemVersion: String
    private var paraId: Int = -1
    var generation: Long = -1L
        private set
    private var indexPartial = false
    private var initialCommentCount = 0

    private val _state = MutableStateFlow(ParagraphReviewCommentUiState())
    val state = _state.asStateFlow()
    private val _replyState = MutableStateFlow(ParagraphReviewReplyUiState())
    val replyState = _replyState.asStateFlow()

    var commentListState: Parcelable? = null

    /** 从不可变参数初始化一次并加载主评第一页。 */
    fun init(arguments: Bundle?) {
        if (initialized) return
        val args = arguments ?: return
        sourceUrl = args.getString(ARG_SOURCE_URL).orEmpty()
        bookId = args.getString(ARG_BOOK_ID).orEmpty()
        itemId = args.getString(ARG_ITEM_ID).orEmpty()
        itemVersion = args.getString(ARG_ITEM_VERSION).orEmpty()
        paraId = args.getInt(ARG_PARA_ID, -1)
        generation = args.getLong(ARG_GENERATION, -1L)
        indexPartial = args.getBoolean(ARG_PARTIAL, false)
        initialCommentCount = resolveParagraphReviewCommentTotal(
            indexCount = args.getInt(ARG_COMMENT_COUNT, 0),
            serverTotal = null,
        )
        initialized = sourceUrl.isNotBlank() && bookId.isNotBlank() &&
            itemId.isNotBlank() && itemVersion.isNotBlank() && paraId >= 0 && generation >= 0
        if (!initialized) {
            _state.value = ParagraphReviewCommentUiState(
                error = ParagraphReviewUiError(ParagraphReviewUiErrorKind.GENERIC, false)
            )
            return
        }
        _state.value = ParagraphReviewCommentUiState(total = initialCommentCount)
        loadFirstCommentPage(force = false)
    }

    /** 收起旧回复并强制刷新主评第一页。 */
    fun refreshComments() {
        if (!initialized) return
        closeReplies()
        loadFirstCommentPage(force = true)
    }

    /** 仅在目标仍为当前展开主评时强制刷新其回复第一页。 */
    fun refreshReplies(commentId: String) {
        if (!initialized || _replyState.value.comment?.commentId != commentId) return
        loadFirstReplyPage(force = true)
    }

    /** 按主评当前成功页状态重试第一页或失败的下一页。 */
    fun retryComments() {
        if (!initialized) return
        if (!commentAccumulator.hasLoadedPage()) loadFirstCommentPage(force = true)
        else loadMoreComments()
    }

    /** 仅在目标仍为当前展开主评时重试回复第一页或失败的下一页。 */
    fun retryReplies(commentId: String) {
        if (!initialized || _replyState.value.comment?.commentId != commentId) return
        if (!replyAccumulator.hasLoadedPage()) {
            loadFirstReplyPage(force = true)
            return
        }
        val snapshot = replyAccumulator.snapshot()
        if (snapshot.hasMore && snapshot.nextCursor.isNotBlank()) {
            loadReplyPagesUntilVisible(
                cursor = snapshot.nextCursor,
                force = false,
                refreshing = false,
            )
        } else {
            loadFirstReplyPage(force = true)
        }
    }

    /** 忽略无回复或已选主评，并在点击其他主评时切换内联回复。 */
    fun toggleReplies(comment: ParagraphComment) {
        if (!initialized) return
        when (
            paragraphReviewReplyToggleAction(
                selectedCommentId = _replyState.value.comment?.commentId,
                clickedCommentId = comment.commentId,
                replyCount = comment.replyCount,
            )
        ) {
            ParagraphReviewReplyToggleAction.IGNORE -> return
            ParagraphReviewReplyToggleAction.EXPAND -> openReplies(comment)
        }
    }

    /** 选择主评、取消旧 flight，并优先发布合同中携带的 eager 回复。 */
    private fun openReplies(comment: ParagraphComment) {
        replyLoadEpoch++
        cache.cancelReplyFlights()
        replyLoadJob?.cancel()
        replyLoadJob = null
        replyAccumulator = CursorPageAccumulator(ParagraphReply::replyId)
        replyVisibleLimit = initialParagraphReviewReplyVisibleLimit()
        _replyState.value = ParagraphReviewReplyUiState(
            comment = comment,
            total = comment.replyTotal ?: comment.replyCount,
            visibleLimit = replyVisibleLimit,
            initialLoading = !comment.repliesLoaded,
        )
        if (!comment.repliesLoaded) {
            loadReplyPagesUntilVisible(cursor = null, force = false, refreshing = false)
            return
        }
        runCatching {
            replyAccumulator.append(
                requestedCursor = null,
                items = comment.replies,
                total = comment.replyTotal ?: comment.replyCount,
                hasMore = comment.replyHasMore == true,
                nextCursor = comment.replyNextCursor.orEmpty(),
            )
        }.onSuccess {
            val window = publishReplySnapshot(comment)
            val snapshot = replyAccumulator.snapshot()
            if (window.shouldLoadMore && snapshot.nextCursor.isNotBlank()) {
                loadReplyPagesUntilVisible(
                    cursor = snapshot.nextCursor,
                    force = false,
                    refreshing = false,
                )
            }
        }.onFailure { error ->
            _replyState.value = ParagraphReviewReplyUiState(
                comment = comment,
                error = error.toUiError(),
            )
        }
    }

    /** 返回主评层并取消仍在执行的回复请求。 */
    fun closeReplies() {
        replyLoadEpoch++
        cache.cancelReplyFlights()
        replyLoadJob?.cancel()
        replyLoadJob = null
        replyAccumulator = CursorPageAccumulator(ParagraphReply::replyId)
        replyVisibleLimit = 0
        _replyState.value = ParagraphReviewReplyUiState()
    }

    /** 在上一主评页声明 hasMore 时仅加载主评下一页。 */
    fun loadMoreComments() {
        if (!initialized) return
        if (commentLoadJob?.isActive == true) return
        val snapshot = commentAccumulator.snapshot()
        if (!snapshot.hasMore || snapshot.nextCursor.isBlank()) return
        loadCommentPage(cursor = snapshot.nextCursor, force = false, refreshing = false)
    }

    /** 推进当前主评下一可见批次，全部可见时由同一 footer 一次性收起。 */
    fun loadMoreReplies(commentId: String) {
        if (!initialized || _replyState.value.comment?.commentId != commentId) return
        if (replyLoadJob?.isActive == true) return
        when (_replyState.value.footerAction) {
            ParagraphReviewReplyFooterAction.NONE -> return
            ParagraphReviewReplyFooterAction.COLLAPSE -> {
                closeReplies()
                return
            }
            ParagraphReviewReplyFooterAction.REVEAL_MORE -> Unit
        }
        replyVisibleLimit = advanceParagraphReviewReplyVisibleLimit(
            currentLimit = replyVisibleLimit,
            requestedBatchSize = _replyState.value.nextBatchSize,
        )
        val comment = _replyState.value.comment ?: return
        val window = publishReplySnapshot(comment)
        val snapshot = replyAccumulator.snapshot()
        if (window.shouldLoadMore && snapshot.nextCursor.isNotBlank()) {
            loadReplyPagesUntilVisible(
                cursor = snapshot.nextCursor,
                force = false,
                refreshing = false,
            )
        }
    }

    /** 重置主评聚合器并进入首屏 loading 或保留内容的刷新状态。 */
    private fun loadFirstCommentPage(force: Boolean) {
        commentLoadEpoch++
        if (force) cache.cancelCommentFlights()
        commentLoadJob?.cancel()
        commentLoadJob = null
        commentAccumulator = CursorPageAccumulator(ParagraphComment::commentId)
        val retained = _state.value.takeIf { force }
        _state.value = ParagraphReviewCommentUiState(
            items = retained?.items.orEmpty(),
            total = retained?.total ?: _state.value.total,
            initialLoading = retained?.items.isNullOrEmpty(),
            refreshing = !retained?.items.isNullOrEmpty(),
            hasMore = retained?.hasMore == true,
            partial = indexPartial,
        )
        loadCommentPage(cursor = null, force = force, refreshing = _state.value.refreshing)
    }

    /** 从 Room 取得当前书源后加载并严格合并一个 cursor 页。 */
    private fun loadCommentPage(cursor: String?, force: Boolean, refreshing: Boolean) {
        if (commentLoadJob?.isActive == true) return
        val loadEpoch = commentLoadEpoch
        _state.update { current ->
            current.copy(
                initialLoading = cursor == null && !refreshing,
                refreshing = refreshing,
                loadingMore = cursor != null,
                error = null,
            )
        }
        commentLoadJob = viewModelScope.launch(IO) {
            try {
                val source = appDb.bookSourceDao.getBookSource(sourceUrl)
                    ?.takeIf { it.supportsParagraphCommentsV1() }
                    ?: throw ReviewException.UnsupportedSource()
                if (cursor == null && force) {
                    cache.invalidateComments(sourceUrl, bookId, itemId, paraId)
                }
                val request = ParagraphCommentPageRequest(
                    bookId = bookId,
                    itemId = itemId,
                    paraId = paraId,
                    itemVersion = itemVersion,
                    cursor = cursor,
                )
                val page = cache.getOrLoad(
                    ReviewCacheKey.Comments(
                        sourceUrl = sourceUrl,
                        bookId = bookId,
                        itemId = itemId,
                        itemVersion = itemVersion,
                        paraId = paraId,
                        cursor = cursor.orEmpty(),
                    ),
                    force = force,
                ) {
                    repository.loadCommentPage(source, request, commentAccumulator.seenCursors())
                }
                ensureActive()
                if (loadEpoch != commentLoadEpoch) return@launch
                commentAccumulator.append(
                    requestedCursor = cursor,
                    items = page.comments,
                    total = page.total,
                    hasMore = page.hasMore,
                    nextCursor = page.nextCursor,
                )
                val snapshot = commentAccumulator.snapshot()
                _state.value = ParagraphReviewCommentUiState(
                    items = snapshot.items,
                    total = resolveParagraphReviewCommentTotal(
                        indexCount = initialCommentCount,
                        serverTotal = snapshot.total,
                    ),
                    hasMore = snapshot.hasMore,
                    partial = indexPartial,
                )
            } catch (_: CancellationException) {
                // 取消不映射为 UI 错误。
            } catch (error: Throwable) {
                if (loadEpoch != commentLoadEpoch) return@launch
                val snapshot = commentAccumulator.snapshot()
                val retained = _state.value
                _state.value = ParagraphReviewCommentUiState(
                    items = snapshot.items.ifEmpty { retained.items },
                    total = if (commentAccumulator.hasLoadedPage()) snapshot.total else retained.total,
                    hasMore = snapshot.hasMore || retained.hasMore,
                    partial = indexPartial,
                    error = error.toUiError(),
                )
            }
        }
    }

    /** 重置回复聚合器并进入首屏 loading 或保留内容的刷新状态。 */
    private fun loadFirstReplyPage(force: Boolean) {
        val comment = _replyState.value.comment ?: return
        replyLoadEpoch++
        if (force) cache.cancelReplyFlights()
        replyLoadJob?.cancel()
        replyLoadJob = null
        replyAccumulator = CursorPageAccumulator(ParagraphReply::replyId)
        replyVisibleLimit = initialParagraphReviewReplyVisibleLimit()
        val retained = _replyState.value.takeIf { force }
        _replyState.value = ParagraphReviewReplyUiState(
            comment = comment,
            items = retained?.items.orEmpty(),
            loadedCount = retained?.items?.size ?: 0,
            total = retained?.total ?: comment.replyTotal ?: comment.replyCount,
            visibleLimit = replyVisibleLimit,
            initialLoading = retained?.items.isNullOrEmpty(),
            refreshing = !retained?.items.isNullOrEmpty(),
            hasMore = retained?.hasMore == true,
        )
        loadReplyPagesUntilVisible(
            cursor = null,
            force = force,
            refreshing = _replyState.value.refreshing,
        )
    }

    /** 连续合并回复 cursor 页，直到当前可见批次已满足或服务端无更多。 */
    private fun loadReplyPagesUntilVisible(
        cursor: String?,
        force: Boolean,
        refreshing: Boolean,
    ) {
        val comment = _replyState.value.comment ?: return
        if (replyLoadJob?.isActive == true) return
        val loadEpoch = replyLoadEpoch
        _replyState.update { current ->
            current.copy(
                initialLoading = cursor == null && !refreshing && current.items.isEmpty(),
                refreshing = refreshing && current.items.isNotEmpty(),
                loadingMore = cursor != null || (!refreshing && current.items.isNotEmpty()),
                error = null,
            )
        }
        replyLoadJob = viewModelScope.launch(IO) {
            try {
                val source = appDb.bookSourceDao.getBookSource(sourceUrl)
                    ?.takeIf { it.supportsParagraphCommentsV1() }
                    ?: throw ReviewException.UnsupportedSource()
                if (cursor == null && force) {
                    cache.invalidateReplies(sourceUrl, bookId, itemId, comment.commentId)
                }
                var requestedCursor = cursor
                var firstRequest = true
                while (true) {
                    val previousLoadedCount = flattenLoadedReplies(
                        replyAccumulator.snapshot().items
                    ).size
                    val request = ParagraphReplyPageRequest(
                        bookId = bookId,
                        itemId = itemId,
                        commentId = comment.commentId,
                        cursor = requestedCursor,
                    )
                    val page = cache.getOrLoad(
                        ReviewCacheKey.Replies(
                            sourceUrl = sourceUrl,
                            bookId = bookId,
                            itemId = itemId,
                            commentId = comment.commentId,
                            cursor = requestedCursor.orEmpty(),
                        ),
                        force = force && firstRequest,
                    ) {
                        repository.loadReplyPage(source, request, replyAccumulator.seenCursors())
                    }
                    ensureActive()
                    if (!canCommitParagraphReviewReplyResult(
                            loadEpoch = loadEpoch,
                            currentEpoch = replyLoadEpoch,
                            loadedCommentId = comment.commentId,
                            selectedCommentId = _replyState.value.comment?.commentId,
                        )
                    ) {
                        return@launch
                    }
                    replyAccumulator.append(
                        requestedCursor = requestedCursor,
                        items = page.replies,
                        total = page.total,
                        hasMore = page.hasMore,
                        nextCursor = page.nextCursor,
                    )
                    val snapshot = replyAccumulator.snapshot()
                    val loadedCount = flattenLoadedReplies(snapshot.items).size
                    if (isParagraphReviewReplyPageStalled(
                            previousLoadedCount = previousLoadedCount,
                            loadedCount = loadedCount,
                            serverHasMore = snapshot.hasMore,
                        )
                    ) {
                        throw ReviewException.Protocol("回复分页未产生新项目")
                    }
                    val window = paragraphReviewReplyWindow(
                        loadedCount = loadedCount,
                        serverTotal = snapshot.total,
                        serverHasMore = snapshot.hasMore,
                        visibleLimit = replyVisibleLimit,
                    )
                    if (!window.shouldLoadMore) {
                        publishReplySnapshot(comment)
                        return@launch
                    }
                    requestedCursor = snapshot.nextCursor
                    firstRequest = false
                }
            } catch (_: CancellationException) {
                // 取消不映射为 UI 错误。
            } catch (error: Throwable) {
                if (!canCommitParagraphReviewReplyResult(
                        loadEpoch = loadEpoch,
                        currentEpoch = replyLoadEpoch,
                        loadedCommentId = comment.commentId,
                        selectedCommentId = _replyState.value.comment?.commentId,
                    )
                ) {
                    return@launch
                }
                val snapshot = replyAccumulator.snapshot()
                val retained = _replyState.value
                val hasLoadedPage = replyAccumulator.hasLoadedPage()
                if (!hasLoadedPage) {
                    _replyState.value = retained.copy(
                        initialLoading = false,
                        refreshing = false,
                        loadingMore = false,
                        error = error.toUiError(),
                    )
                    return@launch
                }
                val flattened = flattenLoadedReplies(snapshot.items)
                val window = paragraphReviewReplyWindow(
                    loadedCount = flattened.size,
                    serverTotal = snapshot.total,
                    serverHasMore = snapshot.hasMore,
                    visibleLimit = replyVisibleLimit,
                )
                _replyState.value = ParagraphReviewReplyUiState(
                    comment = comment,
                    items = flattened.take(window.visibleCount),
                    loadedCount = flattened.size,
                    total = snapshot.total,
                    visibleLimit = replyVisibleLimit,
                    nextBatchSize = window.nextBatchSize,
                    footerAction = window.footerAction,
                    hasMore = snapshot.hasMore,
                    error = error.toUiError(),
                )
            }
        }
    }

    /** 从已加载领域回复重建完整扁平树，不应用 UI 可见批次裁剪。 */
    private fun flattenLoadedReplies(items: List<ParagraphReply>): List<ParagraphReviewReplyListItem> =
        flattenParagraphReviewReplies(ParagraphReplyTreeBuilder.build(items))

    /** 发布当前可见切片，并返回是否仍需 cursor 页满足本批次。 */
    private fun publishReplySnapshot(comment: ParagraphComment): ParagraphReviewReplyWindow {
        val snapshot = replyAccumulator.snapshot()
        val flattened = flattenLoadedReplies(snapshot.items)
        val window = paragraphReviewReplyWindow(
            loadedCount = flattened.size,
            serverTotal = snapshot.total,
            serverHasMore = snapshot.hasMore,
            visibleLimit = replyVisibleLimit,
        )
        _replyState.value = ParagraphReviewReplyUiState(
            comment = comment,
            items = flattened.take(window.visibleCount),
            loadedCount = flattened.size,
            total = snapshot.total,
            visibleLimit = replyVisibleLimit,
            nextBatchSize = window.nextBatchSize,
            footerAction = window.footerAction,
            hasMore = snapshot.hasMore,
            error = if (window.terminalInconsistent) {
                ParagraphReviewUiError(ParagraphReviewUiErrorKind.PROTOCOL, false)
            } else {
                null
            },
        )
        return window
    }

    /** 把领域异常降级为稳定、脱敏的 UI 错误分类。 */
    private fun Throwable.toUiError(): ParagraphReviewUiError = ParagraphReviewUiError(
        kind = when (this) {
            is ReviewException.Authentication -> ParagraphReviewUiErrorKind.AUTHENTICATION
            is ReviewException.Network -> ParagraphReviewUiErrorKind.NETWORK
            is ReviewException.Protocol -> ParagraphReviewUiErrorKind.PROTOCOL
            else -> ParagraphReviewUiErrorKind.GENERIC
        },
        retryable = (this as? ReviewException)?.retryable == true,
    )

    /** 取消分页并释放 ViewModel 自有缓存 scope。 */
    override fun onCleared() {
        commentLoadJob?.cancel()
        replyLoadJob?.cancel()
        cacheScope.cancel()
        super.onCleared()
    }

    companion object {
        const val ARG_SOURCE_URL = "sourceUrl"
        const val ARG_BOOK_ID = "bookId"
        const val ARG_ITEM_ID = "itemId"
        const val ARG_ITEM_VERSION = "itemVersion"
        const val ARG_PARA_ID = "paraId"
        const val ARG_GENERATION = "generation"
        const val ARG_PARTIAL = "partial"
        const val ARG_COMMENT_COUNT = "commentCount"
    }
}
