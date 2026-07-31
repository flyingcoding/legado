package io.legado.app.ui.book.read.review

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemParagraphReviewCommentBinding
import io.legado.app.databinding.ItemParagraphReviewReplyBinding
import io.legado.app.model.review.ParagraphComment

/** 以受限视觉缩进展示完整回复树，不提供任何写操作。 */
class ParagraphReviewReplyAdapter(
    context: Context,
    private val sourceUrl: String,
    private val onImageClick: (ImageView, String) -> Unit,
) : RecyclerAdapter<ParagraphReviewReplyListItem, ItemParagraphReviewReplyBinding>(context) {

    private var headerComment: ParagraphComment? = null
    private var headerBinding: ItemParagraphReviewCommentBinding? = null

    init {
        addHeaderView { parent -> createHeaderBinding(parent) }
    }

    /** 幂等更新回复页所选主评头部，空值只用于退出回复模式时清理状态。 */
    fun updateHeader(comment: ParagraphComment?) {
        if (headerComment == comment) return
        headerComment = comment
        headerBinding?.let(::bindHeader)
    }

    /** 释放已销毁视图树中的头部绑定，保留所选主评供下一视图重建。 */
    fun releaseHeaderBinding() {
        headerBinding?.rvImages?.clearParagraphReviewImages()
        headerBinding = null
    }

    /** 创建与主评列表一致、但不开放回复点击的只读头部。 */
    private fun createHeaderBinding(parent: ViewGroup): ItemParagraphReviewCommentBinding =
        ItemParagraphReviewCommentBinding.inflate(inflater, parent, false).also { binding ->
            headerBinding = binding
            bindHeader(binding)
        }

    /** 根据当前所选主评显示或隐藏头部，并复用主评行绑定规则。 */
    private fun bindHeader(binding: ItemParagraphReviewCommentBinding) {
        val comment = headerComment
        if (comment == null) {
            binding.rvImages.clearParagraphReviewImages()
            binding.root.visibility = View.GONE
            return
        }
        binding.root.visibility = View.VISIBLE
        binding.bindParagraphReviewComment(
            context = context,
            sourceUrl = sourceUrl,
            comment = comment,
            repliesClickable = false,
            onImageClick = onImageClick,
        )
    }

    /** 创建回复 item 的 ViewBinding。 */
    override fun getViewBinding(parent: ViewGroup): ItemParagraphReviewReplyBinding =
        ItemParagraphReviewReplyBinding.inflate(inflater, parent, false)

    /** 绑定回复身份、目标用户、纯文本、时间、计数和视觉层级。 */
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewReplyBinding,
        item: ParagraphReviewReplyListItem,
        payloads: MutableList<Any>,
    ) {
        binding.bindParagraphReviewReply(context, sourceUrl, item, onImageClick)
    }

    /** 回复列表没有可触发的写操作或下钻事件。 */
    override fun registerListener(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewReplyBinding,
    ) = Unit
}
