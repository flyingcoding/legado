package io.legado.app.ui.book.read.review

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogParagraphReviewBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.setTintMutate
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 以 75% 高度底部抽屉展示主评及单条展开的内联回复。 */
class ParagraphReviewDialog : BaseDialogFragment(R.layout.dialog_paragraph_review) {

    private val binding by viewBinding(DialogParagraphReviewBinding::bind)
    private val viewModel by viewModels<ParagraphReviewViewModel>()
    private val commentLoadMoreView by lazy { LoadMoreView(requireContext()) }
    private var listStateRestored = false
    private val sourceUrl: String
        get() = arguments?.getString(ParagraphReviewViewModel.ARG_SOURCE_URL).orEmpty()
    private val commentAdapter by lazy {
        ParagraphReviewCommentAdapter(
            context = requireContext(),
            sourceUrl = sourceUrl,
            onRepliesClick = viewModel::toggleReplies,
            onReplyRetry = viewModel::retryReplies,
            onReplyLoadMore = viewModel::loadMoreReplies,
            onImageClick = ::openImage,
        ).also { adapter ->
            adapter.addFooterView { ViewLoadMoreBinding.bind(commentLoadMoreView) }
        }
    }

    /** 配置底部对齐、满宽、75% 高度、遮罩和窗口进出场动画。 */
    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(true)
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            val attributes = window.attributes.apply {
                gravity = Gravity.BOTTOM
                if (AppConfig.isEInkMode) {
                    dimAmount = 0f
                    windowAnimations = 0
                } else {
                    dimAmount = DRAWER_DIM_AMOUNT
                    windowAnimations = R.style.ParagraphReviewDrawerAnimation
                }
            }
            if (AppConfig.isEInkMode) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            window.attributes = attributes
        }
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, DRAWER_HEIGHT_RATIO)
    }

    /** 初始化工具栏、列表、分页和 STARTED 生命周期状态收集。 */
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.setBackgroundResource(R.drawable.bg_paragraph_review_drawer)
        viewModel.init(arguments)
        if (!isGenerationCurrent()) {
            dismissAllowingStateLoss()
            return
        }
        initToolbar()
        initList()
        collectState()
    }

    /** 配置动态评论总数标题和关闭按钮。 */
    private fun initToolbar() = binding.toolBar.run {
        val indexCount = arguments?.getInt(ParagraphReviewViewModel.ARG_COMMENT_COUNT, 0) ?: 0
        val primaryTextColor = requireContext().getCompatColor(R.color.primaryText)
        title = getString(R.string.review_comment_count_title, indexCount.coerceAtLeast(0))
        setTitleTextColor(primaryTextColor)
        setNavigationIcon(R.drawable.ic_baseline_close)
        navigationIcon?.setTintMutate(primaryTextColor)
        setNavigationContentDescription(R.string.close)
        setNavigationOnClickListener { dismiss() }
    }

    /** 配置主列表刷新、主评分页 footer、到底加载和错误重试。 */
    private fun initList() = binding.run {
        refreshLayout.setColorSchemeColors(accentColor)
        recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        recyclerView.adapter = commentAdapter
        refreshLayout.setOnRefreshListener(viewModel::refreshComments)
        commentLoadMoreView.setOnClickListener {
            val error = viewModel.state.value.error
            when {
                error?.retryable == true -> viewModel.retryComments()
                error == null -> viewModel.loadMoreComments()
            }
        }
        tvState.setOnClickListener {
            if (viewModel.state.value.error?.retryable == true) viewModel.retryComments()
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            /** 主列表到底时只请求主评下一 cursor 页。 */
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                    viewModel.loadMoreComments()
                }
            }
        })
    }

    /** 在 STARTED 生命周期内合并主评和当前内联回复状态。 */
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

    /** 渲染主评列表和 footer，并把回复状态定向提交给所属主评行。 */
    private fun renderState(
        comments: ParagraphReviewCommentUiState,
        replies: ParagraphReviewReplyUiState,
    ) = binding.run {
        if (!isGenerationCurrent()) {
            dismissAllowingStateLoss()
            return@run
        }
        if (commentAdapter.getItems() != comments.items) {
            commentAdapter.setItems(comments.items)
        }
        if (!listStateRestored) {
            viewModel.commentListState?.let {
                recyclerView.layoutManager?.onRestoreInstanceState(it)
            }
            listStateRestored = true
        }
        commentAdapter.updateReplyState(replies)
        toolBar.title = getString(R.string.review_comment_count_title, comments.total)
        refreshLayout.isRefreshing = comments.refreshing
        progressBar.visibility = if (comments.initialLoading) View.VISIBLE else View.GONE
        tvPartial.visibility = if (comments.partial) View.VISIBLE else View.GONE
        val errorText = comments.error?.let(requireContext()::paragraphReviewErrorText)
        tvState.isClickable = false
        tvState.isFocusable = false
        when {
            comments.initialLoading -> tvState.gone()
            errorText != null && comments.items.isEmpty() -> {
                tvState.text = errorText
                tvState.isClickable = comments.error.retryable
                tvState.isFocusable = comments.error.retryable
                tvState.visible()
            }
            comments.items.isEmpty() -> {
                tvState.setText(R.string.review_empty)
                tvState.isClickable = false
                tvState.visible()
            }
            else -> tvState.gone()
        }
        when {
            comments.loadingMore -> commentLoadMoreView.startLoad()
            comments.error != null && comments.items.isNotEmpty() -> commentLoadMoreView.error(
                null,
                getString(
                    if (comments.error.retryable) R.string.review_retry_load_more
                    else R.string.review_load_more_failed
                ),
            )
            !comments.hasMore && comments.items.isNotEmpty() -> commentLoadMoreView.noMore()
            else -> {
                commentLoadMoreView.hasMore()
                commentLoadMoreView.stopLoad()
            }
        }
    }

    /** 在视图销毁前仅保存始终存在的主评列表滚动位置。 */
    override fun onDestroyView() {
        binding.recyclerView.layoutManager?.onSaveInstanceState()?.let {
            viewModel.commentListState = it
        }
        binding.recyclerView.adapter = null
        binding.recyclerView.recycledViewPool.clear()
        listStateRestored = false
        super.onDestroyView()
    }

    /** 委托宿主再次校验 Dialog generation。 */
    private fun isGenerationCurrent(): Boolean {
        val generation = arguments?.getLong(ParagraphReviewViewModel.ARG_GENERATION, -1L) ?: -1L
        return (activity as? GenerationOwner)?.isParagraphReviewGenerationCurrent(generation) == true
    }

    /** 使用真实缩略图视图和书源 origin 打开原图转场预览。 */
    private fun openImage(sourceView: ImageView, url: String) {
        showDialogFragment(PhotoDialog(url, sourceUrl, sourceView))
    }

    /** 由阅读 Activity 提供当前 generation 校验。 */
    interface GenerationOwner {

        /** 判断 Dialog 携带的 generation 是否仍属于当前阅读章节。 */
        fun isParagraphReviewGenerationCurrent(generation: Long): Boolean
    }

    private companion object {
        const val DRAWER_HEIGHT_RATIO = 0.75f
        const val DRAWER_DIM_AMOUNT = 0.36f
    }
}
