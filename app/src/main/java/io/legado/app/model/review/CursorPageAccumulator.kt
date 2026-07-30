package io.legado.app.model.review

/** 保存 cursor 分页聚合后的稳定快照。 */
data class CursorPageSnapshot<T>(
    val items: List<T>,
    val total: Int,
    val hasMore: Boolean,
    val nextCursor: String,
)

/** 串行合并 cursor 页并按 ID 去重保持首次出现顺序。 */
class CursorPageAccumulator<T>(
    private val idOf: (T) -> String,
) {

    private val itemsById = LinkedHashMap<String, T>()
    private val emittedCursors = linkedSetOf<String>()
    private val requestedCursors = linkedSetOf<String>()
    private var pageCount = 0
    private var total = 0
    private var hasMore = false
    private var nextCursor = ""

    /** 按请求顺序追加一页并拒绝跳页、空 cursor 和 cursor 循环。 */
    fun append(
        requestedCursor: String?,
        items: List<T>,
        total: Int,
        hasMore: Boolean,
        nextCursor: String,
    ) {
        if (total < 0) throw ReviewException.Protocol("分页 total 不能为负数")
        val normalizedRequestedCursor = requestedCursor.orEmpty()
        if (pageCount == 0 && normalizedRequestedCursor.isNotEmpty()) {
            throw ReviewException.Protocol("第一页不得携带 cursor")
        }
        if (pageCount > 0 && normalizedRequestedCursor != this.nextCursor) {
            throw ReviewException.Protocol("分页 cursor 未按顺序请求")
        }
        if (normalizedRequestedCursor.isNotEmpty() && !requestedCursors.add(normalizedRequestedCursor)) {
            throw ReviewException.Protocol("分页 cursor 已被请求")
        }

        val normalizedNextCursor = if (hasMore) {
            if (nextCursor.isEmpty()) throw ReviewException.Protocol("下一页 cursor 为空")
            if (nextCursor == normalizedRequestedCursor || nextCursor in emittedCursors ||
                nextCursor in requestedCursors
            ) {
                throw ReviewException.Protocol("下一页 cursor 重复")
            }
            if (nextCursor.toByteArray(Charsets.UTF_8).size > 4096) {
                throw ReviewException.Protocol("下一页 cursor 超过长度上限")
            }
            emittedCursors += nextCursor
            nextCursor
        } else {
            ""
        }

        items.forEach { item ->
            val id = idOf(item)
            if (id.isEmpty()) throw ReviewException.Protocol("分页项目 ID 为空")
            itemsById[id] = item
        }
        this.total = total
        this.hasMore = hasMore
        this.nextCursor = normalizedNextCursor
        pageCount++
    }

    /** 返回当前全部页的不可变有序快照。 */
    fun snapshot(): CursorPageSnapshot<T> = CursorPageSnapshot(
        items = itemsById.values.toList(),
        total = total,
        hasMore = hasMore,
        nextCursor = nextCursor,
    )

    /** 返回已发出的 cursor，供下一页严格 parser 检测循环。 */
    fun seenCursors(): Set<String> = (emittedCursors + requestedCursors).toSet()

    /** 判断是否已经成功合并过至少一个分页，区分首屏失败与加载更多失败。 */
    fun hasLoadedPage(): Boolean = pageCount > 0

    /** 清空已加载页和 cursor 链以支持主动刷新。 */
    fun reset() {
        itemsById.clear()
        emittedCursors.clear()
        requestedCursors.clear()
        pageCount = 0
        total = 0
        hasMore = false
        nextCursor = ""
    }
}
