package io.legado.app.ui.book.read.review

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemParagraphReviewCommentBinding
import io.legado.app.databinding.ItemParagraphReviewImageBinding
import io.legado.app.databinding.ItemParagraphReviewReplyBinding
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.glide.RedactedGlideUrl
import io.legado.app.model.review.ParagraphComment
import io.legado.app.ui.widget.anima.RotateLoading
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/** 把主评领域对象绑定到可复用的只读主评行。 */
internal fun ItemParagraphReviewCommentBinding.bindParagraphReviewComment(
    context: Context,
    sourceUrl: String,
    comment: ParagraphComment,
    palette: ParagraphReviewPalette,
    repliesClickable: Boolean,
    repliesExpanded: Boolean = false,
    onImageClick: (ImageView, String) -> Unit,
) {
    root.setBackgroundColor(Color.TRANSPARENT)
    tvUser.setTextColor(palette.secondaryText)
    tvContent.setTextColor(palette.primaryText)
    tvTime.setTextColor(palette.secondaryText)
    tvLikes.setTextColor(palette.secondaryText)
    replyDivider.setBackgroundColor(palette.divider)
    tvReplies.setTextColor(palette.accent)
    TextViewCompat.setCompoundDrawableTintList(
        tvReplies,
        ColorStateList.valueOf(palette.accent),
    )
    tvReplies.background = paragraphReviewRippleBackground(palette)
    inlineReplyProgress.indeterminateTintList = ColorStateList.valueOf(palette.accent)
    tvInlineReplyState.setTextColor(palette.secondaryText)
    tvInlineReplyState.background = paragraphReviewRippleBackground(palette)
    val presentation = presentParagraphReviewComment(
        comment = comment,
        anonymousUser = context.getString(R.string.review_anonymous_user),
        unknownTime = context.getString(R.string.review_unknown_time),
        repliesClickable = repliesClickable,
    )
    tvUser.text = presentation.userName
    tvContent.text = presentation.content
    if (presentation.content.isBlank()) tvContent.gone() else tvContent.visible()
    tvTime.text = presentation.time
    tvLikes.text = context.getString(R.string.review_like_count, presentation.diggCount)
    tvReplies.text = context.getString(
        R.string.review_expand_reply_count,
        presentation.replyCount,
    )
    tvReplies.isSelected = repliesExpanded
    val showReplyEntry = presentation.canOpenReplies && !repliesExpanded
    tvReplies.isEnabled = showReplyEntry
    tvReplies.isClickable = showReplyEntry
    tvReplies.isFocusable = showReplyEntry
    if (showReplyEntry) {
        replyDivider.visible()
        tvReplies.visible()
    } else {
        replyDivider.gone()
        tvReplies.gone()
    }
    ivAvatar.loadParagraphReviewAvatar(
        sourceUrl = sourceUrl,
        avatarUrl = presentation.avatarUrl,
        sizeDp = COMMENT_AVATAR_SIZE_DP,
    )
    rvImages.bindParagraphReviewImages(context, sourceUrl, presentation.images, onImageClick)
}

/** 把回复展示模型绑定为纯文本、显式回复目标和受限视觉缩进。 */
internal fun ItemParagraphReviewReplyBinding.bindParagraphReviewReply(
    context: Context,
    sourceUrl: String,
    item: ParagraphReviewReplyListItem,
    palette: ParagraphReviewPalette,
    onImageClick: (ImageView, String) -> Unit,
) {
    root.setBackgroundColor(Color.TRANSPARENT)
    tvUser.setTextColor(palette.secondaryText)
    tvReplyTo.setTextColor(palette.secondaryText)
    tvContent.setTextColor(palette.primaryText)
    tvTime.setTextColor(palette.secondaryText)
    tvLikes.setTextColor(palette.secondaryText)
    tvReplies.setTextColor(palette.secondaryText)
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
    if (presentation.content.isBlank()) tvContent.gone() else tvContent.visible()
    tvTime.text = presentation.time
    tvLikes.text = context.getString(R.string.review_like_count, presentation.diggCount)
    tvReplies.text = context.getString(R.string.review_reply_count, presentation.replyCount)
    ivAvatar.loadParagraphReviewAvatar(
        sourceUrl = sourceUrl,
        avatarUrl = presentation.avatarUrl,
        sizeDp = REPLY_AVATAR_SIZE_DP,
    )
    rvImages.bindParagraphReviewImages(context, sourceUrl, presentation.images, onImageClick)
}

/** 绑定横向缩略图列表，并在无图时先清空旧数据再隐藏容器。 */
private fun RecyclerView.bindParagraphReviewImages(
    context: Context,
    sourceUrl: String,
    images: List<ParagraphReviewImagePresentation>,
    onImageClick: (ImageView, String) -> Unit,
) {
    if (images.isEmpty()) {
        clearParagraphReviewImages()
        return
    }
    val imageAdapter = (adapter as? ParagraphReviewImageAdapter)
        ?.takeIf { it.sourceUrl == sourceUrl }
        ?: ParagraphReviewImageAdapter(context, sourceUrl, onImageClick).also {
            layoutManager = LinearLayoutManager(
                context,
                LinearLayoutManager.HORIZONTAL,
                false,
            ).apply {
                isItemPrefetchEnabled = false
            }
            adapter = it
        }
    imageAdapter.onImageClick = onImageClick
    if (imageAdapter.getItems() != images) {
        imageAdapter.setItems(images)
        scrollToPosition(0)
    }
    visible()
}

/** 取消当前缩略图绑定并清空内层 adapter，供无图重绑和头部销毁复用。 */
internal fun RecyclerView.clearParagraphReviewImages() {
    repeat(childCount) { index ->
        val binding = (getChildViewHolder(getChildAt(index)) as? ItemViewHolder)
            ?.binding as? ItemParagraphReviewImageBinding
        binding?.ivImage?.let { imageView ->
            Glide.with(imageView).clear(imageView)
            imageView.setImageDrawable(null)
        }
    }
    val imageAdapter = adapter as? ParagraphReviewImageAdapter
    adapter = null
    imageAdapter?.clearItems()
    gone()
}

/** 把脱敏错误分类映射为主列表和内联回复共用的稳定文案。 */
internal fun Context.paragraphReviewErrorText(error: ParagraphReviewUiError): String = getString(
    when (error.kind) {
        ParagraphReviewUiErrorKind.AUTHENTICATION -> R.string.review_error_authentication
        ParagraphReviewUiErrorKind.NETWORK -> R.string.review_error_network
        ParagraphReviewUiErrorKind.PROTOCOL -> R.string.review_error_protocol
        ParagraphReviewUiErrorKind.GENERIC -> R.string.review_error_generic
    }
)

/** 使用书源同源选项加载安全头像地址，失败时统一回退本地占位。 */
@SuppressLint("CheckResult")
private fun ImageView.loadParagraphReviewAvatar(
    sourceUrl: String,
    avatarUrl: String?,
    sizeDp: Int,
) {
    val size = sizeDp.dpToPx()
    val options = RequestOptions()
        .placeholder(R.drawable.ic_bottom_person_e)
        .error(R.drawable.ic_bottom_person_e)
        .circleCrop()
        .override(size, size)
        .set(OkHttpModelLoader.sourceOriginOption, sourceUrl)
    Glide.with(this)
        .load(RedactedGlideUrl.fromOrNull(avatarUrl))
        .apply(options)
        .into(this)
}

private const val COMMENT_AVATAR_SIZE_DP = 40
private const val REPLY_AVATAR_SIZE_DP = 36

/** 创建只保留顶部圆角的阅读主题抽屉背景。 */
internal fun paragraphReviewDrawerBackground(
    backgroundColor: Int,
    cornerRadius: Int,
): GradientDrawable {
    val radius = cornerRadius.dpToPx().toFloat()
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(backgroundColor)
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }
}

/** 创建使用阅读主题背景和顶部分隔色的电子墨水抽屉背景。 */
internal fun paragraphReviewEInkDrawerBackground(
    backgroundColor: Int,
    dividerColor: Int,
): LayerDrawable = LayerDrawable(
    arrayOf(
        dividerColor.toDrawable(),
        backgroundColor.toDrawable(),
    )
).apply {
    setLayerInset(1, 0, 1.dpToPx(), 0, 0)
}

/** 创建使用阅读文字色派生反馈色的透明点击背景。 */
internal fun paragraphReviewRippleBackground(
    palette: ParagraphReviewPalette,
    cornerRadius: Int = DEFAULT_RIPPLE_RADIUS_DP,
): RippleDrawable {
    val mask = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.WHITE)
        this.cornerRadius = cornerRadius.dpToPx().toFloat()
    }
    return RippleDrawable(
        ColorStateList.valueOf(palette.ripple),
        Color.TRANSPARENT.toDrawable(),
        mask,
    )
}

/** 将阅读主题色应用到评论分页和内联回复共用的加载控件。 */
internal fun LoadMoreView.applyParagraphReviewPalette(palette: ParagraphReviewPalette) {
    setBackgroundColor(Color.TRANSPARENT)
    findViewById<RotateLoading>(R.id.rotate_loading).loadingColor = palette.accent
    findViewById<TextView>(R.id.tv_text).apply {
        setTextColor(palette.secondaryText)
        background = paragraphReviewRippleBackground(palette)
    }
}

private const val DEFAULT_RIPPLE_RADIUS_DP = 4
