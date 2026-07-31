package io.legado.app.ui.widget.dialog

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogPhotoViewBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.image.photo.Info
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.lang.ref.WeakReference

/** 显示支持手势和缩略图双向转场的全屏图片。 */
class PhotoDialog() : BaseDialogFragment(R.layout.dialog_photo_view) {

    constructor(
        src: String,
        sourceOrigin: String? = null,
        sourceView: ImageView? = null,
    ) : this() {
        arguments = Bundle().apply {
            putString(ARG_SRC, src)
            putString(ARG_SOURCE_ORIGIN, sourceOrigin)
        }
        sourceViewRef = sourceView?.let(::WeakReference)
    }

    private val binding by viewBinding(DialogPhotoViewBinding::bind)
    private var sourceViewRef: WeakReference<ImageView>? = null
    private var dismissing = false
    private val loadOriginalRunnable = Runnable { loadOriginalImage() }
    private val dismissFallbackRunnable = Runnable { finishDismiss() }

    /** 拦截系统返回键，使其与单击图片共用反向缩回动画。 */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissAnimated()
                    true
                } else {
                    false
                }
            }
        }

    /** 把预览窗口扩展到全屏并移除系统默认背景和额外 dim。 */
    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { windowAnimations = 0 }
        }
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** 准备缩略图占位、黑色遮罩、入场动画和同源原图加载。 */
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.setBackgroundColor(Color.TRANSPARENT)
        binding.photoView.setOnClickListener { dismissAnimated() }
        val sourceView = validSourceView()
        val sourceInfo = sourceView?.let(::sourceInfoOrNull)
        val placeholder = sourceView?.drawable?.let { drawable ->
            drawable.constantState?.newDrawable(resources)?.mutate() ?: drawable
        }
        if (placeholder != null) binding.photoView.setImageDrawable(placeholder)
        if (AppConfig.isEInkMode) {
            binding.photoScrim.alpha = 1f
            binding.photoView.alpha = 1f
            loadOriginalImage()
            return
        }
        binding.photoScrim.alpha = 0f
        binding.photoScrim.animate()
            .alpha(1f)
            .setDuration(binding.photoView.getAnimDuring().toLong())
            .start()
        if (sourceInfo != null && placeholder != null) {
            binding.photoView.alpha = 1f
            binding.photoView.animaFrom(sourceInfo)
            binding.photoView.postDelayed(
                loadOriginalRunnable,
                binding.photoView.getAnimDuring().toLong(),
            )
        } else {
            binding.photoView.alpha = 0f
            binding.photoView.animate()
                .alpha(1f)
                .setDuration(FALLBACK_FADE_DURATION_MILLIS)
                .start()
            loadOriginalImage()
        }
    }

    /** 按项目既有本地、缓存和脱敏远程路径加载原图。 */
    @SuppressLint("CheckResult")
    private fun loadOriginalImage() {
        val arguments = arguments ?: return
        val src = arguments.getString(ARG_SRC) ?: return
        ImageProvider.get(src)?.let {
            binding.photoView.setImageBitmap(it)
            return
        }
        val file = ReadBook.book?.let { book -> BookHelp.getImage(book, src) }
        if (file?.exists() == true) {
            ImageLoader.load(requireContext(), file)
                .error(R.drawable.image_loading_error)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(binding.photoView)
        } else {
            ImageLoader.loadRedactedRemote(requireContext(), src).apply {
                arguments.getString(ARG_SOURCE_ORIGIN)?.let { sourceOrigin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceOrigin))
                }
            }.error(BookCover.defaultDrawable)
                .dontTransform()
                .downsample(DownsampleStrategy.NONE)
                .into(binding.photoView)
        }
    }

    /** 在源视图有效时缩回原位，否则安全降级为居中淡出。 */
    private fun dismissAnimated() {
        if (dismissing) return
        dismissing = true
        binding.photoView.removeCallbacks(loadOriginalRunnable)
        if (AppConfig.isEInkMode) {
            finishDismiss()
            return
        }
        binding.photoScrim.animate()
            .alpha(0f)
            .setDuration(FALLBACK_FADE_DURATION_MILLIS)
            .start()
        val sourceInfo = validSourceView()?.let(::sourceInfoOrNull)
        if (sourceInfo != null && binding.photoView.drawable != null) {
            binding.photoView.isEnable = false
            binding.photoView.animaTo(sourceInfo, Runnable { finishDismiss() })
            binding.photoView.postDelayed(
                dismissFallbackRunnable,
                binding.photoView.getAnimDuring().toLong() + DISMISS_FALLBACK_DELAY_MILLIS,
            )
        } else {
            binding.photoView.animate()
                .alpha(0f)
                .setDuration(FALLBACK_FADE_DURATION_MILLIS)
                .withEndAction(::finishDismiss)
                .start()
        }
    }

    /** 返回仍附着、已测量且持有 drawable 的缩略图，否则触发淡入淡出降级。 */
    private fun validSourceView(): ImageView? = sourceViewRef?.get()?.takeIf { source ->
        source.isAttachedToWindow && source.width > 0 && source.height > 0 &&
            source.drawable != null
    }

    /** 把跨 Dialog 的源图屏幕坐标换算到全屏 PhotoView 坐标系。 */
    private fun sourceInfoOrNull(sourceView: ImageView): Info? = runCatching {
        binding.photoView.getImageViewInfo(sourceView).also { info ->
            val sourceLocation = IntArray(2)
            val targetLocation = IntArray(2)
            sourceView.getLocationOnScreen(sourceLocation)
            binding.photoView.getLocationOnScreen(targetLocation)
            info.mRect.set(
                sourceLocation[0] - targetLocation[0] + info.mImgRect.left,
                sourceLocation[1] - targetLocation[1] + info.mImgRect.top,
                sourceLocation[0] - targetLocation[0] + info.mImgRect.right,
                sourceLocation[1] - targetLocation[1] + info.mImgRect.bottom,
            )
        }
    }.getOrNull()

    /** 幂等结束转场并允许在状态已保存时关闭 Dialog。 */
    private fun finishDismiss() {
        binding.photoView.removeCallbacks(dismissFallbackRunnable)
        if (dialog?.isShowing == true) dismissAllowingStateLoss()
    }

    /** 销毁视图前取消延迟任务、属性动画与原图请求。 */
    override fun onDestroyView() {
        binding.photoView.removeCallbacks(loadOriginalRunnable)
        binding.photoView.removeCallbacks(dismissFallbackRunnable)
        binding.photoScrim.animate().cancel()
        binding.photoView.animate().cancel()
        Glide.with(binding.photoView).clear(binding.photoView)
        super.onDestroyView()
    }

    private companion object {
        const val ARG_SRC = "src"
        const val ARG_SOURCE_ORIGIN = "sourceOrigin"
        const val FALLBACK_FADE_DURATION_MILLIS = 200L
        const val DISMISS_FALLBACK_DELAY_MILLIS = 120L
    }
}
