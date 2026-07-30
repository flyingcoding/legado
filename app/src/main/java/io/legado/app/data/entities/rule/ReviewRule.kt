package io.legado.app.data.entities.rule

import android.os.Parcelable
import com.google.gson.JsonDeserializer
import io.legado.app.utils.INITIAL_GSON
import kotlinx.parcelize.Parcelize

@Parcelize
data class ReviewRule(
    var contractVersion: String? = null,
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

    companion object {

        const val PARAGRAPH_COMMENTS_V1_CONTRACT = "fanqie.paragraph-comments.v1"

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
