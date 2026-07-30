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

/** 为全屏段评 Dialog 串行加载主评 cursor 页并保留旋转期间列表状态。 */
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
    private var initialized = false
    private lateinit var sourceUrl: String
    private lateinit var bookId: String
    private lateinit var itemId: String
    private lateinit var itemVersion: String
    private var paraId: Int = -1
    var generation: Long = -1L
        private set
    private var indexPartial = false

    private val _state = MutableStateFlow(ParagraphReviewCommentUiState())
    val state = _state.asStateFlow()
    private val _replyState = MutableStateFlow(ParagraphReviewReplyUiState())
    val replyState = _replyState.asStateFlow()

    var commentListState: Parcelable? = null
    var replyListState: Parcelable? = null

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
        initialized = sourceUrl.isNotBlank() && bookId.isNotBlank() &&
            itemId.isNotBlank() && itemVersion.isNotBlank() && paraId >= 0 && generation >= 0
        if (!initialized) {
            _state.value = ParagraphReviewCommentUiState(
                error = ParagraphReviewUiError(ParagraphReviewUiErrorKind.GENERIC, false)
            )
            return
        }
        loadFirstCommentPage(force = false)
    }

    /** 按当前主评/回复层刷新对应 cursor 链。 */
    fun refresh() {
        if (!initialized) return
        if (_replyState.value.comment == null) {
            loadFirstCommentPage(force = true)
        } else {
            loadFirstReplyPage(force = true)
        }
    }

    /** 按当前主评/回复层串行加载下一 cursor 页。 */
    fun loadMore() {
        if (!initialized) return
        if (_replyState.value.comment == null) loadMoreComments() else loadMoreReplies()
    }

    /** 按当前列表是否已有成功项重试第一页或失败的下一页。 */
    fun retry() {
        val current = if (_replyState.value.comment == null) _state.value else _replyState.value
        when (current) {
            is ParagraphReviewCommentUiState -> {
                if (!commentAccumulator.hasLoadedPage()) {
                    loadFirstCommentPage(force = true)
                } else {
                    loadMoreComments()
                }
            }

            is ParagraphReviewReplyUiState -> {
                if (!replyAccumulator.hasLoadedPage()) {
                    loadFirstReplyPage(force = true)
                } else {
                    loadMoreReplies()
                }
            }
        }
    }

    /** 选择主评并优先展示合同中已经携带的 eager 回复。 */
    fun openReplies(comment: ParagraphComment) {
        if (!initialized || _replyState.value.comment?.commentId == comment.commentId) return
        replyLoadEpoch++
        cache.cancelReplyFlights()
        replyLoadJob?.cancel()
        replyLoadJob = null
        replyAccumulator = CursorPageAccumulator(ParagraphReply::replyId)
        _replyState.value = ParagraphReviewReplyUiState(
            comment = comment,
            initialLoading = !comment.repliesLoaded,
        )
        if (!comment.repliesLoaded) {
            loadReplyPage(cursor = null, force = false, refreshing = false)
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
            publishReplySnapshot(comment)
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
        _replyState.value = ParagraphReviewReplyUiState()
    }

    /** 在上一主评页声明 hasMore 时加载下一页。 */
    private fun loadMoreComments() {
        if (commentLoadJob?.isActive == true) return
        val snapshot = commentAccumulator.snapshot()
        if (!snapshot.hasMore || snapshot.nextCursor.isBlank()) return
        loadCommentPage(cursor = snapshot.nextCursor, force = false, refreshing = false)
    }

    /** 在上一回复页声明 hasMore 时加载下一页。 */
    private fun loadMoreReplies() {
        if (replyLoadJob?.isActive == true) return
        val snapshot = replyAccumulator.snapshot()
        if (!snapshot.hasMore || snapshot.nextCursor.isBlank()) return
        loadReplyPage(cursor = snapshot.nextCursor, force = false, refreshing = false)
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
        val retained = _replyState.value.takeIf { force }
        _replyState.value = ParagraphReviewReplyUiState(
            comment = comment,
            items = retained?.items.orEmpty(),
            initialLoading = retained?.items.isNullOrEmpty(),
            refreshing = !retained?.items.isNullOrEmpty(),
            hasMore = retained?.hasMore == true,
        )
        loadReplyPage(cursor = null, force = force, refreshing = _replyState.value.refreshing)
    }

    /** 从 Room 取得当前书源后加载并合并一页回复树。 */
    private fun loadReplyPage(cursor: String?, force: Boolean, refreshing: Boolean) {
        val comment = _replyState.value.comment ?: return
        if (replyLoadJob?.isActive == true) return
        val loadEpoch = replyLoadEpoch
        _replyState.update { current ->
            current.copy(
                initialLoading = cursor == null && !refreshing && current.items.isEmpty(),
                refreshing = refreshing && current.items.isNotEmpty(),
                loadingMore = cursor != null,
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
                val request = ParagraphReplyPageRequest(
                    bookId = bookId,
                    itemId = itemId,
                    commentId = comment.commentId,
                    cursor = cursor,
                )
                val page = cache.getOrLoad(
                    ReviewCacheKey.Replies(
                        sourceUrl = sourceUrl,
                        bookId = bookId,
                        itemId = itemId,
                        commentId = comment.commentId,
                        cursor = cursor.orEmpty(),
                    ),
                    force = force,
                ) {
                    repository.loadReplyPage(source, request, replyAccumulator.seenCursors())
                }
                ensureActive()
                if (loadEpoch != replyLoadEpoch ||
                    _replyState.value.comment?.commentId != comment.commentId
                ) {
                    return@launch
                }
                replyAccumulator.append(
                    requestedCursor = cursor,
                    items = page.replies,
                    total = page.total,
                    hasMore = page.hasMore,
                    nextCursor = page.nextCursor,
                )
                publishReplySnapshot(comment)
            } catch (_: CancellationException) {
                // 取消不映射为 UI 错误。
            } catch (error: Throwable) {
                if (loadEpoch != replyLoadEpoch ||
                    _replyState.value.comment?.commentId != comment.commentId
                ) {
                    return@launch
                }
                val snapshot = replyAccumulator.snapshot()
                val retained = _replyState.value
                _replyState.value = ParagraphReviewReplyUiState(
                    comment = comment,
                    items = if (snapshot.items.isEmpty()) retained.items else {
                        flattenParagraphReviewReplies(
                            ParagraphReplyTreeBuilder.build(snapshot.items)
                        )
                    },
                    hasMore = snapshot.hasMore || retained.hasMore,
                    error = error.toUiError(),
                )
            }
        }
    }

    /** 把全部已加载回复重建成跨页树并发布扁平渲染快照。 */
    private fun publishReplySnapshot(comment: ParagraphComment) {
        val snapshot = replyAccumulator.snapshot()
        _replyState.value = ParagraphReviewReplyUiState(
            comment = comment,
            items = flattenParagraphReviewReplies(
                ParagraphReplyTreeBuilder.build(snapshot.items)
            ),
            hasMore = snapshot.hasMore,
        )
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
    }
}
