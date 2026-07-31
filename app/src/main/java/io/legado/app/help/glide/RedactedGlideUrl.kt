package io.legado.app.help.glide

import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.Headers
import io.legado.app.utils.isAbsUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URL

/**
 * 保存真实远程图片地址用于请求和缓存，同时让 Glide 日志中的模型字符串保持脱敏。
 */
internal class RedactedGlideUrl private constructor(
    private val analyzeUrlInput: String,
    private val canonicalUrl: String,
) : GlideUrl(URL(canonicalUrl), RedactedHeaders) {

    /** 返回已经由 HttpUrl 规范化的真实请求地址。 */
    override fun toStringUrl(): String = canonicalUrl

    /** 返回不包含远程地址、query 或凭据的稳定日志文本。 */
    override fun toString(): String = REDACTED_MODEL_TEXT

    /** 返回包装前字符串，避免改变 AnalyzeUrl 的 URL 选项和脚本语义。 */
    internal fun analyzeUrlInput(): String = analyzeUrlInput

    companion object {

        const val REDACTED_MODEL_TEXT = "[redacted remote image]"

        /** 将 HTTP(S) 地址转换为可安全记录且保留旧规则语义的 Glide 模型。 */
        fun fromOrNull(url: String?): RedactedGlideUrl? {
            val remoteUrl = url?.takeIf(String::isAbsUrl) ?: return null
            val canonicalUrl = remoteUrl.toHttpUrlOrNull()?.toString() ?: return null
            return RedactedGlideUrl(remoteUrl, canonicalUrl)
        }
    }
}

/** 提供不触发 Glide 默认 Android User-Agent 初始化的稳定空请求头。 */
private object RedactedHeaders : Headers {

    /** 远程图片请求头继续由 sourceOrigin/AnalyzeUrl 注入。 */
    override fun getHeaders(): Map<String, String> = emptyMap()
}
