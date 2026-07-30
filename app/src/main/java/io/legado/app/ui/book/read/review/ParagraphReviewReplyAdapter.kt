package io.legado.app.ui.book.read.review

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemParagraphReviewReplyBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/** 以受限视觉缩进展示完整回复树，不提供任何写操作。 */
class ParagraphReviewReplyAdapter(
    context: Context,
    private val sourceUrl: String,
) : RecyclerAdapter<ParagraphReviewReplyListItem, ItemParagraphReviewReplyBinding>(context) {

    /** 创建回复 item 的 ViewBinding。 */
    override fun getViewBinding(parent: ViewGroup): ItemParagraphReviewReplyBinding =
        ItemParagraphReviewReplyBinding.inflate(inflater, parent, false)

    /** 绑定回复身份、目标用户、纯文本、时间、计数和视觉层级。 */
    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewReplyBinding,
        item: ParagraphReviewReplyListItem,
        payloads: MutableList<Any>,
    ) {
        val reply = item.reply
        binding.run {
            root.setPadding(
                (16 + item.visualDepth * 12).dpToPx(),
                root.paddingTop,
                16.dpToPx(),
                root.paddingBottom,
            )
            tvUser.text = reply.userName?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.review_anonymous_user)
            reply.replyToUserName?.takeIf(String::isNotBlank)?.let { target ->
                tvReplyTo.text = context.getString(R.string.review_reply_to_user, target)
                tvReplyTo.visible()
            } ?: tvReplyTo.gone()
            tvContent.text = reply.text
            tvTime.text = formatParagraphReviewTime(
                reply.createTimestamp,
                context.getString(R.string.review_unknown_time),
            )
            tvLikes.text = context.getString(R.string.review_like_count, reply.diggCount)
            tvReplies.text = context.getString(R.string.review_reply_count, reply.replyCount)
            val options = RequestOptions()
                .placeholder(R.drawable.ic_bottom_person_e)
                .error(R.drawable.ic_bottom_person_e)
                .set(OkHttpModelLoader.sourceOriginOption, sourceUrl)
            ImageLoader.load(context, safeParagraphReviewAvatar(reply.userAvatar))
                .apply(options)
                .into(ivAvatar)
        }
    }

    /** 回复列表没有可触发的写操作或下钻事件。 */
    override fun registerListener(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewReplyBinding,
    ) = Unit
}
