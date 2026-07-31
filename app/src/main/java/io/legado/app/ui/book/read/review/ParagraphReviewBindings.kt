package io.legado.app.ui.book.read.review

import android.annotation.SuppressLint
import android.content.Context
import android.widget.ImageView
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.databinding.ItemParagraphReviewCommentBinding
import io.legado.app.databinding.ItemParagraphReviewReplyBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.review.ParagraphComment
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/** 把主评领域对象绑定到可复用的只读主评行。 */
internal fun ItemParagraphReviewCommentBinding.bindParagraphReviewComment(
    context: Context,
    sourceUrl: String,
    comment: ParagraphComment,
    repliesClickable: Boolean,
) {
    val presentation = presentParagraphReviewComment(
        comment = comment,
        anonymousUser = context.getString(R.string.review_anonymous_user),
        unknownTime = context.getString(R.string.review_unknown_time),
        repliesClickable = repliesClickable,
    )
    tvUser.text = presentation.userName
    tvContent.text = presentation.content
    tvTime.text = presentation.time
    tvLikes.text = context.getString(R.string.review_like_count, presentation.diggCount)
    tvReplies.text = context.getString(R.string.review_reply_count, presentation.replyCount)
    tvReplies.isEnabled = presentation.canOpenReplies
    tvReplies.isClickable = presentation.canOpenReplies
    tvReplies.isFocusable = presentation.canOpenReplies
    ivAvatar.loadParagraphReviewAvatar(context, sourceUrl, presentation.avatarUrl)
}

/** 把回复展示模型绑定为纯文本、显式回复目标和受限视觉缩进。 */
internal fun ItemParagraphReviewReplyBinding.bindParagraphReviewReply(
    context: Context,
    sourceUrl: String,
    item: ParagraphReviewReplyListItem,
) {
    val presentation = presentParagraphReviewReply(
        reply = item.reply,
        anonymousUser = context.getString(R.string.review_anonymous_user),
        unknownTime = context.getString(R.string.review_unknown_time),
    )
    root.setPadding(
        (16 + item.visualDepth * 12).dpToPx(),
        root.paddingTop,
        16.dpToPx(),
        root.paddingBottom,
    )
    tvUser.text = presentation.userName
    presentation.replyToUserName?.let { target ->
        tvReplyTo.text = context.getString(R.string.review_reply_to_user, target)
        tvReplyTo.visible()
    } ?: run {
        tvReplyTo.text = null
        tvReplyTo.gone()
    }
    tvContent.text = presentation.content
    tvTime.text = presentation.time
    tvLikes.text = context.getString(R.string.review_like_count, presentation.diggCount)
    tvReplies.text = context.getString(R.string.review_reply_count, presentation.replyCount)
    ivAvatar.loadParagraphReviewAvatar(context, sourceUrl, presentation.avatarUrl)
}

/** 使用书源同源选项加载安全头像地址，失败时统一回退本地占位。 */
@SuppressLint("CheckResult")
private fun ImageView.loadParagraphReviewAvatar(
    context: Context,
    sourceUrl: String,
    avatarUrl: String?,
) {
    val options = RequestOptions()
        .placeholder(R.drawable.ic_bottom_person_e)
        .error(R.drawable.ic_bottom_person_e)
        .set(OkHttpModelLoader.sourceOriginOption, sourceUrl)
    ImageLoader.load(context, avatarUrl)
        .apply(options)
        .into(this)
}
