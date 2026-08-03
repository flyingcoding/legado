package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.content.Context
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.help.config.ReadBookConfig
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
        val bodyLeft = start + height / 6
        val bodyRight = end - 1
        val bodyTop = baseLine - height * 0.8f
        val bodyBottom = baseLine
        path.reset()
        path.moveTo(start + 1, baseLine - height * 2 / 5)
        path.lineTo(bodyLeft, baseLine - height * 0.55f)
        path.lineTo(bodyLeft, bodyTop)
        path.lineTo(bodyRight, bodyTop)
        path.lineTo(bodyRight, bodyBottom)
        path.lineTo(bodyLeft, bodyBottom)
        path.lineTo(bodyLeft, baseLine - height / 4)
        path.close()
        val reviewPaint = ChapterProvider.reviewPaint
        reviewPaint.color = ReadBookConfig.textColor
        reviewPaint.textSize = reviewColumnTextSize(height, countText.length)
        val maxTextWidth = (bodyRight - bodyLeft).coerceAtLeast(0f) * 0.88f
        val measuredTextWidth = reviewPaint.measureText(countText)
        if (maxTextWidth > 0f && measuredTextWidth > maxTextWidth) {
            reviewPaint.textSize *= maxTextWidth / measuredTextWidth
        }
        reviewPaint.style = Paint.Style.STROKE
        canvas.drawPath(path, reviewPaint)
        reviewPaint.style = Paint.Style.FILL
        val textCenterY = (bodyTop + bodyBottom) / 2
        val textBaseline = textCenterY -
                (reviewPaint.fontMetrics.ascent + reviewPaint.fontMetrics.descent) / 2
        canvas.drawText(
            countText,
            (bodyLeft + bodyRight) / 2,
            textBaseline,
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

/** 根据正文高度和计数字符数返回角标字号，保证长计数不贴边。 */
fun reviewColumnTextSize(height: Float, textLength: Int): Float = height * when {
    textLength <= 1 -> 0.52f
    textLength <= 3 -> 0.40f
    else -> 0.32f
}
