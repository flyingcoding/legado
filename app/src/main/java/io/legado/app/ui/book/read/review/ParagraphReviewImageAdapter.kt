package io.legado.app.ui.book.read.review

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemParagraphReviewImageBinding
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.glide.RedactedGlideUrl
import io.legado.app.utils.dpToPx
import kotlin.math.roundToInt

/** 横向展示安全段评缩略图，并将点击统一上抛给宿主。 */
internal class ParagraphReviewImageAdapter(
    context: Context,
    val sourceUrl: String,
    var onImageClick: (ImageView, String) -> Unit,
) : RecyclerAdapter<ParagraphReviewImagePresentation, ItemParagraphReviewImageBinding>(context) {

    /** 创建段评缩略图 ViewBinding。 */
    override fun getViewBinding(parent: ViewGroup): ItemParagraphReviewImageBinding =
        ItemParagraphReviewImageBinding.inflate(inflater, parent, false)

    /** 清理旧请求后按受限比例加载当前安全图片。 */
    @SuppressLint("CheckResult")
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewImageBinding,
        item: ParagraphReviewImagePresentation,
        payloads: MutableList<Any>,
    ) {
        val height = THUMBNAIL_HEIGHT_DP.dpToPx()
        val width = (height * item.aspectRatio).roundToInt()
        binding.root.layoutParams = binding.root.layoutParams.apply {
            this.width = width
            this.height = height
        }
        Glide.with(binding.ivImage).clear(binding.ivImage)
        binding.ivImage.setImageDrawable(null)
        val options = RequestOptions()
            .placeholder(R.drawable.ic_image)
            .error(R.drawable.image_loading_error)
            .override(width, height)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .set(OkHttpModelLoader.sourceOriginOption, sourceUrl)
        Glide.with(binding.ivImage)
            .load(RedactedGlideUrl.fromOrNull(item.url) ?: item.url)
            .apply(options)
            .into(binding.ivImage)
    }

    /** 绑定当前 holder 对应图片的点击事件。 */
    override fun registerListener(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewImageBinding,
    ) {
        binding.root.setOnClickListener {
            getItemByLayoutPosition(holder.layoutPosition)?.url?.let { url ->
                onImageClick(binding.ivImage, url)
            }
        }
    }

    /** 回收缩略图视图时取消请求并清除旧 drawable。 */
    override fun onViewRecycled(holder: ItemViewHolder) {
        (holder.binding as? ItemParagraphReviewImageBinding)?.ivImage?.let { imageView ->
            Glide.with(imageView).clear(imageView)
            imageView.setImageDrawable(null)
        }
        super.onViewRecycled(holder)
    }

    private companion object {
        const val THUMBNAIL_HEIGHT_DP = 92
    }
}
