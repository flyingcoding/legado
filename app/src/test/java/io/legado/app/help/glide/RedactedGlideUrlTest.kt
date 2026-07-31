package io.legado.app.help.glide

import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URL

class RedactedGlideUrlTest {

    /** 验证日志模型字符串不包含完整 URL、query 或凭据。 */
    @Test
    fun redactedModel_hidesUrlButKeepsRealCacheKey() {
        val realUrl = "https://example.invalid/review/image.png?token=synthetic-secret"
        val model = RedactedGlideUrl.fromOrNull(realUrl)!!

        assertEquals(RedactedGlideUrl.REDACTED_MODEL_TEXT, model.toString())
        assertFalse(model.toString().contains("example.invalid"))
        assertFalse(model.toString().contains("synthetic-secret"))
        assertEquals(realUrl, model.cacheKey)
        assertEquals(realUrl, model.toStringUrl())
        assertEquals(realUrl, model.fetchUrl())
    }

    /** 验证地址会先经 HttpUrl 规范化，非法或非 HTTP(S) 模型不会被包装。 */
    @Test
    fun redactedModel_acceptsOnlyCanonicalHttpUrls() {
        val originalUrl = "HTTPS://EXAMPLE.INVALID:443/a/../image.png"
        val model = RedactedGlideUrl.fromOrNull(originalUrl)!!

        assertEquals(originalUrl, model.fetchUrl())
        assertEquals("https://example.invalid/image.png", model.toStringUrl())
        assertEquals("https://example.invalid/image.png", model.cacheKey)
        listOf(
            "file:///tmp/image.png",
            "content://images/1",
            "data:image/png;base64,AA",
            "/relative/image.png",
            "not a url",
        ).forEach { url ->
            assertNull(url, RedactedGlideUrl.fromOrNull(url))
        }
    }

    /** 验证远程图片 URL 选项进入 AnalyzeUrl 前保持包装前原文。 */
    @Test
    fun redactedModel_preservesAnalyzeUrlOptions() {
        val ruleUrl = """https://example.invalid/image.png,{"headers":{"Referer":"https://source.invalid"}}"""
        val model = RedactedGlideUrl.fromOrNull(ruleUrl)!!

        assertEquals(ruleUrl, model.fetchUrl())
        assertEquals(RedactedGlideUrl.REDACTED_MODEL_TEXT, model.toString())
        assertFalse(model.toString().contains("Referer"))
        assertFalse(model.toString().contains("source.invalid"))
    }

    /** 验证共享 fetcher 不会改变普通 GlideUrl 交给 AnalyzeUrl 的原始规则字符串。 */
    @Test
    fun fetchUrl_preservesLegacyGlideUrlStringSemantics() {
        val model = FakeLegacyGlideUrl()

        assertEquals(LEGACY_RAW_URL, model.fetchUrl())
        assertEquals(LEGACY_RAW_URL, model.toString())
        assertFalse(model.toStringUrlCalled)
    }

    /** 提供能区分原始字符串与转义请求字符串的普通 GlideUrl。 */
    private class FakeLegacyGlideUrl :
        GlideUrl(URL("https://unused.example.invalid"), TestHeaders) {

        var toStringUrlCalled = false

        /** 返回合成转义地址并记录是否错误进入该分支。 */
        override fun toStringUrl(): String {
            toStringUrlCalled = true
            return LEGACY_ESCAPED_URL
        }

        /** 返回旧 fetcher 交给 AnalyzeUrl 和图片解码器的原始规则字符串。 */
        override fun toString(): String = LEGACY_RAW_URL
    }

    private companion object {
        const val LEGACY_RAW_URL = "https://example.invalid/image name.png@js:legacy-rule"
        const val LEGACY_ESCAPED_URL =
            "https://example.invalid/image%20name.png@js:legacy-rule"
    }

    /** 为 JVM 测试避开 Glide 默认 Android User-Agent 初始化。 */
    private object TestHeaders : Headers {

        /** 测试 fetch 语义无需附加请求头。 */
        override fun getHeaders(): Map<String, String> = emptyMap()
    }
}
