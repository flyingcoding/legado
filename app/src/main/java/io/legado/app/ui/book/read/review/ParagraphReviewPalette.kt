package io.legado.app.ui.book.read.review

import androidx.core.graphics.toColorInt
import io.legado.app.help.config.ReadBookConfig

/** 保存段评抽屉一次展示期间使用的当前阅读主题颜色。 */
internal data class ParagraphReviewPalette(
    val background: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val divider: Int,
    val ripple: Int,
)

/** 从当前阅读配置生成段评抽屉统一使用的颜色集合。 */
internal fun currentParagraphReviewPalette(): ParagraphReviewPalette {
    val config = ReadBookConfig.durConfig
    val solidBackground = if (config.curBgType() == 0) {
        runCatching { config.curBgStr().toColorInt() }.getOrNull()
    } else {
        null
    }
    val background = resolveParagraphReviewBackgroundColor(
        backgroundType = config.curBgType(),
        solidBackground = solidBackground,
        backgroundMeanColor = ReadBookConfig.bgMeanColor,
    )
    return paragraphReviewPalette(background, ReadBookConfig.textColor)
}

/** 纯色主题返回精确背景色，图片主题和非法纯色回退阅读背景均值色。 */
internal fun resolveParagraphReviewBackgroundColor(
    backgroundType: Int,
    solidBackground: Int?,
    backgroundMeanColor: Int,
): Int = if (backgroundType == 0 && solidBackground != null) {
    solidBackground
} else {
    backgroundMeanColor
}

/** 根据阅读背景与正文色派生主次文字、强调、分隔和触摸反馈颜色。 */
internal fun paragraphReviewPalette(
    background: Int,
    textColor: Int,
): ParagraphReviewPalette {
    val opaqueText = paragraphReviewColorWithAlpha(textColor, 0xFF)
    return ParagraphReviewPalette(
        background = background,
        primaryText = textColor,
        secondaryText = paragraphReviewColorWithAlpha(textColor, 0xA3),
        accent = opaqueText,
        divider = paragraphReviewColorWithAlpha(textColor, 0x38),
        ripple = paragraphReviewColorWithAlpha(textColor, 0x24),
    )
}

/** 保留颜色 RGB，并替换为指定的有界 Alpha。 */
internal fun paragraphReviewColorWithAlpha(color: Int, alpha: Int): Int =
    (alpha.coerceIn(0, 0xFF) shl 24) or (color and 0x00FFFFFF)
