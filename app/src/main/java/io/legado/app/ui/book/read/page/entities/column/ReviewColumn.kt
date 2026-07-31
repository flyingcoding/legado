package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.content.Context
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 评论按钮列
 */
@Keep
data class ReviewColumn(
    override var start: Float,
    override var end: Float,
    val paraId: Int,
    val count: Int,
    val generation: Long,
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine
    override fun draw(view: ContentTextView, canvas: Canvas) {
        val textPaint = if (textLine.isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        drawToCanvas(
            canvas,
            reviewColumnLocalBaseline(textLine.lineBase, textLine.lineTop),
            textPaint.textSize,
        )
    }

    val countText by lazy {
        formatParagraphReviewCount(count)
    }

    /** 返回供 TalkBack 使用的真实评论计数描述。 */
    fun accessibilityDescription(context: Context): String =
        context.resources.getQuantityString(R.plurals.review_count_description, count, count)

    val path by lazy { Path() }

    fun drawToCanvas(canvas: Canvas, baseLine: Float, height: Float) {
        if (count == 0) return
        path.reset()
        path.moveTo(start + 1, baseLine - height * 2 / 5)
        path.lineTo(start + height / 6, baseLine - height * 0.55f)
        path.lineTo(start + height / 6, baseLine - height * 0.8f)
        path.lineTo(end - 1, baseLine - height * 0.8f)
        path.lineTo(end - 1, baseLine)
        path.lineTo(start + height / 6, baseLine)
        path.lineTo(start + height / 6, baseLine - height / 4)
        path.close()
        val reviewPaint = ChapterProvider.reviewPaint
        reviewPaint.style = Paint.Style.STROKE
        canvas.drawPath(path, reviewPaint)
        reviewPaint.style = Paint.Style.FILL
        canvas.drawText(
            countText,
            (start + height / 9 + end) / 2,
            baseLine - height * 0.23f,
            reviewPaint
        )
    }


}

/** 把真实段评计数格式化为阅读气泡的有界文本。 */
fun formatParagraphReviewCount(count: Int): String =
    if (count > 999) "999+" else count.toString()

/** 把整页绝对基线转换成 TextLine 离屏画布使用的行内基线。 */
fun reviewColumnLocalBaseline(lineBase: Float, lineTop: Float): Float =
    lineBase - lineTop
