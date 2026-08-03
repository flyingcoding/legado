package io.legado.app.ui.book.read.review

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemParagraphReviewReplyBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.gone
import io.legado.app.utils.visible

/** 在一条主评内部展示回复树、错误重试和独立 cursor 分页。 */
internal class ParagraphReviewInlineReplyAdapter(
    context: Context,
    private val sourceUrl: String,
    val commentId: String,
    private val palette: ParagraphReviewPalette,
    private val onRetry: (String) -> Unit,
    private val onLoadMore: (String) -> Unit,
    private val onImageClick: (ImageView, String) -> Unit,
) : RecyclerAdapter<ParagraphReviewReplyListItem, ItemParagraphReviewReplyBinding>(context) {

    private val loadMoreView = LoadMoreView(context)
    private var state = ParagraphReviewReplyUiState()

    init {
        addFooterView { ViewLoadMoreBinding.bind(loadMoreView) }
        loadMoreView.applyParagraphReviewPalette(palette)
        loadMoreView.setOnClickListener {
            when {
                state.initialLoading || state.refreshing || state.loadingMore -> Unit
                state.error?.retryable == true -> onRetry(commentId)
                state.error != null -> Unit
                state.footerAction != ParagraphReviewReplyFooterAction.NONE -> {
                    onLoadMore(commentId)
                }
            }
        }
    }

    /** 创建无主评 header 的回复行 ViewBinding。 */
    override fun getViewBinding(parent: ViewGroup): ItemParagraphReviewReplyBinding =
        ItemParagraphReviewReplyBinding.inflate(inflater, parent, false)

    /** 绑定回复身份、显式父子目标、图片与受限视觉层级。 */
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewReplyBinding,
        item: ParagraphReviewReplyListItem,
        payloads: MutableList<Any>,
    ) {
        binding.bindParagraphReviewReply(context, sourceUrl, item, palette, onImageClick)
    }

    /** 内联回复本身只提供图片预览，不注册写操作或二次下钻。 */
    override fun registerListener(
        holder: ItemViewHolder,
        binding: ItemParagraphReviewReplyBinding,
    ) = Unit

    /** 更新回复项，并按独立动作映射加载、错误、下一批或最终收起 footer。 */
    fun render(newState: ParagraphReviewReplyUiState) {
        state = newState
        if (getItems() != newState.items) setItems(newState.items)
        when {
            newState.initialLoading || newState.refreshing || newState.loadingMore -> {
                loadMoreView.visible()
                loadMoreView.startLoad()
            }
            newState.error != null -> {
                loadMoreView.visible()
                loadMoreView.error(
                    null,
                    context.getString(
                        if (newState.error.retryable) R.string.review_retry_load_more
                        else R.string.review_load_more_failed
                    ),
                )
            }
            newState.footerAction == ParagraphReviewReplyFooterAction.REVEAL_MORE -> {
                loadMoreView.visible()
                loadMoreView.error(
                    null,
                    context.getString(
                        R.string.review_expand_reply_count,
                        newState.nextBatchSize,
                    ),
                )
            }
            newState.footerAction == ParagraphReviewReplyFooterAction.COLLAPSE -> {
                loadMoreView.visible()
                loadMoreView.error(null, context.getString(R.string.review_collapse_replies))
            }
            else -> loadMoreView.gone()
        }
    }

    /** 行被回收时取消其图片请求并移除旧缩略图 adapter。 */
    override fun onViewRecycled(holder: ItemViewHolder) {
        (holder.binding as? ItemParagraphReviewReplyBinding)
            ?.rvImages
            ?.clearParagraphReviewImages()
        super.onViewRecycled(holder)
    }

    /** 收起或切换主评时释放可见回复图片、footer 与嵌套 RecyclerView。 */
    fun releaseFrom(recyclerView: RecyclerView) {
        repeat(recyclerView.childCount) { index ->
            ((recyclerView.getChildViewHolder(recyclerView.getChildAt(index)) as? ItemViewHolder)
                ?.binding as? ItemParagraphReviewReplyBinding)
                ?.rvImages
                ?.clearParagraphReviewImages()
        }
        recyclerView.adapter = null
        clearItems()
        loadMoreView.gone()
        recyclerView.recycledViewPool.clear()
    }
}
