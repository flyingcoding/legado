package io.legado.app.ui.book.read.review

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemParagraphReviewCommentBinding
import io.legado.app.model.review.ParagraphComment
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/** 绑定只读主评，并为有回复的评论提供独立展开入口。 */
class ParagraphReviewCommentAdapter(
    context: Context,
    private val sourceUrl: String,
    private val onRepliesClick: (ParagraphComment) -> Unit,
    private val onReplyRetry: (String) -> Unit,
    private val onReplyLoadMore: (String) -> Unit,
    private val onImageClick: (ImageView, String) -> Unit,
) : RecyclerAdapter<ParagraphComment, ItemParagraphReviewCommentBinding>(context) {

    private var replyState = ParagraphReviewReplyUiState()

    /** 创建主评 item 的 ViewBinding。 */
    override fun getViewBinding(parent: ViewGroup): ItemParagraphReviewCommentBinding =
        ItemParagraphReviewCommentBinding.inflate(inflater, parent, false)

    /** 绑定头像、正文、图片、计数和按需显示的回复展开入口。 */
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewCommentBinding,
        item: ParagraphComment,
        payloads: MutableList<Any>,
    ) {
        val selectedState = replyState.takeIf {
            it.comment?.commentId == item.commentId
        }
        binding.bindParagraphReviewComment(
            context = context,
            sourceUrl = sourceUrl,
            comment = item,
            repliesClickable = true,
            repliesExpanded = selectedState != null,
            onImageClick = onImageClick,
        )
        binding.bindInlineReplies(item, selectedState)
    }

    /** 只给回复展开入口注册打开回复列表事件，不添加任何写操作。 */
    override fun registerListener(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewCommentBinding,
    ) {
        binding.tvReplies.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)
                ?.takeIf { it.replyCount > 0 }
                ?.let(onRepliesClick)
        }
    }

    /** 仅刷新旧、新展开主评位置，避免回复状态变化触发整个主列表重绑。 */
    fun updateReplyState(newState: ParagraphReviewReplyUiState) {
        if (replyState == newState) return
        val affectedIds = setOfNotNull(
            replyState.comment?.commentId,
            newState.comment?.commentId,
        )
        replyState = newState
        affectedIds.forEach { commentId ->
            getItems().indexOfFirst { it.commentId == commentId }
                .takeIf { it >= 0 }
                ?.let { updateItem(it, REPLY_STATE_PAYLOAD) }
        }
    }

    /** 回收主评行时清理主评图片与可能存在的嵌套回复资源。 */
    override fun onViewRecycled(holder: ItemViewHolder) {
        (holder.binding as? ItemParagraphReviewCommentBinding)?.let { binding ->
            binding.rvImages.clearParagraphReviewImages()
            binding.releaseInlineReplies()
        }
        super.onViewRecycled(holder)
    }

    /** 把当前主评的回复 loading、空态、错误和列表绑定到内联容器。 */
    private fun ItemParagraphReviewCommentBinding.bindInlineReplies(
        comment: ParagraphComment,
        state: ParagraphReviewReplyUiState?,
    ) {
        if (state == null) {
            releaseInlineReplies()
            return
        }
        inlineReplyContainer.visible()
        tvInlineReplyState.setOnClickListener(null)
        tvInlineReplyState.isClickable = false
        tvInlineReplyState.isFocusable = false
        inlineReplyProgress.visibility = if (state.initialLoading || state.refreshing) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        val emptyError = state.error?.takeIf { state.items.isEmpty() }
        when {
            state.initialLoading -> {
                tvInlineReplyState.gone()
                rvInlineReplies.gone()
            }
            emptyError != null -> {
                tvInlineReplyState.text = context.paragraphReviewErrorText(emptyError)
                tvInlineReplyState.isClickable = emptyError.retryable
                tvInlineReplyState.isFocusable = emptyError.retryable
                tvInlineReplyState.setOnClickListener {
                    if (emptyError.retryable) onReplyRetry(comment.commentId)
                }
                tvInlineReplyState.visible()
                rvInlineReplies.gone()
            }
            state.items.isEmpty() -> {
                tvInlineReplyState.setText(R.string.review_reply_empty)
                tvInlineReplyState.isClickable = false
                tvInlineReplyState.isFocusable = false
                tvInlineReplyState.setOnClickListener(null)
                tvInlineReplyState.visible()
                if (state.footerAction == ParagraphReviewReplyFooterAction.NONE) {
                    rvInlineReplies.gone()
                } else {
                    rvInlineReplies.visible()
                }
            }
            else -> {
                tvInlineReplyState.setOnClickListener(null)
                tvInlineReplyState.gone()
                rvInlineReplies.visible()
            }
        }
        val inlineAdapter = (rvInlineReplies.adapter as? ParagraphReviewInlineReplyAdapter)
            ?.takeIf { it.commentId == comment.commentId }
            ?: ParagraphReviewInlineReplyAdapter(
                context = context,
                sourceUrl = sourceUrl,
                commentId = comment.commentId,
                onRetry = onReplyRetry,
                onLoadMore = onReplyLoadMore,
                onImageClick = onImageClick,
            ).also { adapter ->
                (rvInlineReplies.adapter as? ParagraphReviewInlineReplyAdapter)
                    ?.releaseFrom(rvInlineReplies)
                rvInlineReplies.layoutManager = LinearLayoutManager(context).apply {
                    isItemPrefetchEnabled = false
                }
                rvInlineReplies.isNestedScrollingEnabled = false
                rvInlineReplies.itemAnimator = null
                rvInlineReplies.adapter = adapter
            }
        inlineAdapter.render(state)
    }

    /** 清空未选中行的嵌套 adapter、图片请求与状态点击监听。 */
    private fun ItemParagraphReviewCommentBinding.releaseInlineReplies() {
        (rvInlineReplies.adapter as? ParagraphReviewInlineReplyAdapter)
            ?.releaseFrom(rvInlineReplies)
        tvInlineReplyState.setOnClickListener(null)
        tvInlineReplyState.text = null
        tvInlineReplyState.gone()
        inlineReplyProgress.gone()
        rvInlineReplies.gone()
        inlineReplyContainer.gone()
    }

    private companion object {
        val REPLY_STATE_PAYLOAD = Any()
    }
}
