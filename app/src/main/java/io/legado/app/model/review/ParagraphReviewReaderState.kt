package io.legado.app.model.review

/** 标识一次评论读取与排版提交所属的章节世代。 */
data class ReviewGeneration(
    val token: Long,
    val sourceUrl: String,
    val bookUrl: String,
    val chapterIndex: Int,
    val itemId: String,
    val contentHash: String,
)

/** 表示阅读页索引加载的可显示状态。 */
sealed interface ReviewIndexLoadState {
    data object Idle : ReviewIndexLoadState
    data object Loading : ReviewIndexLoadState
    data class Content(val index: ReviewIndex) : ReviewIndexLoadState
    data class Error(val retryable: Boolean) : ReviewIndexLoadState
}

/** 保存阅读页当前 generation、索引状态和映射结果。 */
data class ParagraphReviewReaderState(
    val generation: ReviewGeneration? = null,
    val indexState: ReviewIndexLoadState = ReviewIndexLoadState.Idle,
    val mapping: ParagraphReviewMappingResult = ParagraphReviewMappingResult.Unavailable(
        ParagraphReviewMappingUnavailableReason.NO_VERIFIED_MAPPER
    ),
)

/** 以单调 token 隔离切章、切源、正文变化和关闭开关后的陈旧结果。 */
class ParagraphReviewGenerationReducer(
    initialToken: Long = 0L,
) {

    init {
        require(initialToken >= 0L) { "initialToken must not be negative" }
    }

    private var nextToken = initialToken

    @Volatile
    var state: ParagraphReviewReaderState = ParagraphReviewReaderState()
        private set

    /** 启动新的章节世代并清除旧索引与映射。 */
    @Synchronized
    fun begin(
        sourceUrl: String,
        bookUrl: String,
        chapterIndex: Int,
        itemId: String,
        contentHash: String,
    ): ReviewGeneration {
        nextToken = Math.addExact(nextToken, 1L)
        val generation = ReviewGeneration(
            token = nextToken,
            sourceUrl = sourceUrl,
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            itemId = itemId,
            contentHash = contentHash,
        )
        state = ParagraphReviewReaderState(
            generation = generation,
            indexState = ReviewIndexLoadState.Loading,
        )
        return generation
    }

    /** 仅在 generation 完整匹配时提交索引状态和映射。 */
    @Synchronized
    fun commit(
        generation: ReviewGeneration,
        indexState: ReviewIndexLoadState,
        mapping: ParagraphReviewMappingResult = state.mapping,
    ): Boolean {
        if (state.generation != generation) return false
        state = state.copy(indexState = indexState, mapping = mapping)
        return true
    }

    /** 递增 token 并回到无活动章节状态，使所有在途结果失效。 */
    @Synchronized
    fun invalidate(): Long {
        nextToken = Math.addExact(nextToken, 1L)
        state = ParagraphReviewReaderState()
        return nextToken
    }

    /** 判断点击携带的 token 是否仍属于当前章节。 */
    @Synchronized
    fun acceptsClick(token: Long): Boolean = state.generation?.token == token
}
