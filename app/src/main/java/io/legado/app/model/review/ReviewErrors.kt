package io.legado.app.model.review

import io.legado.app.exception.NoStackTraceException

/** 表示可稳定分类且不会携带响应正文的段评失败。 */
sealed class ReviewException(
    message: String,
    open val retryable: Boolean = false,
) : NoStackTraceException(message) {

    /** 表示书源没有完整且受支持的只读段评能力。 */
    class UnsupportedSource : ReviewException("当前书源不支持段评")

    /** 表示书源中的只读 URL 模板不满足安全约束。 */
    class InvalidTemplate(reason: String) : ReviewException("段评 URL 模板无效：$reason")

    /** 表示请求参数在发送前未通过合同校验。 */
    class InvalidArgument(val parameter: String) :
        ReviewException("段评请求参数无效：$parameter")

    /** 表示公共评论服务拒绝了当前认证信息。 */
    class Authentication : ReviewException("段评服务认证失败")

    /** 表示评论服务请求发生可重试的传输失败。 */
    class Network : ReviewException("段评服务网络请求失败", retryable = true)

    /** 表示评论服务返回了非成功 HTTP 状态。 */
    class Http(
        val status: Int,
        override val retryable: Boolean,
        val retryAfterSeconds: Long? = null,
    ) : ReviewException("段评服务请求失败（HTTP $status）", retryable)

    /** 表示评论服务返回了合同内的稳定业务错误。 */
    class Api(
        val status: Int,
        val type: String,
        override val retryable: Boolean,
        val parameter: String,
        val retryAfterSeconds: Long? = null,
    ) : ReviewException("段评服务返回错误：$type", retryable)

    /** 表示服务响应不符合固定 v1 合同。 */
    class Protocol(reason: String) : ReviewException("段评响应协议错误：$reason")
}
