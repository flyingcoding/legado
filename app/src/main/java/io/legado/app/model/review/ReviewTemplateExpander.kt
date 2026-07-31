package io.legado.app.model.review

import io.legado.app.BuildConfig
import io.legado.app.data.entities.rule.ReviewRule
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 封装段评 HTTP 的构建类型与书源显式调试声明。 */
data class ReviewTransportPolicy(
    val isDebugBuild: Boolean,
    val declaredPolicy: String?,
) {

    /** 仅在 debug 构建且声明值受支持时允许远程 HTTP。 */
    fun allowsRemoteHttp(): Boolean =
        isDebugBuild && declaredPolicy == ReviewRule.DEBUG_HTTP_TRANSPORT_POLICY

    companion object {

        /** 使用当前构建类型和规则声明创建运行时策略。 */
        fun fromRule(rule: ReviewRule): ReviewTransportPolicy = ReviewTransportPolicy(
            isDebugBuild = BuildConfig.DEBUG,
            declaredPolicy = rule.transportPolicy,
        )

        /** 创建未声明远程 HTTP 的默认策略。 */
        fun default(): ReviewTransportPolicy = ReviewTransportPolicy(
            isDebugBuild = BuildConfig.DEBUG,
            declaredPolicy = null,
        )
    }
}

/** 保存 URL 模板展开时允许使用的七类合同变量。 */
data class ReviewTemplateValues(
    val bookId: String? = null,
    val itemId: String? = null,
    val paraId: Int? = null,
    val itemVersion: String? = null,
    val commentId: String? = null,
    val pageSize: Int? = null,
    val cursor: String? = null,
) {

    /** 将模板变量转换为统一的字符串映射。 */
    fun asMap(): Map<String, String?> = mapOf(
        "bookId" to bookId,
        "itemId" to itemId,
        "paraId" to paraId?.toString(),
        "itemVersion" to itemVersion,
        "commentId" to commentId,
        "pageSize" to pageSize?.toString(),
        "cursor" to cursor,
    )
}

/** 安全展开固定 v1 GET 端点的 query 模板。 */
object ReviewTemplateExpander {

    private val placeholderRegex = Regex("\\{\\{([A-Za-z][A-Za-z0-9]*)\\}\\}")
    private val anyTemplateMarkerRegex = Regex("\\{\\{.*?\\}\\}")
    private val decimalIdRegex = Regex("^[0-9]+$")

    private data class EndpointPolicy(
        val variables: Set<String>,
        val requiredVariables: Set<String>,
        val queryParameters: Set<String>,
        val variableParameters: Map<String, String>,
    )

    private val policies = mapOf(
        ReviewEndpoint.INDEX to EndpointPolicy(
            variables = setOf("bookId", "itemId", "itemVersion"),
            requiredVariables = setOf("bookId", "itemId"),
            queryParameters = setOf(
                "book_id", "item_id", "item_version", "detail_limit", "comment_count",
                "include_replies", "reply_count",
            ),
            variableParameters = mapOf(
                "bookId" to "book_id",
                "itemId" to "item_id",
                "itemVersion" to "item_version",
            ),
        ),
        ReviewEndpoint.COMMENT_PAGE to EndpointPolicy(
            variables = setOf("bookId", "itemId", "paraId", "itemVersion", "pageSize", "cursor"),
            requiredVariables = setOf(
                "bookId", "itemId", "paraId", "itemVersion", "pageSize", "cursor",
            ),
            queryParameters = setOf(
                "book_id", "item_id", "para_id", "item_version", "count", "cursor",
            ),
            variableParameters = mapOf(
                "bookId" to "book_id",
                "itemId" to "item_id",
                "paraId" to "para_id",
                "itemVersion" to "item_version",
                "pageSize" to "count",
                "cursor" to "cursor",
            ),
        ),
        ReviewEndpoint.REPLY_PAGE to EndpointPolicy(
            variables = setOf("bookId", "itemId", "commentId", "pageSize", "cursor"),
            requiredVariables = setOf("bookId", "itemId", "commentId", "pageSize", "cursor"),
            queryParameters = setOf("book_id", "item_id", "comment_id", "count", "cursor"),
            variableParameters = mapOf(
                "bookId" to "book_id",
                "itemId" to "item_id",
                "commentId" to "comment_id",
                "pageSize" to "count",
                "cursor" to "cursor",
            ),
        ),
    )

    /** 校验并展开指定端点的只读 URL 模板。 */
    fun expand(
        sourceUrl: String,
        endpoint: ReviewEndpoint,
        template: String,
        values: ReviewTemplateValues,
        transportPolicy: ReviewTransportPolicy = ReviewTransportPolicy.default(),
    ): HttpUrl {
        val source = sourceUrl.toHttpUrlOrNull()
            ?: throw ReviewException.InvalidTemplate("书源地址不是有效 URL")
        validateOriginScheme(source, transportPolicy)
        validateTemplateText(template)
        validateValues(values)

        val policy = policies.getValue(endpoint)
        val matches = placeholderRegex.findAll(template).toList()
        val variableNames = matches.map { it.groupValues[1] }
        if (variableNames.any { it !in policy.variables }) {
            throw ReviewException.InvalidTemplate("包含当前端点不允许的变量")
        }
        if (!variableNames.containsAll(policy.requiredVariables)) {
            throw ReviewException.InvalidTemplate("缺少当前端点必需的变量")
        }
        if (anyTemplateMarkerRegex.findAll(template).count() != matches.size) {
            throw ReviewException.InvalidTemplate("包含无法识别的模板标记")
        }

        val sentinels = variableNames.distinct().associateWith { name -> "legado_review_$name" }
        val safeTemplate = placeholderRegex.replace(template) { match ->
            sentinels.getValue(match.groupValues[1])
        }
        val parsed = source.resolve(safeTemplate)
            ?: throw ReviewException.InvalidTemplate("无法解析端点地址")
        validateParsedTemplate(
            source = source,
            endpoint = endpoint,
            parsed = parsed,
            policy = policy,
            sentinels = sentinels,
            transportPolicy = transportPolicy,
        )

        val builder = parsed.newBuilder()
        val valueMap = values.asMap()
        sentinels.forEach { (name, sentinel) ->
            val parameter = parsed.queryParameterNames.singleOrNull { queryName ->
                parsed.queryParameterValues(queryName).singleOrNull()?.contains(sentinel) == true
            } ?: throw ReviewException.InvalidTemplate("变量没有位于独立 query value")
            val rawTemplateValue = parsed.queryParameter(parameter)
                ?: throw ReviewException.InvalidTemplate("模板 query value 为空")
            val value = valueMap[name]
            if (name == "cursor" && value.isNullOrEmpty()) {
                builder.removeAllQueryParameters(parameter)
            } else {
                val expandedValue = rawTemplateValue.replace(
                    sentinel,
                    value ?: throw ReviewException.InvalidArgument(name),
                )
                builder.setQueryParameter(parameter, expandedValue)
            }
        }
        return builder.build()
    }

    /** 校验响应最终 URL 未通过重定向离开书源 origin。 */
    fun requireSameOrigin(
        sourceUrl: String,
        responseUrl: String,
        transportPolicy: ReviewTransportPolicy = ReviewTransportPolicy.default(),
    ) {
        val source = sourceUrl.toHttpUrlOrNull()
            ?: throw ReviewException.InvalidTemplate("书源地址不是有效 URL")
        val response = responseUrl.toHttpUrlOrNull()
            ?: throw ReviewException.Protocol("响应最终地址无效")
        try {
            validateSameOrigin(source, response, transportPolicy)
        } catch (_: ReviewException.InvalidTemplate) {
            throw ReviewException.Protocol("响应重定向离开书源 origin")
        }
    }

    /** 编码 AnalyzeUrl 会再次解释的规则起始符，同时保持 query 解码值不变。 */
    fun toAnalyzeUrlInput(url: HttpUrl): String = url.toString().replace("@", "%40")

    /** 校验模板中不存在 method、body 或片段等覆写语法。 */
    private fun validateTemplateText(template: String) {
        if (template.isBlank() || template.any { it == '\r' || it == '\n' }) {
            throw ReviewException.InvalidTemplate("模板为空或包含换行")
        }
        val lower = template.lowercase()
        if ('#' in template || Regex(",\\s*\\{").containsMatchIn(template) ||
            "@js" in lower || "<js>" in lower || "</js>" in lower ||
            "@post" in lower || "@put" in lower || "@delete" in lower ||
            "\"method\"" in lower || "\"body\"" in lower
        ) {
            throw ReviewException.InvalidTemplate("模板包含只读 GET 之外的配置")
        }
    }

    /** 校验解析后的路径、origin、参数名、重复参数和变量绑定。 */
    private fun validateParsedTemplate(
        source: HttpUrl,
        endpoint: ReviewEndpoint,
        parsed: HttpUrl,
        policy: EndpointPolicy,
        sentinels: Map<String, String>,
        transportPolicy: ReviewTransportPolicy,
    ) {
        validateSameOrigin(source, parsed, transportPolicy)
        if (parsed.encodedPath != endpoint.path) {
            throw ReviewException.InvalidTemplate("端点路径不匹配")
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw ReviewException.InvalidTemplate("端点不得包含用户信息")
        }
        if (parsed.queryParameterNames.any { it !in policy.queryParameters }) {
            throw ReviewException.InvalidTemplate("包含合同未定义的 query 参数")
        }
        parsed.queryParameterNames.forEach { parameter ->
            if (parsed.queryParameterValues(parameter).size != 1) {
                throw ReviewException.InvalidTemplate("query 参数重复")
            }
        }
        sentinels.forEach { (variable, sentinel) ->
            val expectedParameter = policy.variableParameters.getValue(variable)
            val actualValues = parsed.queryParameterValues(expectedParameter)
            if (actualValues.size != 1 || actualValues.single() != sentinel) {
                throw ReviewException.InvalidTemplate("变量未绑定到合同参数")
            }
        }
    }

    /** 校验书源和端点仅使用 HTTPS、debug 本机 HTTP 或显式 debug 远程 HTTP。 */
    private fun validateOriginScheme(url: HttpUrl, transportPolicy: ReviewTransportPolicy) {
        val localHosts = setOf("localhost", "127.0.0.1", "::1")
        val isHttp = url.scheme == "http"
        val isDebugLoopback = transportPolicy.isDebugBuild && isHttp && url.host in localHosts
        val isExplicitDebugRemote = isHttp && transportPolicy.allowsRemoteHttp()
        if (url.scheme != "https" && !isDebugLoopback && !isExplicitDebugRemote) {
            throw ReviewException.InvalidTemplate("段评传输策略不允许当前协议")
        }
    }

    /** 校验端点与书源使用完全相同的 scheme、host 和 port。 */
    private fun validateSameOrigin(
        source: HttpUrl,
        endpoint: HttpUrl,
        transportPolicy: ReviewTransportPolicy,
    ) {
        validateOriginScheme(source, transportPolicy)
        validateOriginScheme(endpoint, transportPolicy)
        if (source.scheme != endpoint.scheme || source.host != endpoint.host || source.port != endpoint.port) {
            throw ReviewException.InvalidTemplate("端点与书源不同源")
        }
    }

    /** 校验七类模板输入的本地范围和编码边界。 */
    private fun validateValues(values: ReviewTemplateValues) {
        listOf("bookId" to values.bookId, "itemId" to values.itemId, "commentId" to values.commentId)
            .forEach { (name, value) ->
                if (value != null && !decimalIdRegex.matches(value)) {
                    throw ReviewException.InvalidArgument(name)
                }
            }
        if (values.paraId != null && values.paraId < 0) {
            throw ReviewException.InvalidArgument("paraId")
        }
        if (values.pageSize != null && values.pageSize !in 1..50) {
            throw ReviewException.InvalidArgument("pageSize")
        }
        values.itemVersion?.let { itemVersion ->
            if (itemVersion.toByteArray(Charsets.UTF_8).size !in 1..128 ||
                itemVersion.any { it == '\r' || it == '\n' }
            ) {
                throw ReviewException.InvalidArgument("itemVersion")
            }
        }
        values.cursor?.let { cursor ->
            if (cursor.toByteArray(Charsets.UTF_8).size > 4096) {
                throw ReviewException.InvalidArgument("cursor")
            }
        }
    }
}
