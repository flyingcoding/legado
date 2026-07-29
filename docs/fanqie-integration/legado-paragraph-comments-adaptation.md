# Legado 段落评论适配指南

本文指导后续在 Legado Android 源码中接入 `fanqie.paragraph-comments.v1`。本任务不修改 Legado 仓库，以下内容是基于只读代码审计得到的实施方案。

## 1. 参考基线与兼容结论

审计基线：

```text
仓库：/Users/flying/Desktop/do/legado
revision：master@5f5d31404af9957684703747ecee2b91e7161f1d
```

结论：该 revision 有未完成的段评骨架，但原版构建不能消费 `fanqie-legado-source.json` 中的 `ruleReview`，也不能展示可用的段评 UI。

| 锚点 | 当前事实 | 必要改造 |
| --- | --- | --- |
| `data/entities/BookSource.kt` | 已有 `ruleReview: ReviewRule?`；`getReviewRule()` 被注释 | 恢复 getter，保持未知字段兼容 |
| `BookSource.Converters` | `stringToReviewRule()` 固定返回 null，`reviewRuleToString()` 固定写入 `"null"` | 使用现有 Gson 安全读写并做 round-trip 测试 |
| `data/entities/rule/ReviewRule.kt` | 仅有早期 `reviewUrl`、基础选择器、回复 URL 和未来写 URL | 扩展为 v1 只读字段；移除或始终禁用写字段 |
| `ui/book/source/edit/BookSourceEditActivity.kt` | `reviewEntities`、第 7 个 tab、读取和保存逻辑全部注释 | 恢复只读规则编辑；不显示写操作 URL |
| `activity_book_source_edit.xml` | `cb_is_enable_review` 为 `visibility="gone"` | 改成“来源支持 + 用户选择”的能力开关 |
| `help/config/AppConfig.kt` | `enableReview` getter 被 `BuildConfig.DEBUG` 强制限制 | 发布版按能力和用户开关启用，不再只限 debug |
| `menu/book_read.xml`、`ReadBookActivity.kt` | 阅读菜单项隐藏，菜单处理被注释 | 对支持 v1 的在线书源显示；切换后触发重新排版和索引请求 |
| `ChapterProvider.kt` | 插入 `reviewChar`；旧排版路径创建固定 `count=100` 的 `ReviewColumn` | 删除固定值，传入真实 `paraId/count`；不要在无映射时插占位列 |
| `TextChapterLayout.kt` | 当前阅读排版路径会插 `reviewChar`，但转为 `ReviewColumn` 的分支被注释 | 恢复并接入真实模型；这是当前主要展示路径 |
| `ReviewColumn.kt` | 能画计数气泡，但只有 `count` | 增加 `paraId`、章节 generation 和可访问性描述 |
| `ContentTextView.kt` | 点击 `ReviewColumn` 只显示 `Button Pressed!` toast | 通过事件回调打开主评列表 |
| `ReadBook.kt` | 有章节加载 job、取消和 `TextChapterLayout` 生命周期 | 把评论 job 绑定到相同章节 generation，禁止旧结果覆盖新章 |
| `BookHelp.kt` / `BookChapter.kt` | 章节 URL 和变量可用；`BookChapter` 有 `url/baseUrl/variable` | 安全提取并保存 `book_id/item_id`，不要从标题或正文猜测 |

原版 Legado 导入新版书源后，搜索、详情、目录和正文规则仍可使用；`ruleReview` 会被忽略或在保存书源时被 converter 写成 `null`。因此书源注释和 UI 必须明确：“段评需要修改 Legado 源码后才能启用”。

## 2. 目标数据流

```text
章节正文先显示
  -> 从 BookChapter 的 URL/变量取得 bookId、itemId
  -> 异步 GET reviewIndexUrl（detail_limit=0）
  -> 校验 contract + DTO + itemId
  -> 建立 paraId -> count 映射
  -> 重新排版或局部刷新 ReviewColumn
  -> 点击气泡
      -> GET reviewUrl 第一页
      -> cursor 分页主评
      -> 点击某条回复数
          -> GET reviewQuoteUrl 第一页
          -> cursor 分页并跨页重建回复树
```

正文不能等待评论索引。评论失败不应阻塞阅读、替换规则、图片加载、翻页或本地书籍。

## 3. `ruleReview` v1

### 3.1 完整字段

| 字段 | 类型 | 必填 | 用途 |
| --- | --- | --- | --- |
| `contractVersion` | string | 是 | 必须等于 `fanqie.paragraph-comments.v1` |
| `reviewIndexUrl` | string | 是 | 章节段评索引 GET 模板 |
| `reviewUrl` | string | 是 | 单段主评分页 GET 模板 |
| `reviewQuoteUrl` | string | 是 | 回复页 GET 模板 |
| `paragraphListRule` | string | 是 | 从索引 envelope 取 `paragraphs` |
| `paragraphIdRule` | string | 是 | 从段落项取 `para_id` |
| `paragraphCountRule` | string | 是 | 从段落项取 `count` |
| `commentListRule` | string | 是 | 从主评页取 `comments` |
| `commentIdRule` | string | 是 | 主评 ID |
| `userIdRule` | string | 否 | 主评用户 ID |
| `userNameRule` | string | 否 | 主评用户名 |
| `avatarRule` | string | 否 | 主评头像 URL |
| `contentRule` | string | 是 | 主评正文 |
| `postTimeRule` | string | 是 | 主评 Unix 秒时间戳 |
| `voteUpCountRule` | string | 是 | 主评点赞展示数 |
| `quoteCountRule` | string | 是 | 主评回复数 |
| `hasMoreRule` | string | 是 | 主评/回复页 `has_more` |
| `nextCursorRule` | string | 是 | 主评/回复页 `next_cursor` |
| `quoteListRule` | string | 是 | 回复页 `replies` |
| `quoteIdRule` | string | 是 | 回复 ID |
| `quoteParentIdRule` | string | 否 | 直接父回复 ID；对应 `reply_to_reply_id` |
| `quoteUserIdRule` | string | 否 | 回复用户 ID |
| `quoteUserNameRule` | string | 否 | 回复用户名 |
| `quoteAvatarRule` | string | 否 | 回复头像 URL |
| `quoteContentRule` | string | 是 | 回复正文 |
| `quotePostTimeRule` | string | 是 | 回复 Unix 秒时间戳 |
| `quoteVoteUpCountRule` | string | 是 | 回复点赞展示数 |
| `quoteChildrenRule` | string | 否 | 当前页已组装的 `children` |

当前书源使用的规则值见仓库根目录 `fanqie-legado-source.json`。这些选择器是一个稳定配置层；建议解析器仍以 v1 DTO 做严格 envelope 校验，规则只负责字段定位，不允许 UI 直接操作原始 JSON。

### 3.2 模板变量

| 变量 | 来源 | 约束 |
| --- | --- | --- |
| `bookId` | 书籍详情/章节 URL 中的 `book_id` | 纯数字 |
| `itemId` | 目录项 URL 中的 `item_id` | 纯数字 |
| `paraId` | 索引响应的 `para_id` | `0..2147483647` |
| `itemVersion` | 索引响应；没有时为 `0` | UTF-8 `1..128` 字节 |
| `commentId` | 主评响应的 `comment_id` | 纯数字 |
| `pageSize` | 客户端常量或用户不可见配置 | `1..50`，推荐 20 |
| `cursor` | 上一页 `next_cursor` | opaque，最大 4096 UTF-8 字节 |

替换规则：

1. 所有值必须由 URL builder 编码，不能使用简单 `String.replace` 后直接发请求。
2. 第一页 cursor 为空时，删除整个 `cursor` query 参数；也可以编码为空，但删除更容易避免代理差异。
3. 模板展开后只允许同一 `bookSourceUrl` origin 的 HTTPS URL，或开发环境显式允许的本机 HTTP URL。
4. 展开结果方法必须是 GET；拒绝 `@POST`、JSON body、自定义 method 或跨域跳转。
5. `bookId/itemId/commentId/paraId/pageSize` 在生成 URL 前按本地规则校验，不依赖服务端兜底。

### 3.3 严格只读边界

下列旧骨架字段不得加入书源、编辑器、DTO 或请求 repository：

```text
voteUpUrl
voteDownUrl
postReviewUrl
postQuoteUrl
deleteUrl
```

同样不要新增点赞、点踩、举报、发评或删除按钮。即使旧 `ReviewRule.kt` 仍声明这些字段，也应在 v1 迁移时删除，或用 deprecated 私有字段只为旧 JSON 反序列化保留且永不执行。

## 4. 推荐模型

### 4.1 规则模型

在 `ReviewRule.kt` 中把对象扩展为只读 v1。字段可以继续 nullable 以兼容导入旧书源，但能力判断必须要求核心字段完整：

```kotlin
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
    /** 判断当前规则是否完整支持 fanqie 段评 v1。 */
    fun supportsParagraphCommentsV1(): Boolean =
        contractVersion == "fanqie.paragraph-comments.v1" &&
            !reviewIndexUrl.isNullOrBlank() &&
            !reviewUrl.isNullOrBlank() &&
            !reviewQuoteUrl.isNullOrBlank()
}
```

核心选择器也应加入能力检查；上例为简化伪代码，不能直接作为最终完整校验。

### 4.2 Wire DTO 与领域 DTO

网络 DTO 应允许 nullable，以便检测服务端缺字段；校验后映射到非空领域模型。不要让 Gson 默认值掩盖“字段缺失”和“服务端返回空数组”的区别。

```kotlin
data class ReviewEnvelopeWire<T>(
    val contract: String?,
    val code: Int?,
    val message: String?,
    val data: T?,
    val error: ReviewErrorWire?,
)

data class ReviewErrorWire(
    val type: String?,
    val retryable: Boolean?,
    val parameter: String?,
)

data class ReviewWarningWire(
    val scope: String?,
    val type: String?,
    val retryable: Boolean?,
    @SerializedName("para_id") val paraId: Int?,
    @SerializedName("comment_id") val commentId: String?,
)

data class ReviewIndexWire(
    @SerializedName("item_id") val itemId: String?,
    @SerializedName("book_id") val bookId: String?,
    @SerializedName("item_version") val itemVersion: String?,
    val paragraphs: List<ReviewParagraphWire>?,
    val partial: Boolean?,
    val warnings: List<ReviewWarningWire>?,
)

data class ReviewParagraphWire(
    @SerializedName("para_id") val paraId: Int?,
    val count: Int?,
    val hot: String?,
    @SerializedName("user_count") val userCount: Int?,
    @SerializedName("detail_loaded") val detailLoaded: Boolean?,
    val comments: List<ParagraphCommentWire>?,
)

data class ParagraphCommentPageWire(
    @SerializedName("item_id") val itemId: String?,
    @SerializedName("book_id") val bookId: String?,
    @SerializedName("item_version") val itemVersion: String?,
    @SerializedName("para_id") val paraId: Int?,
    val comments: List<ParagraphCommentWire>?,
    val total: Int?,
    @SerializedName("has_more") val hasMore: Boolean?,
    @SerializedName("next_cursor") val nextCursor: String?,
)

data class ParagraphCommentWire(
    @SerializedName("comment_id") val commentId: String?,
    val text: String?,
    @SerializedName("user_id") val userId: String?,
    @SerializedName("user_name") val userName: String?,
    @SerializedName("user_avatar") val userAvatar: String?,
    @SerializedName("create_timestamp") val createTimestamp: Long?,
    @SerializedName("digg_count") val diggCount: Int?,
    @SerializedName("reply_count") val replyCount: Int?,
    @SerializedName("replies_loaded") val repliesLoaded: Boolean?,
    val replies: List<ParagraphReplyWire>?,
    @SerializedName("reply_total") val replyTotal: Int?,
    @SerializedName("reply_has_more") val replyHasMore: Boolean?,
    @SerializedName("reply_next_cursor") val replyNextCursor: String?,
)

data class ParagraphReplyPageWire(
    @SerializedName("item_id") val itemId: String?,
    @SerializedName("book_id") val bookId: String?,
    @SerializedName("comment_id") val commentId: String?,
    val replies: List<ParagraphReplyWire>?,
    val total: Int?,
    @SerializedName("has_more") val hasMore: Boolean?,
    @SerializedName("next_cursor") val nextCursor: String?,
)

data class ParagraphReplyWire(
    @SerializedName("reply_id") val replyId: String?,
    @SerializedName("parent_reply_id") val parentReplyId: String?,
    @SerializedName("reply_to_comment_id") val replyToCommentId: String?,
    @SerializedName("reply_to_reply_id") val replyToReplyId: String?,
    val text: String?,
    @SerializedName("user_id") val userId: String?,
    @SerializedName("user_name") val userName: String?,
    @SerializedName("user_avatar") val userAvatar: String?,
    @SerializedName("reply_to_user_name") val replyToUserName: String?,
    @SerializedName("create_timestamp") val createTimestamp: Long?,
    @SerializedName("digg_count") val diggCount: Int?,
    @SerializedName("reply_count") val replyCount: Int?,
    val children: List<ParagraphReplyWire>?,
)
```

当前公开服务在段落详情 warning 和 eager 回复 warning 中都会返回 `para_id`；回复 warning 还会返回 `comment_id`。Wire DTO 保持 nullable，用于把字段缺失识别为协议错误，而不是静默补默认值。

领域模型至少增加：

```kotlin
data class ParagraphReviewSummary(
    val paraId: Int,
    val count: Int,
    val userCount: Int,
)

data class ReviewPage<T>(
    val items: List<T>,
    val total: Int,
    val hasMore: Boolean,
    val nextCursor: String,
)

sealed interface ReviewLoadState<out T> {
    data object Unsupported : ReviewLoadState<Nothing>
    data object Loading : ReviewLoadState<Nothing>
    data class Content<T>(val value: T, val partial: Boolean = false) : ReviewLoadState<T>
    data object Empty : ReviewLoadState<Nothing>
    data class Offline<T>(val cached: T?) : ReviewLoadState<T>
    data class Error(val type: String, val retryable: Boolean) : ReviewLoadState<Nothing>
}
```

领域层不应携带原始响应正文、Authorization、完整请求 URL 或 cursor 日志字段。

## 5. `BookSource` 持久化与迁移

### 5.1 修复 converter

`BookSource.kt` 当前 Room schema version 75 已包含 `ruleReview TEXT` 列；历史 schema 53–75 也已有该列。仅修复 converter 和扩展 `ReviewRule` JSON 字段不会改变表结构，因此不应无理由提升数据库版本。

建议实现：

```kotlin
/** 把数据库 JSON 解析为段评规则，畸形值降级为 null。 */
@TypeConverter
fun stringToReviewRule(json: String?): ReviewRule? =
    GSON.fromJsonObject<ReviewRule>(json).getOrNull()

/** 把段评规则稳定序列化到现有 ruleReview TEXT 列。 */
@TypeConverter
fun reviewRuleToString(reviewRule: ReviewRule?): String =
    GSON.toJson(reviewRule)
```

同时恢复 `getReviewRule()`。导入、编辑、保存、导出、再次导入必须保持 `ruleReview` 等价，未知字段的处理策略需有测试：若 Gson 默认忽略未知字段，旧客户端保存可能仍丢字段，因此不应承诺旧版 round-trip 兼容。

### 5.2 何时需要数据库迁移

只有以下任一改动发生时才需要提高 Room version 并提供 migration：

- 给 `BookSource`、`BookChapter` 或其他 entity 增加/删除列；
- 新建持久化评论缓存表；
- 改变现有列的 null/default/index 约束。

推荐第一版把评论缓存放在内存 repository，并允许短期文件缓存独立于 Room entity，避免为只读、短 TTL 数据引入 schema。若决定新建表，必须：

1. 更新 `AppDatabase.version` 和导出 schema；
2. 在 `DatabaseMigrations` 增加连续 migration；
3. 做 version 75 到新版本的 migration instrumentation test；
4. 验证升级前书源、书架、章节缓存不丢失；
5. 不使用 destructive migration 作为正常升级方案。

## 6. 书源编辑器改造

`BookSourceEditActivity` 需要恢复 `reviewEntities`、tab position 6、`upSourceView()` 填充和 `getSource()` 保存逻辑，但字段列表必须只包含第 3 节的只读字段。

建议 UI 分组：

- 合同：`contractVersion`。
- URL：`reviewIndexUrl`、`reviewUrl`、`reviewQuoteUrl`。
- 段落：3 个 paragraph rules。
- 主评：comment/user/avatar/content/time/count rules。
- 分页：`hasMoreRule`、`nextCursorRule`。
- 回复：quote list/id/parent/user/avatar/content/time/count/children rules。

编辑器保存前执行：

1. 合同版本必须是受支持值。
2. 三个 URL 都是只读 GET 模板且没有 body/method 覆写。
3. 模板变量只能来自允许集合。
4. 核心 JSONPath 非空。
5. 不存在任何写操作字段。

`cb_is_enable_review` 不应作为书源可执行写能力开关。建议改名为“启用只读段评”，只有规则通过 `supportsParagraphCommentsV1()` 时可选；不支持时隐藏或禁用并显示原因。

## 7. ID 获取与 URL 构造

当前 fanqie 书源的目录 `chapterUrl` 是：

```text
/api/book/chapter?book_id={$.book_id}&item_id={$.item_id}
```

目录响应已在每章提供 `book_id` 和 `item_id`。适配时应在目录解析阶段把两个 ID 写入 `BookChapter.variable`，例如键 `fanqieBookId`、`fanqieItemId`；这是比稍后解析 URL 更稳的方案。兼容已保存章节时，可从 `BookChapter.url` 的 query 参数安全提取一次并回填变量。

```kotlin
data class ReviewChapterIdentity(
    val bookId: String,
    val itemId: String,
    val itemVersion: String = "0",
)

/** 从章节变量或 URL 安全取得评论所需 ID。 */
fun BookChapter.reviewIdentityOrNull(): ReviewChapterIdentity? {
    val storedBookId = getVariable("fanqieBookId").orEmpty()
    val storedItemId = getVariable("fanqieItemId").orEmpty()
    val absoluteUrl = NetworkUtils.getAbsoluteURL(baseUrl, url)
    val uri = absoluteUrl.toHttpUrlOrNull()
    val bookId = storedBookId.ifBlank { uri?.queryParameter("book_id").orEmpty() }
    val itemId = storedItemId.ifBlank { uri?.queryParameter("item_id").orEmpty() }
    if (!bookId.all(Char::isDigit) || !itemId.all(Char::isDigit)) return null
    return ReviewChapterIdentity(bookId, itemId)
}
```

上例是伪代码，实际需要处理相对 URL：先用 `baseUrl`/书源 URL 解析绝对 URL，再用项目已有 URL 工具读取 query。禁止用正则截取整段 URL、从标题猜 ID，或从正文中搜索数字。

## 8. 网络与解析层

推荐新增单一 `ParagraphReviewRepository`，内部复用 Legado 的 `AnalyzeUrl`，从而继承书源 header、并发限速、代理、Cookie 设置和协程取消。不要另建绕开书源配置的裸 OkHttpClient。

```kotlin
interface ParagraphReviewRepository {
    /** 获取章节段评索引。 */
    suspend fun loadIndex(
        source: BookSource,
        chapter: ReviewChapterIdentity,
        force: Boolean = false,
    ): ReviewIndex

    /** 获取单段主评分页。 */
    suspend fun loadComments(
        source: BookSource,
        chapter: ReviewChapterIdentity,
        paraId: Int,
        cursor: String?,
        count: Int = 20,
    ): ReviewPage<ParagraphComment>

    /** 获取单条主评回复页。 */
    suspend fun loadReplies(
        source: BookSource,
        chapter: ReviewChapterIdentity,
        commentId: String,
        cursor: String?,
        count: Int = 20,
    ): ReviewPage<ParagraphReply>
}
```

请求伪代码：

```kotlin
/** 执行段评 GET，并校验公开合同后返回 data。 */
private suspend inline fun <reified T> requestReviewData(
    source: BookSource,
    ruleUrl: String,
    ruleData: RuleDataInterface,
    coroutineContext: CoroutineContext,
): T {
    coroutineContext.ensureActive()
    val safeUrl = reviewTemplateExpander.expandAndValidate(ruleUrl, ruleData)
    val response = AnalyzeUrl(
        mUrl = safeUrl,
        source = source,
        ruleData = ruleData,
        coroutineContext = coroutineContext,
    ).getStrResponseAwait(useWebView = false)
    coroutineContext.ensureActive()

    val envelope = gson.fromJson<ReviewEnvelopeWire<T>>(response.body)
    require(envelope.contract == "fanqie.paragraph-comments.v1")
    if (envelope.code != 0) throw ReviewApiException.from(envelope.error)
    return requireNotNull(envelope.data)
}
```

最终实现不要用 `require` 直接把协议错误变成 UI 崩溃；应映射为 `ReviewProtocolException` 并进入 `Error(type="upstream_protocol")`。响应解析必须检查：

- HTTP 状态；
- `contract` 精确匹配；
- `code==0` 与 data 存在；
- 错误 envelope 的 `type`、`retryable`、`parameter` 全部存在；无适用参数时 `parameter` 必须是空串；
- 必返 array/boolean/integer/string 存在且类型正确；
- 请求 identity 与响应 `book_id/item_id/para_id/comment_id` 一致；
- count/total 非负，cursor UTF-8 长度不超过 4096；
- `has_more=false` 时把 next cursor 规范化为空；
- `has_more=true` 但 next cursor 为空或重复时停止分页并报协议错误。

不要把服务器 message、响应 body 或最终 URL写入 release 日志。

## 9. 缓存设计

推荐 TTL：索引 60 秒，主评页和回复页 30 秒。

```kotlin
sealed interface ReviewCacheKey {
    data class Index(
        val sourceUrl: String,
        val bookId: String,
        val itemId: String,
        val itemVersion: String,
    ) : ReviewCacheKey

    data class Comments(
        val sourceUrl: String,
        val bookId: String,
        val itemId: String,
        val itemVersion: String,
        val paraId: Int,
        val cursor: String,
    ) : ReviewCacheKey

    data class Replies(
        val sourceUrl: String,
        val bookId: String,
        val itemId: String,
        val commentId: String,
        val cursor: String,
    ) : ReviewCacheKey
}
```

缓存规则：

- 成功值才进入缓存；错误、partial warning 和认证失败不做长期缓存。
- partial 索引可短暂用于显示计数，但刷新优先级高于完整成功值；必须保留 partial 标记。
- 同 key 使用 single-flight，避免旋转屏幕或重复重组触发相同请求。
- 切书源清除旧 `sourceUrl` 命名空间；章节 `itemVersion` 或正文 hash 变化清除旧章节索引和主评页。
- 主动刷新清空该资源的 cursor 链，不影响其他章节。
- 头像交给现有图片加载器短期缓存，repository 不缓存图片 bytes。
- cursor 可以存在内存 key 中，但不得写日志、统计或崩溃报告；如落盘必须按应用私有数据处理。

## 10. 章节 generation 与协程取消

`ReadBook` 已维护章节加载 jobs，并在切章、范围过期和 unregister 时取消。评论层应复用同一生命周期语义，但不要塞进正文下载流程使其互相阻塞。

推荐状态：

```kotlin
data class ReviewGeneration(
    val value: Long,
    val sourceUrl: String,
    val bookUrl: String,
    val chapterIndex: Int,
    val itemId: String,
)
```

每次切章、切源、重新分段、替换规则变化、正文 hash 变化或用户强制刷新时递增 generation。所有请求捕获启动 generation，提交 UI 前再次比较。

```kotlin
/** 为当前章节加载索引，旧 generation 的结果不会提交。 */
fun loadReviewIndex(chapter: BookChapter) {
    reviewIndexJob?.cancel()
    val generation = nextReviewGeneration(chapter)
    reviewIndexJob = viewModelScope.launch {
        _reviewState.value = ReviewLoadState.Loading
        val result = runCatching {
            repository.loadIndex(source, chapter.reviewIdentityOrNull() ?: error("unsupported"))
        }
        ensureActive()
        if (generation != currentReviewGeneration) return@launch
        _reviewState.value = result.fold(
            onSuccess = ::toReviewState,
            onFailure = ::toReviewErrorState,
        )
    }
}
```

取消要求：

- `ReadBook` 当前章变化：取消索引、主评和回复 job。
- 切源：取消并清除旧 source cache。
- Activity/ViewModel 销毁：依赖 `viewModelScope`/生命周期自动取消。
- 新 cursor 请求只能在前一页完成后启动；同列表不得并行翻页。
- 网络库必须收到 coroutine context；不能只在回调末尾丢弃结果却让请求继续占用 admission。
- 取消异常不显示 toast，不映射成网络错误。

## 11. 段落映射与排版

### 11.1 不可假设一一对应

`para_id` 是 FQNovel 服务端标识，而 `BookContent.textList` 是 Legado 经过去标题、替换、图片处理和空行处理后的本地段落列表。两者没有已证明的天然一一映射。

建议第一阶段实现 `ParagraphReviewMapping`：

```kotlin
data class ParagraphReviewMapping(
    val localParagraphIndex: Int,
    val paraId: Int,
    val count: Int,
    val evidence: MappingEvidence,
)

enum class MappingEvidence {
    SERVER_METADATA,
    VERIFIED_SOURCE_PARAGRAPH_INDEX,
}
```

只有 `SERVER_METADATA` 或经过 fixture/真实样本验证的 source-specific 映射可以显示气泡。越界、重复 para、标题/图片混排不确定、正文 hash 变化时不显示气泡。绝不采用“最接近文本”“标题后的第 N 行”之类无证据猜测。

### 11.2 `ReviewColumn`

把固定 `count=100` 替换为真实模型：

```kotlin
data class ReviewColumn(
    override var start: Float,
    override var end: Float,
    val paraId: Int,
    val count: Int,
    val generation: Long,
) : BaseColumn
```

- `count<=0` 不创建列。
- 展示可保留 `999+` 上限，但内部 count 不截断。
- 为 TalkBack 提供“本段 N 条评论”的 content description。
- 点击事件携带 `paraId`，不能用当前屏幕行号反查。
- generation 不匹配时忽略点击，避免重新排版期间打开错误段落。

### 11.3 `ChapterProvider` 与 `TextChapterLayout`

当前两个路径的状态不同：旧 `ChapterProvider.addCharToLine()` 已创建固定 ReviewColumn；当前 `TextChapterLayout.addCharToLine()` 的对应分支仍注释。适配时二者必须使用同一个 `ParagraphReviewLayoutData`，不要维护两套 count 查找逻辑。

推荐流程：

1. 正文无索引时正常排版，不插 `reviewChar`，避免生成 count=0 的占位列。
2. 索引和 para 映射到达后，构造 `Map<localParagraphIndex, ParagraphReviewSummary>`。
3. 取消当前章节旧 layout job，使用相同正文和新映射重新排版。
4. 在每个本地段落结束处，只有命中映射且 count>0 时插入 `reviewChar`。
5. `addCharToLine()` 从当前段上下文取得 `paraId/count/generation` 并创建 ReviewColumn。
6. 通过现有 `onLayoutPageCompleted`/`upContent` 逐页刷新，尽量保持阅读位置；使用正文绝对位置恢复，而不是页码。
7. 替换规则、字体、行距或横竖屏变化触发重排时复用已验证映射；正文 hash 改变则先失效映射。

标题行是否显示气泡必须单独验证。当前代码给标题也附加 `reviewChar`，但没有证据证明标题对应 `para_id`，因此 v1 默认不要给标题插评论列。

## 12. 点击与 UI

### 12.1 点击事件

`ContentTextView.click()` 当前对 ReviewColumn 只弹固定 toast。改为向阅读 Activity/ViewModel 发出强类型事件：

```kotlin
sealed interface ReadPageAction {
    data class OpenParagraphReviews(
        val paraId: Int,
        val generation: Long,
    ) : ReadPageAction
}
```

Activity 收到事件后核对 generation 和当前章节 identity，再打开 bottom sheet 或独立页面。不要在自定义 View 内发网络请求。

### 12.2 主评列表

主评 UI 至少显示：头像占位、用户名、正文、Unix 秒转换后的时间、点赞展示数和回复数。行为：

- 打开即请求主评第一页；已有未过期缓存时先显示缓存并后台刷新。
- 列表到底且 `has_more=true` 时串行请求 next cursor。
- 以 `comment_id` 去重，保持服务端页顺序。
- 点击“X 条回复”再懒加载，不预取整章回复。
- `replies_loaded` 只表示章节 eager 请求；普通列表不把 false 当错误。
- v1 没有写操作按钮。

### 12.3 回复树

回复页面/展开区按 `reply_id` 去重，以 `reply_to_reply_id` 重新挂树。父回复尚未跨页出现时先作为 orphan 根展示，可用“回复某用户”文本提示关系；后续父节点出现后平滑迁移，避免重复。

合并伪代码：

```kotlin
/** 合并回复页并按 replyToReplyId 重建无环树。 */
fun mergeReplyPages(pages: List<ReviewPage<ParagraphReply>>): List<ParagraphReplyNode> {
    val nodes = linkedMapOf<String, ParagraphReplyNode>()
    pages.flatMap { flatten(it.items) }.forEach { reply ->
        nodes[reply.id] = nodes[reply.id]?.merge(reply) ?: ParagraphReplyNode(reply)
    }

    val roots = mutableListOf<ParagraphReplyNode>()
    nodes.values.forEach { node ->
        val parent = node.replyToReplyId?.let(nodes::get)
        if (parent != null && parent.id != node.id && !wouldCreateCycle(parent, node)) {
            parent.attachOnce(node)
        } else {
            roots += node
        }
    }
    return roots
}
```

### 12.4 UI 状态矩阵

| 状态 | 章节气泡 | 主评/回复面板 | 重试 |
| --- | --- | --- | --- |
| `unsupported` | 不显示 | 显示“当前书源不支持段评”或不提供入口 | 否 |
| `loading` | 正文照常，可暂不显示 | 骨架或小型进度，不遮挡阅读 | 切章自动取消 |
| `empty` | 不显示气泡 | 显示“暂无评论” | 允许下拉刷新 |
| `content` | 显示 count>0 气泡 | 正常列表 | 允许刷新/分页 |
| `partial` | 显示已成功计数 | 标记“部分内容加载失败”，保留成功数据 | 只重试 warning scope |
| `error` | 不清空仍有效缓存 | 显示脱敏分类，不展示服务器正文 | `retryable=true` 才提供自动/手动重试 |
| `offline` | 可显示带缓存标记的未过期/最近数据 | 明确“离线缓存” | 网络恢复后刷新 |

认证 `401` 应提示书源公共 API key 配置错误，不自动无限重试。`429` 尊重 `Retry-After`；`502 upstream_protocol` 停止自动重试并提示服务端版本可能不匹配。

## 13. 阅读设置与能力判断

当前 `AppConfig.enableReview` 仅在 `BuildConfig.DEBUG` 时可能为 true，阅读菜单项也隐藏。建议拆成两个条件：

```text
sourceSupportsReview = bookSource.ruleReview.supportsParagraphCommentsV1()
userEnabledReview = 持久化用户开关
effectiveReviewEnabled = 在线书籍 && sourceSupportsReview && userEnabledReview
```

- release 构建允许 effective=true。
- 本地 TXT/EPUB/PDF 默认 unsupported。
- 切到不支持的书源时保留用户全局偏好，但当前 effective=false。
- 菜单只在 `sourceSupportsReview` 时显示，标题明确“只读段评”。
- 关闭开关应取消评论 job、移除 ReviewColumn 并重新排版，但不删除正文缓存。

## 14. 头像加载

- 空 URL、非 HTTPS、解析失败：直接使用本地占位头像。
- HTTPS URL：复用 Legado 当前图片加载器；限制尺寸和磁盘 TTL。
- 403/404/过期：该 URL 标记本次失败，刷新评论页获取新 URL；不要无限重试同一 URL。
- 不把头像 URL当作 user key，不写入永久用户档案。
- 若应用 Network Security Config 禁止明文流量，不为头像域名放宽全局策略。

## 15. 安全与隐私检查

- 公共密钥只从书源 header 注入，UI 和日志显示为掩码；不将其复制到 query。
- 只允许三个 v1 GET 模板；拒绝所有写方法、body 和跨 origin 重定向。
- cursor、正文、头像完整 URL、用户 ID 不进入 release 日志和 analytics。
- 异常只记录 `error.type`、HTTP 状态、布尔和耗时；不记录 response body。
- 所有 ID 保持字符串；不执行 JavaScript 数值转换。
- WebView 不参与评论请求，防止脚本读取 header 或正文。
- 评论内容按不可信文本渲染，不解析 HTML/Markdown/JS，不允许可点击 scheme 绕过现有 URL 安全策略。
- 服务端返回的 URL 只用于头像，且经过 scheme/host/尺寸策略；评论正文中的 URL 保持纯文本。

## 16. 推荐实施顺序

1. 增加 v1 `ReviewRule` 字段、能力校验和 converter round-trip 测试。
2. 恢复书源编辑器的只读段评 tab；完成导入/保存/导出兼容测试。
3. 增加 wire/domain DTO、严格 parser、URL template expander 和合成合同测试。
4. 增加 repository、single-flight、TTL cache、错误分类和 cancellation 测试。
5. 在目录解析时保存 `bookId/itemId`，实现兼容 URL 提取。
6. 实现章节 generation、异步索引和状态流；此阶段正文仍不显示气泡。
7. 建立可验证 para 映射；无证据样本继续隐藏。
8. 改 `ReviewColumn`、两个 layout 路径和点击事件，删除固定 `count=100`/toast。
9. 实现主评分页、回复懒加载、分页去重和跨页树。
10. 开放 release 菜单和设置，完成可访问性、离线和旋转/切章测试。
11. 在非正式服务端配对版本上做真实 smoke；正文、头像、ID 和 cursor 不进入测试报告。

## 17. 测试清单

### 17.1 规则与持久化

- [ ] 导入含完整 v1 `ruleReview` 的书源，能力判断为 true。
- [ ] `BookSource` 写入 Room 后读取，所有 v1 字段保持一致。
- [ ] 编辑并保存其他书源字段，不把 `ruleReview` 写成 `null`。
- [ ] 导出再导入，JSON 等价且没有写操作 URL。
- [ ] 原版字段/未知字段策略有明确测试。
- [ ] 畸形 ruleReview 降级 unsupported，不崩溃。

### 17.2 URL 与认证

- [ ] 7 个模板变量逐一 URL 编码。
- [ ] 空 cursor 删除参数；包含 `&`、`+`、`%`、非 ASCII 的 cursor 不破坏 query。
- [ ] 非数字 ID、count 越界、cursor 超长在本地拒绝。
- [ ] Authorization header 使用公共 key；不写 query、不泄漏日志。
- [ ] POST/body/跨 origin/非 HTTPS 模板被拒绝。

### 17.3 Parser 合同

- [ ] index/page/replies 的成功、空数组和分页 fixture。
- [ ] `contract` 错误、code 非零、data 缺失、必返字段缺失、类型错误。
- [ ] 可选 user/avatar/children 缺失正常解析。
- [ ] `create_timestamp=0`、大 ID string、`para_id` 边界。
- [ ] `partial/warnings` 三态语义正确。
- [ ] `has_more=true` 且空/重复 cursor 停止循环。

### 17.4 缓存、分页与树

- [ ] 索引 60 秒、主评/回复 30 秒 TTL。
- [ ] 同 key single-flight；不同 para/comment 不互相阻塞。
- [ ] 主评按 `comment_id` 跨页去重并保持顺序。
- [ ] 回复父子同页、父在后页、父永不出现、自环、双节点环。
- [ ] 刷新第一页清 cursor 链但保留可复用展开状态。
- [ ] 切源/版本/hash 变化正确失效。

### 17.5 协程与生命周期

- [ ] 快速连续切三章，只有最后 generation 能提交 UI。
- [ ] 请求中切源、关闭 Activity、关闭段评开关，网络请求实际取消。
- [ ] 取消不显示错误 toast，不写错误缓存。
- [ ] 旋转屏幕不重复请求同一 in-flight key。
- [ ] 旧 cursor 晚到结果不能覆盖刷新后的列表。

### 17.6 排版与交互

- [ ] 没有可靠 para 映射时不显示气泡。
- [ ] count 0、1、999、1000 的绘制和 accessibility 文本。
- [ ] 标题、普通段、图片段、空段、替换规则、繁简转换、横竖屏和字体变化。
- [ ] 点击 ReviewColumn 传递准确 paraId/generation，不再弹占位 toast。
- [ ] 重排保持阅读绝对位置，前后页加载不闪跳。
- [ ] loading/empty/content/partial/error/offline/unsupported 全状态截图测试。

### 17.7 安全与回归

- [ ] 搜索、详情、目录、正文原有流程不回归。
- [ ] 本地书、其他书源和不支持 v1 的书源不受影响。
- [ ] 没有点赞、发评、回复、删除、举报网络请求或 UI。
- [ ] release 日志不含 key、cursor、正文、头像 URL、用户/评论 ID。
- [ ] 401/429/502/503/504 映射和退避符合公开合同。

## 18. 完成定义

只有同时满足以下条件，修改版 Legado 才能宣称支持本书源段评：

- `ruleReview` 可以导入、持久化、编辑和导出；
- 三个只读端点通过严格 v1 parser 消费；
- para 映射有真实证据，无法映射时安全隐藏；
- 气泡、主评分页、回复分页和树合并可用；
- 切章/切源/销毁取消与 UI 状态完整；
- 原有书源阅读链无回归；
- 未引入任何写操作；
- 使用与服务端匹配的候选版本完成真实 smoke，并明确记录环境和隐私边界。
