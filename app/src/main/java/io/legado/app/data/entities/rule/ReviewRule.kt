package io.legado.app.data.entities.rule

import android.os.Parcelable
import com.google.gson.JsonDeserializer
import io.legado.app.utils.INITIAL_GSON
import kotlinx.parcelize.Parcelize

@Parcelize
data class ReviewRule(
    var contractVersion: String? = null,
    var transportPolicy: String? = null,
    var paragraphMappingMode: String? = null,
    var reviewIndexUrl: String? = null,
    var reviewUrl: String? = null,
    var reviewQuoteUrl: String? = null,
    var paragraphListRule: String? = null,
    var paragraphIdRule: String? = null,
    var paragraphCountRule: String? = null,
    var commentListRule: String? = null,
    var commentIdRule: String? = null,
    var userIdRule: String? = null,
    var userNameRule: String? = null,
    var avatarRule: String? = null,
    var contentRule: String? = null,
    var imageListRule: String? = null,
    var imageUrlRule: String? = null,
    var imageWidthRule: String? = null,
    var imageHeightRule: String? = null,
    var imageFormatRule: String? = null,
    var postTimeRule: String? = null,
    var voteUpCountRule: String? = null,
    var quoteCountRule: String? = null,
    var hasMoreRule: String? = null,
    var nextCursorRule: String? = null,
    var quoteListRule: String? = null,
    var quoteIdRule: String? = null,
    var quoteParentIdRule: String? = null,
    var quoteUserIdRule: String? = null,
    var quoteUserNameRule: String? = null,
    var quoteAvatarRule: String? = null,
    var quoteContentRule: String? = null,
    var quoteImageListRule: String? = null,
    var quoteImageUrlRule: String? = null,
    var quoteImageWidthRule: String? = null,
    var quoteImageHeightRule: String? = null,
    var quoteImageFormatRule: String? = null,
    var quotePostTimeRule: String? = null,
    var quoteVoteUpCountRule: String? = null,
    var quoteChildrenRule: String? = null,
) : Parcelable {

    /** fanqie 段评 v1 必填字段及其当前值。 */
    val paragraphCommentsV1RequiredFields: Map<String, String?>
        get() = linkedMapOf(
            "contractVersion" to contractVersion,
            "reviewIndexUrl" to reviewIndexUrl,
            "reviewUrl" to reviewUrl,
            "reviewQuoteUrl" to reviewQuoteUrl,
            "paragraphListRule" to paragraphListRule,
            "paragraphIdRule" to paragraphIdRule,
            "paragraphCountRule" to paragraphCountRule,
            "commentListRule" to commentListRule,
            "commentIdRule" to commentIdRule,
            "contentRule" to contentRule,
            "postTimeRule" to postTimeRule,
            "voteUpCountRule" to voteUpCountRule,
            "quoteCountRule" to quoteCountRule,
            "hasMoreRule" to hasMoreRule,
            "nextCursorRule" to nextCursorRule,
            "quoteListRule" to quoteListRule,
            "quoteIdRule" to quoteIdRule,
            "quoteContentRule" to quoteContentRule,
            "quotePostTimeRule" to quotePostTimeRule,
            "quoteVoteUpCountRule" to quoteVoteUpCountRule,
        )

    /** 判断当前规则是否完整支持 fanqie 段评 v1。 */
    fun supportsParagraphCommentsV1(): Boolean =
        contractVersion == PARAGRAPH_COMMENTS_V1_CONTRACT &&
            paragraphCommentsV1RequiredFields.values.all { !it.isNullOrBlank() }

    /** fanqie 段评图片 v1 必填规则及其当前值。 */
    val paragraphCommentImagesV1RequiredFields: Map<String, String?>
        get() = linkedMapOf(
            "imageListRule" to imageListRule,
            "imageUrlRule" to imageUrlRule,
            "imageWidthRule" to imageWidthRule,
            "imageHeightRule" to imageHeightRule,
            "imageFormatRule" to imageFormatRule,
            "quoteImageListRule" to quoteImageListRule,
            "quoteImageUrlRule" to quoteImageUrlRule,
            "quoteImageWidthRule" to quoteImageWidthRule,
            "quoteImageHeightRule" to quoteImageHeightRule,
            "quoteImageFormatRule" to quoteImageFormatRule,
        )

    /** 判断当前规则是否完整声明段评和回复图片选择器。 */
    fun supportsParagraphCommentImagesV1(): Boolean =
        supportsParagraphCommentsV1() &&
            paragraphCommentImagesV1RequiredFields.values.all { !it.isNullOrBlank() }

    companion object {

        const val PARAGRAPH_COMMENTS_V1_CONTRACT = "fanqie.paragraph-comments.v1"
        const val DEBUG_HTTP_TRANSPORT_POLICY = "debug-http"
        const val FANQIE_PARAGRAPH_INDEX_MAPPING_MODE = "fanqie.paragraph-index.v1"

        val jsonDeserializer = JsonDeserializer<ReviewRule?> { json, _, _ ->
            runCatching {
                when {
                    json.isJsonObject -> INITIAL_GSON.fromJson(json, ReviewRule::class.java)
                    json.isJsonPrimitive ->
                        INITIAL_GSON.fromJson(json.asString, ReviewRule::class.java)

                    else -> null
                }
            }.getOrNull()
        }

    }

}
