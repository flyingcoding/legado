package io.legado.app.ui.book.read.review

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogParagraphReviewBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 使用全屏只读列表展示一段主评及 cursor 分页状态。 */
class ParagraphReviewDialog : BaseDialogFragment(R.layout.dialog_paragraph_review) {

    private val binding by viewBinding(DialogParagraphReviewBinding::bind)
    private val viewModel by viewModels<ParagraphReviewViewModel>()
    private val commentLoadMoreView by lazy { LoadMoreView(requireContext()) }
    private val replyLoadMoreView by lazy { LoadMoreView(requireContext()) }
    private val sourceUrl: String
        get() = arguments?.getString(ParagraphReviewViewModel.ARG_SOURCE_URL).orEmpty()
    private val commentAdapter by lazy {
        ParagraphReviewCommentAdapter(
            context = requireContext(),
            sourceUrl = sourceUrl,
            onRepliesClick = viewModel::openReplies,
            onImageClick = ::openImage,
        )
    }
    private val replyAdapter by lazy {
        ParagraphReviewReplyAdapter(
            context = requireContext(),
            sourceUrl = sourceUrl,
            onImageClick = ::openImage,
        )
    }
    private var showingReplies: Boolean? = null

    /** 把 Dialog 扩展到全屏。 */
    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** 初始化工具栏、列表、刷新、加载更多和状态收集。 */
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.init(arguments)
        if (!isGenerationCurrent()) {
            dismissAllowingStateLoss()
            return
        }
        initToolbar()
        initList()
        collectState()
    }

    /** 配置主评关闭和回复返回主评的统一导航按钮。 */
    private fun initToolbar() = binding.toolBar.run {
        setTitle(R.string.review_dialog_title)
        setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
        setNavigationOnClickListener {
            if (viewModel.replyState.value.comment == null) {
                dismissAllowingStateLoss()
            } else {
                viewModel.closeReplies()
            }
        }
    }

    /** 配置 RecyclerView footer、下拉刷新、到底加载和错误重试。 */
    private fun initList() = binding.run {
        refreshLayout.setColorSchemeColors(accentColor)
        recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        recyclerView.adapter = commentAdapter
        commentAdapter.addFooterView { ViewLoadMoreBinding.bind(commentLoadMoreView) }
        replyAdapter.addFooterView { ViewLoadMoreBinding.bind(replyLoadMoreView) }
        refreshLayout.setOnRefreshListener(viewModel::refresh)
        commentLoadMoreView.setOnClickListener {
            val error = viewModel.state.value.error
            when {
                error?.retryable == true -> viewModel.retry()
                error == null -> viewModel.loadMore()
            }
        }
        replyLoadMoreView.setOnClickListener {
            val error = viewModel.replyState.value.error
            when {
                error?.retryable == true -> viewModel.retry()
                error == null -> viewModel.loadMore()
            }
        }
        tvState.setOnClickListener {
            val error = if (showingReplies == true) {
                viewModel.replyState.value.error
            } else {
                viewModel.state.value.error
            }
            if (error?.retryable == true) viewModel.retry()
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            /** 列表到底时请求下一 cursor 页。 */
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) viewModel.loadMore()
            }
        })
    }

    /** 在 STARTED 生命周期内合并主评与回复状态并渲染当前列表。 */
    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.state, viewModel.replyState) { comments, replies ->
                    comments to replies
                }.collect { (comments, replies) ->
                    renderState(comments, replies)
                }
            }
        }
    }

    /** 把不可变 UI 状态绑定到列表和轻量状态控件。 */
    private fun renderState(
        comments: ParagraphReviewCommentUiState,
        replies: ParagraphReviewReplyUiState,
    ) = binding.run {
        if (!isGenerationCurrent()) {
            dismissAllowingStateLoss()
            return@run
        }
        val inReplies = replies.comment != null
        replyAdapter.updateHeader(replies.comment)
        switchListMode(inReplies)
        val initialLoading: Boolean
        val refreshing: Boolean
        val loadingMore: Boolean
        val hasMore: Boolean
        val isEmpty: Boolean
        val error: ParagraphReviewUiError?
        if (inReplies) {
            replyAdapter.setItems(replies.items)
            initialLoading = replies.initialLoading
            refreshing = replies.refreshing
            loadingMore = replies.loadingMore
            hasMore = replies.hasMore
            isEmpty = replies.items.isEmpty()
            error = replies.error
        } else {
            commentAdapter.setItems(comments.items)
            initialLoading = comments.initialLoading
            refreshing = comments.refreshing
            loadingMore = comments.loadingMore
            hasMore = comments.hasMore
            isEmpty = comments.items.isEmpty()
            error = comments.error
        }
        refreshLayout.isRefreshing = refreshing
        progressBar.visibility = if (initialLoading) View.VISIBLE else View.GONE
        tvPartial.visibility = if (comments.partial) View.VISIBLE else View.GONE
        toolBar.setTitle(
            if (inReplies) R.string.review_replies_title else R.string.review_dialog_title
        )
        val errorText = error?.let(::errorText)
        when {
            initialLoading -> tvState.gone()
            errorText != null && isEmpty -> {
                tvState.text = errorText
                tvState.isClickable = error.retryable
                tvState.visible()
            }
            isEmpty -> {
                tvState.setText(
                    if (inReplies) R.string.review_reply_empty else R.string.review_empty
                )
                tvState.isClickable = false
                tvState.visible()
            }
            else -> tvState.gone()
        }
        val loadMoreView = if (inReplies) replyLoadMoreView else commentLoadMoreView
        when {
            loadingMore -> loadMoreView.startLoad()
            error != null && !isEmpty -> loadMoreView.error(
                null,
                getString(
                    if (error.retryable) R.string.review_retry_load_more
                    else R.string.review_load_more_failed
                )
            )
            !hasMore && !isEmpty -> loadMoreView.noMore()
            else -> {
                loadMoreView.hasMore()
                loadMoreView.stopLoad()
            }
        }
    }

    /** 在主评和回复 adapter 间切换并保存各自 RecyclerView 滚动状态。 */
    private fun switchListMode(inReplies: Boolean) = binding.recyclerView.run {
        val transition = paragraphReviewListModeTransition(showingReplies, inReplies)
        if (!transition.modeChanged) return@run
        if (transition.saveCurrentState) {
            layoutManager?.onSaveInstanceState()?.let { state ->
                if (showingReplies == true) viewModel.replyListState = state
                else viewModel.commentListState = state
            }
        }
        showingReplies = inReplies
        adapter = if (inReplies) replyAdapter else commentAdapter
        val targetState = if (inReplies) viewModel.replyListState else viewModel.commentListState
        if (targetState == null) {
            scrollToPosition(0)
        } else {
            layoutManager?.onRestoreInstanceState(targetState)
        }
    }

    /** 在旋转重建前把当前列表位置交给 Dialog ViewModel 保存。 */
    override fun onDestroyView() {
        showingReplies?.let { inReplies ->
            binding.recyclerView.layoutManager?.onSaveInstanceState()?.let { state ->
                if (inReplies) viewModel.replyListState = state
                else viewModel.commentListState = state
            }
            replyAdapter.releaseHeaderBinding()
        }
        showingReplies = null
        super.onDestroyView()
    }

    /** 返回稳定、脱敏且不包含上游正文的错误文案。 */
    private fun errorText(error: ParagraphReviewUiError): String = getString(
        when (error.kind) {
            ParagraphReviewUiErrorKind.AUTHENTICATION -> R.string.review_error_authentication
            ParagraphReviewUiErrorKind.NETWORK -> R.string.review_error_network
            ParagraphReviewUiErrorKind.PROTOCOL -> R.string.review_error_protocol
            ParagraphReviewUiErrorKind.GENERIC -> R.string.review_error_generic
        }
    )

    /** 委托宿主再次校验 Dialog generation。 */
    private fun isGenerationCurrent(): Boolean {
        val generation = arguments?.getLong(ParagraphReviewViewModel.ARG_GENERATION, -1L) ?: -1L
        return (activity as? GenerationOwner)?.isParagraphReviewGenerationCurrent(generation) == true
    }

    /** 使用书源 origin 打开现有原图预览对话框。 */
    private fun openImage(url: String) {
        showDialogFragment(PhotoDialog(url, sourceUrl))
    }

    /** 由阅读 Activity 提供当前 generation 校验。 */
    interface GenerationOwner {

        /** 判断 Dialog 携带的 generation 是否仍属于当前阅读章节。 */
        fun isParagraphReviewGenerationCurrent(generation: Long): Boolean
    }
}
