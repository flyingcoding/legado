package io.legado.app.ui.book.read.review

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemParagraphReviewCommentBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
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
    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewCommentBinding,
        item: ParagraphComment,
        payloads: MutableList<Any>,
    ) {
        binding.run {
        tvUser.text = item.userName?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.review_anonymous_user)
        tvContent.text = item.text
        tvTime.text = formatParagraphReviewTime(
            item.createTimestamp,
            context.getString(R.string.review_unknown_time),
        )
        tvLikes.text = context.getString(R.string.review_like_count, item.diggCount)
        tvReplies.text = context.getString(R.string.review_reply_count, item.replyCount)
        tvReplies.isEnabled = item.replyCount > 0
        val options = RequestOptions()
            .placeholder(R.drawable.ic_bottom_person_e)
            .error(R.drawable.ic_bottom_person_e)
            .set(OkHttpModelLoader.sourceOriginOption, sourceUrl)
        ImageLoader.load(context, safeParagraphReviewAvatar(item.userAvatar))
            .apply(options)
            .into(ivAvatar)
        }
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
