package io.legado.app.ui.book.read.review

import android.content.Context
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemParagraphReviewCommentBinding
import io.legado.app.model.review.ParagraphComment

/** 以纯文本绑定主评，只暴露只读点赞数和回复数。 */
class ParagraphReviewCommentAdapter(
    context: Context,
    private val sourceUrl: String,
    private val onRepliesClick: (ParagraphComment) -> Unit,
) : RecyclerAdapter<ParagraphComment, ItemParagraphReviewCommentBinding>(context) {

    /** 创建主评 item 的 ViewBinding。 */
    override fun getViewBinding(parent: ViewGroup): ItemParagraphReviewCommentBinding =
        ItemParagraphReviewCommentBinding.inflate(inflater, parent, false)

    /** 绑定头像占位、用户名、纯文本、时间和只读计数。 */
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewCommentBinding,
        item: ParagraphComment,
        payloads: MutableList<Any>,
    ) {
        binding.bindParagraphReviewComment(
            context = context,
            sourceUrl = sourceUrl,
            comment = item,
            repliesClickable = true,
        )
    }

    /** 只给回复计数注册打开回复列表事件，不添加任何写操作。 */
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
}
