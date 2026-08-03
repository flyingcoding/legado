# Legado 段评图片适配待修改清单

本文记录 Legado 后续接入番茄段评正文图片所需的代码改动，供后续单独修改 Android 代码时使用。

当前状态：

- 审计基线：`master@adce026d6`。
- Legado 已具备段评索引、主评分页、回复分页、回复树、内存缓存和只读列表 UI。
- proxy 已部署支持图片的 `fanqie.paragraph-comments.v1` 服务，主评和回复均可返回 `images`。
- `docs/fanqie-integration/fanqie-legado-source.json` 已包含 10 个图片选择器。
- 本文仅是代码实施清单；创建本文时没有修改 Kotlin、XML、资源文件或 Gradle 配置。

本文不记录真实服务地址、Authorization、书籍 ID、章节 ID、评论正文、cursor 或图片 URL。测试数据必须使用合成值和 `example.invalid`。

## 1. 目标与边界

### 1.1 目标

完成后，Legado 应能：

1. 从 `ruleReview` 导入、编辑并保存主评和回复图片规则。
2. 严格解析主评、首屏内嵌回复、回复分页和嵌套回复中的 `images`。
3. 保留图片顺序以及服务端返回的宽、高和可选格式。
4. 同时展示文字和图片，也能正确展示 `text == ""` 的纯图片段评。
5. 在主评列表、回复页头部、根回复和嵌套回复中显示缩略图。
6. 点击缩略图后复用 Legado 现有 `PhotoDialog` 预览原图。
7. 保持 cursor 分页、回复树合并、刷新、缓存和列表回收行为正确。
8. 图片加载失败时只显示占位/错误图，不影响段评文字、分页和阅读正文。

### 1.2 非目标

- 不在 Go proxy 或 Legado 内转存、压缩或代理图片二进制。
- 不新增发评、发回复、点赞、删除、举报等写操作。
- 不新增图片上传能力。
- 不把真实图片 URL、Authorization 或上游响应正文写入日志。
- 不修改 `fanqie.paragraph-comments.v1` 合同标识；`images` 是同一只读合同上的增量字段。
- 不为本改动增加 Room migration。`BookSource.ruleReview` 仍以 JSON 文本保存，数据库列结构不变。
- 不引入新的图片加载库；复用 Glide、`ImageLoader` 和 `OkHttpModelLoader`。

## 2. 当前缺口

| 层 | 当前状态 | 待修改结果 |
| --- | --- | --- |
| 书源规则 | JSON 已有 10 个图片选择器；`ReviewRule` 尚无对应字段 | 字段可导入、编辑、保存并 round-trip |
| wire DTO | 主评和回复 DTO 没有 `images` | 增加 nullable wire 图片对象和列表 |
| domain DTO | `ParagraphComment`、`ParagraphReply` 没有图片 | 增加非空不可变图片列表 |
| 协议解析 | `ReviewV1Parser` 只校验文字、用户、时间和回复 | 校验图片结构、数量、URL、尺寸和格式 |
| 聚合/缓存 | 当前对象可分页合并和缓存，但测试未覆盖图片 | 保留图片，不改变 key、cursor 或树关系 |
| UI 映射 | presentation 只有文字、头像、时间和计数 | 增加安全图片 presentation 和宽高比 |
| UI 布局 | 主评、回复行均无图片容器 | 增加横向缩略图列表和预览回调 |
| 测试 | 已覆盖基础段评合同 | 补齐规则、解析、缓存、回复树和 UI 图片测试 |

## 3. 服务端字段与书源规则

### 3.1 响应对象

主评、根回复和任意嵌套回复使用相同的图片结构：

```json
{
  "text": "",
  "images": [
    {
      "url": "https://example.invalid/review/image-1.webp",
      "width": 1080,
      "height": 1440,
      "format": "webp"
    }
  ]
}
```

字段约定：

| 字段 | 类型 | 要求 |
| --- | --- | --- |
| `images` | array | 启用图片能力时必填；无图片必须返回 `[]` |
| `url` | string | 每个图片对象必填；绝对 HTTP(S) URL；UTF-8 不超过 4096 字节 |
| `width` | integer | 必填；非负；客户端使用 `Long` 接收 |
| `height` | integer | 必填；非负；客户端使用 `Long` 接收 |
| `format` | string | 可选；trim 后保存；UTF-8 不超过 64 字节 |

每条主评或回复最多接受 50 张图片。列表顺序就是展示顺序，客户端不得按 URL、尺寸或格式重新排序。

### 3.2 `ruleReview` 的 10 个字段

字段名必须与现有书源 JSON 完全一致，不要另建 `reviewCommentImages` 等别名。

| 规则字段 | 当前值 | 用途 |
| --- | --- | --- |
| `imageListRule` | `$.images` | 主评图片数组 |
| `imageUrlRule` | `$.url` | 主评图片 URL |
| `imageWidthRule` | `$.width` | 主评图片宽度 |
| `imageHeightRule` | `$.height` | 主评图片高度 |
| `imageFormatRule` | `$.format` | 主评图片格式 |
| `quoteImageListRule` | `$.images` | 回复图片数组 |
| `quoteImageUrlRule` | `$.url` | 回复图片 URL |
| `quoteImageWidthRule` | `$.width` | 回复图片宽度 |
| `quoteImageHeightRule` | `$.height` | 回复图片高度 |
| `quoteImageFormatRule` | `$.format` | 回复图片格式 |

## 4. 兼容策略

不要把 10 个图片规则直接加入现有 `paragraphCommentsV1RequiredFields`。否则旧的纯文本段评书源只因缺少图片规则就会失去整个段评能力。

建议保留两级能力：

- `supportsParagraphCommentsV1()`：继续表示基础段评能力，现有必填字段不变。
- `supportsParagraphCommentImagesV1()`：基础段评能力成立，并且 10 个图片规则全部非空时为 `true`。

解析策略由第二个能力标志控制：

| 场景 | `images` 行为 |
| --- | --- |
| 当前番茄书源，10 个规则齐全 | 严格模式：主评和回复必须存在数组；`null`、缺失或类型错误均为协议错误 |
| 历史纯文本书源，没有图片规则 | 兼容模式：缺失/`null` 归一为 `emptyList()`；若实际返回图片，仍执行完整安全校验 |

这样既能验证当前 proxy 的新合同，也不会破坏其他只支持文字的来源。

可新增一个轻量解析选项，例如：

```kotlin
/** 控制段评解析器是否要求服务端显式返回图片数组。 */
data class ReviewParserCapabilities(
    val requireImages: Boolean,
)
```

`DefaultParagraphReviewRepository` 已能取得当前书源规则，调用 `ReviewV1Parser` 时传入：

```kotlin
ReviewParserCapabilities(
    requireImages = rule.supportsParagraphCommentImagesV1(),
)
```

若后续决定只支持当前已升级的 proxy，也可以始终严格要求 `images`，但必须先确认没有历史 v1 服务仍省略该字段。默认采用上面的双能力方案。

## 5. 分层修改清单

### 5.1 书源规则模型与编辑页

#### `app/src/main/java/io/legado/app/data/entities/rule/ReviewRule.kt`

1. 在 `ReviewRule` 增加第 3.2 节列出的 10 个 nullable string 字段。
2. 新增 `paragraphCommentImagesV1RequiredFields`，只包含这 10 个字段。
3. 新增 `supportsParagraphCommentImagesV1()`，同时要求：
   - `supportsParagraphCommentsV1()` 为 `true`；
   - 10 个图片规则均非空。
4. 不改变 `PARAGRAPH_COMMENTS_V1_CONTRACT`。
5. 不把派生属性写入 JSON；现有 Gson 行为和测试应继续保证这一点。

建议结构：

```kotlin
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
```

#### `app/src/main/java/io/legado/app/ui/book/source/edit/ReviewRuleEditor.kt`

- `toReviewRuleOrNull()` 增加 10 个字段的读取和构造映射。
- 保存已有书源时必须原样保留图片字段。
- 空的图片规则不应让基础文字段评规则整体保存失败。

#### `app/src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt`

- 在 `reviewEntities` 中为 10 个字段增加 `EditEntity`。
- 主评图片规则紧跟主评内容规则，回复图片规则紧跟回复内容规则，避免编辑页字段混杂。
- 不在字段标题或提示中写入任何真实 URL、token 或业务 ID。

#### 字符串资源

同步增加中英文名称：

- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh/strings.xml`

名称要明确区分“主评图片”和“回复图片”，不要只写“图片 URL”。

#### 持久化结论

- `BookSource.ruleReview` 使用 JSON 文本保存，新字段由 Gson 自然兼容。
- 不修改 Room entity 列，不生成 schema，不增加 migration。
- 旧 JSON 缺少新字段时反序列化为 `null`，基础段评仍可用。

### 5.2 wire 与 domain 模型

#### `app/src/main/java/io/legado/app/model/review/wire/ReviewWireModels.kt`

新增：

```kotlin
/** 保存公共段评 API 返回的 nullable 图片字段。 */
data class ParagraphCommentImageWire(
    val url: String? = null,
    val width: Long? = null,
    val height: Long? = null,
    val format: String? = null,
)
```

并在两处增加：

```kotlin
val images: List<ParagraphCommentImageWire>? = null
```

目标对象：

- `ParagraphCommentWire`
- `ParagraphReplyWire`

`ParagraphReplyWire.children` 会递归覆盖任意嵌套回复，因此不能只给回复分页顶层补字段。

#### `app/src/main/java/io/legado/app/model/review/ReviewModels.kt`

新增严格 domain 对象：

```kotlin
/** 保存校验后的段评图片及服务端原始尺寸。 */
data class ParagraphCommentImage(
    val url: String,
    val width: Long,
    val height: Long,
    val format: String?,
)
```

在 `ParagraphComment` 和 `ParagraphReply` 中增加：

```kotlin
val images: List<ParagraphCommentImage>
```

domain 层始终使用非空列表：无图片是 `emptyList()`，不向 UI 暴露 `null`。

### 5.3 严格解析与映射

#### `app/src/main/java/io/legado/app/model/review/wire/ReviewV1Parser.kt`

需要同时覆盖：

- 章节索引中 `ReviewParagraphWire.comments` 的首屏主评；
- 主评分页的主评；
- 主评内嵌 `replies`；
- 回复分页的根回复；
- 任意层级 `children`。

建议新增常量：

```kotlin
private const val MAX_IMAGES_PER_ITEM = 50
private const val MAX_IMAGE_URL_BYTES = 4096
private const val MAX_IMAGE_FORMAT_BYTES = 64
```

建议提取单一函数完成结构校验和 domain 映射，避免主评与回复出现不同规则：

```kotlin
/** 校验图片数组并转换为不可变 domain 列表。 */
private fun parseImages(
    images: List<ParagraphCommentImageWire>?,
    requireImages: Boolean,
    fieldPath: String,
): List<ParagraphCommentImage>
```

校验规则：

1. 严格模式下，字段缺失或 `null` 是协议错误；兼容模式下归一为空列表。
2. 数组长度不得超过 50。
3. 每个元素必须是对象；不接受 `null` 元素。
4. `url` 必须非空、UTF-8 不超过 4096 字节，并能解析为绝对 HTTP(S) URL。
5. `width`、`height` 必须存在且非负，使用 `Long`，不做 `Int` 截断。
6. `format` 可缺失；存在时 trim，UTF-8 不超过 64 字节；trim 后为空可归一为 `null`。
7. 保留原数组顺序。
8. 不因 `text == ""` 丢弃评论或回复；只要其余合同字段合法，纯图片内容有效。
9. 协议异常只抛现有脱敏协议错误，不在异常消息中附带图片 URL 或完整响应正文。

结构校验应发生在 `toDomain` 之前。Gson 把“字段不存在”和显式 `null` 都映射为 `null`，若需要在严格模式区分数组类型错误，继续沿用解析器当前对原始 JSON shape 的显式检查，不要仅依赖 DTO 类型。

### 5.4 Repository、分页、缓存和回复树

#### `app/src/main/java/io/legado/app/model/review/ParagraphReviewRepository.kt`

- 从当前 `ReviewRule` 计算 `requireImages` 并传给所有 parser 入口。
- 索引、主评和回复请求使用同一策略，不能只改主评分页。
- 不改变请求 URL 模板、Authorization、cursor 编码、retry 或错误分类。
- 不缓存图片字节；repository 只缓存图片元数据。

#### 预期无需生产代码改动

以下对象保存的是完整 domain item，新 `images` 字段应随对象自然传递：

- `ReviewMemoryCache`
- `CursorPageAccumulator`
- `ParagraphReplyTreeBuilder`

仍需补回归测试，确认：

- cursor 下一页追加时图片不丢失；
- 重复 `commentId` 的新对象替换旧对象时，首个插入顺序不变，图片 URL/尺寸可以刷新；
- `ParagraphReply.copy(children = ...)` 构建回复树时保留当前节点图片；
- 跨页补齐父子关系时，根回复和嵌套回复图片都保留；
- 内存缓存命中、刷新和清理不会共享可变图片列表。

### 5.5 UI presentation

#### `app/src/main/java/io/legado/app/ui/book/read/review/ParagraphReviewUiModels.kt`

建议新增：

```kotlin
/** 保存经过 UI 安全过滤的段评图片。 */
internal data class ParagraphReviewImagePresentation(
    val url: String,
    val aspectRatio: Float,
)
```

并给主评、回复 presentation 增加 `images`。

URL 和比例策略：

- `safeParagraphReviewImageUrl()` 只允许 HTTPS，行为与 `safeParagraphReviewAvatar()` 对齐。
- 拒绝 HTTP、相对路径、`data:`、`file:`、`content:` 和无法解析的字符串。
- 无效 URL 从 UI 图片列表中过滤，但不删除整条评论。
- 宽高都大于 0 时使用 `width / height`；零尺寸或异常值使用默认比例。
- 最终比例限制在安全范围，例如 `0.5f..2.0f`，避免极端尺寸撑坏列表。
- presentation 保留服务端顺序。
- `content` 允许为空；可见性由“文字是否为空”和“图片是否为空”分别控制。

备注：wire/parser 层可以接受 HTTP(S) 以忠实校验服务端合同，实际 Glide 展示层只放行 HTTPS。生产书源本身也应使用 HTTPS。

### 5.6 UI 布局、Adapter 和预览

#### 新增建议文件

- `app/src/main/java/io/legado/app/ui/book/read/review/ParagraphReviewImageAdapter.kt`
- `app/src/main/res/layout/item_paragraph_review_image.xml`

图片 adapter 职责：

- 只接收安全 presentation，不自行解析协议 DTO。
- 使用固定边界的缩略图宽高，例如 96–112dp，并在边界内应用安全宽高比。
- 绑定时复用 `ImageLoader` 和 `OkHttpModelLoader.sourceOriginOption`，保证与书源图片请求头/来源处理一致。
- 设置本地 placeholder 和 error drawable。
- 每次重绑先清理旧请求/旧 drawable，避免 RecyclerView 回收后串图。
- 点击时只回调 URL，不直接持有 `FragmentManager`。

#### 修改布局

- `app/src/main/res/layout/item_paragraph_review_comment.xml`
- `app/src/main/res/layout/item_paragraph_review_reply.xml`

在正文和统计信息之间增加横向 `RecyclerView`，建议 ID 为 `rvImages`：

- `layout_height="wrap_content"`；
- 横向 `LinearLayoutManager`；
- 不再嵌套一个可纵向滚动的列表；
- 空图片列表时设为 `GONE`；
- 图片存在时，即使正文为空也保持整个 row 可见；
- 正文为空时 `tvContent` 为 `GONE`，避免多余空白。

#### 修改现有 Adapter/Binding

- `ParagraphReviewCommentAdapter.kt`
- `ParagraphReviewReplyAdapter.kt`
- `ParagraphReviewBindings.kt`

主评列表、回复页头部和回复行都必须绑定图片。尤其注意：

- `ParagraphReviewReplyAdapter` 的主评 header 也要显示主评图片；
- `flattenParagraphReviewReplies()` 后的所有嵌套回复使用同一图片绑定逻辑；
- `setItems`、header 更新和回收时清空旧图片列表；
- 不为每次 bind 重复创建无界线程池或网络客户端；
- Adapter 的图片点击统一上抛给 Dialog。

#### 修改 `ParagraphReviewDialog.kt`

给两个 Adapter 传入 `onImageClick`：

```kotlin
onImageClick = { url ->
    showDialogFragment(PhotoDialog(url, sourceUrl))
}
```

实际实现时复用项目现有的 `PhotoDialog` 和 `showDialogFragment` 扩展，并把当前书源 `sourceUrl` 作为来源参数传入。不要在图片 adapter 内部自行创建 dialog。

### 5.7 生命周期、性能与安全

- Glide 请求应绑定 item view/fragment 生命周期；Dialog 销毁后不能继续回写旧 view。
- 主列表滚动不预加载全尺寸原图，只加载受控缩略图尺寸。
- 每个 item 最多 50 张是协议上限；首屏可按 UI 性能决定只同时请求可见缩略图，但不能改变数据列表或点击顺序。
- 图片加载失败、超时或 404 不进入段评分页错误状态。
- 不在 `toString()`、异常、analytics 或 debug log 中输出 URL 查询参数。
- 不将 Authorization 追加到图片 URL；只使用现有书源 header/origin 机制。
- 预览 Dialog 关闭、段评 Dialog 关闭、切章和切换主评/回复模式后，不应保留对旧 Fragment/View 的强引用。

## 6. 文件级清单

### 6.1 必改生产文件

- [ ] `app/src/main/java/io/legado/app/data/entities/rule/ReviewRule.kt`
- [ ] `app/src/main/java/io/legado/app/ui/book/source/edit/ReviewRuleEditor.kt`
- [ ] `app/src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt`
- [ ] `app/src/main/res/values/strings.xml`
- [ ] `app/src/main/res/values-zh/strings.xml`
- [ ] `app/src/main/java/io/legado/app/model/review/wire/ReviewWireModels.kt`
- [ ] `app/src/main/java/io/legado/app/model/review/wire/ReviewV1Parser.kt`
- [ ] `app/src/main/java/io/legado/app/model/review/ReviewModels.kt`
- [ ] `app/src/main/java/io/legado/app/model/review/ParagraphReviewRepository.kt`
- [ ] `app/src/main/java/io/legado/app/ui/book/read/review/ParagraphReviewUiModels.kt`
- [ ] `app/src/main/java/io/legado/app/ui/book/read/review/ParagraphReviewBindings.kt`
- [ ] `app/src/main/java/io/legado/app/ui/book/read/review/ParagraphReviewCommentAdapter.kt`
- [ ] `app/src/main/java/io/legado/app/ui/book/read/review/ParagraphReviewReplyAdapter.kt`
- [ ] `app/src/main/java/io/legado/app/ui/book/read/review/ParagraphReviewDialog.kt`
- [ ] `app/src/main/res/layout/item_paragraph_review_comment.xml`
- [ ] `app/src/main/res/layout/item_paragraph_review_reply.xml`

### 6.2 建议新增生产文件

- [ ] `app/src/main/java/io/legado/app/ui/book/read/review/ParagraphReviewImageAdapter.kt`
- [ ] `app/src/main/res/layout/item_paragraph_review_image.xml`

若最终把 URL/比例策略拆成独立文件，应放在同一 review UI package，避免把只属于段评的规则塞进通用图片组件。

### 6.3 预期不改的生产文件

- `ReviewMemoryCache.kt`
- `CursorPageAccumulator.kt`
- `ParagraphReplyTreeBuilder.kt`
- `ParagraphReviewViewModel.kt`，除非最终 UI 状态不再直接持有 domain 对象
- Room entity、schema 和 migration
- Gradle dependency

## 7. 测试清单

### 7.1 规则与保存

#### `ReviewRuleTest.kt`

- [ ] JSON 导入 10 个字段并逐项断言。
- [ ] Gson round-trip 后 10 个字段不变。
- [ ] `supportsParagraphCommentImagesV1()` 在字段齐全时为 `true`。
- [ ] 任一图片规则缺失、空白时图片能力为 `false`。
- [ ] 图片规则缺失时 `supportsParagraphCommentsV1()` 仍为 `true`。
- [ ] 派生 map/capability 不进入 JSON。

#### `ReviewRuleEditorTest.kt`

- [ ] 编辑器读取、修改并保存 10 个字段。
- [ ] 保存基础纯文本规则时不强制图片字段。
- [ ] 编辑其他字段时不意外清空已有图片规则。

### 7.2 Parser

#### `ReviewV1ParserTest.kt`

成功用例：

- [ ] 主评图片完整映射，顺序不变。
- [ ] 章节索引内嵌主评图片完整映射。
- [ ] 主评内嵌根回复和 children 图片完整映射。
- [ ] 回复分页根回复和多级 children 图片完整映射。
- [ ] `images: []` 映射为非空类型的 `emptyList()`。
- [ ] `text: ""` 且图片非空时保留整条主评/回复。
- [ ] `format` 缺失或空白时归一为 `null`。
- [ ] `Long` 尺寸不发生 `Int` 截断。
- [ ] 兼容模式下缺失图片字段映射为空列表。

失败用例：

- [ ] 严格模式下 `images` 缺失、`null`、非数组时协议失败。
- [ ] 图片元素为 `null` 或非对象时协议失败。
- [ ] URL 为空、相对路径、非法 scheme、无法解析或超过 4096 字节时失败。
- [ ] `width`/`height` 缺失、非整数或负数时失败。
- [ ] 图片超过 50 张时失败。
- [ ] `format` 超过 64 UTF-8 字节时失败。
- [ ] 错误对象和异常文本不包含原始 URL。
- [ ] 新增字段不改变 ID string、cursor、total、`has_more` 等已有严格规则。

### 7.3 聚合、缓存与回复树

- [ ] `CursorPageAccumulator` 追加下一页后保留所有图片。
- [ ] 重复主评刷新图片元数据时保持首次出现顺序。
- [ ] `ReviewMemoryCache` 写入/命中/刷新后图片列表相等。
- [ ] `ParagraphReplyTreeBuilder` 构建同页和跨页树时保留每个节点的图片。
- [ ] `copy(children = ...)` 不清空图片。

可在现有 `ReviewAggregationTest.kt`、`ReviewMemoryCacheTest.kt` 中补用例，不必为了图片复制一套完整测试基架。

### 7.4 UI 单元与界面测试

#### `ParagraphReviewUiModelsTest.kt`

- [ ] HTTPS 图片 URL 被保留。
- [ ] HTTP、相对地址、`data:`、`file:` 和非法 URL 被过滤。
- [ ] 宽高比正确计算并限制在安全区间。
- [ ] 零尺寸使用默认比例。
- [ ] 纯图片 presentation 的正文可为空，图片仍存在。
- [ ] 空图片时 UI 模型为空列表。

Adapter/界面回归：

- [ ] 主评行显示图片。
- [ ] 回复页主评 header 显示图片。
- [ ] 根回复和嵌套回复显示图片。
- [ ] 回收并重绑无图片 item 后不残留上一条图片。
- [ ] 图片加载失败显示错误占位但文字和统计正常。
- [ ] 点击图片只打开一个 `PhotoDialog`，返回后列表位置不变。
- [ ] 主评/回复模式切换、刷新、加载更多和旋转后图片状态正确。
- [ ] 深色/浅色主题、字体缩放和窄屏下不溢出。

## 8. 推荐实施顺序

1. **规则层**：补 `ReviewRule`、编辑器、字符串资源和 round-trip 测试。
2. **合同层**：补 wire/domain、解析策略、严格校验和 parser 测试。
3. **传输层**：repository 传入图片能力；补聚合、缓存和回复树回归。
4. **展示层**：补 presentation、安全 URL/比例、图片 adapter 和布局。
5. **交互层**：Dialog 复用 `PhotoDialog`，验证 source origin/header。
6. **收口**：单测、lint、debug 构建和真实 proxy smoke。

不要先做 UI 再补 parser。先形成严格、不可变的 domain 图片列表，能显著降低 Adapter 中的判空和协议分支。

## 9. 本地验证命令

项目存在 `app` product flavor，优先使用 `AppDebug` 变体：

```bash
./gradlew :app:testAppDebugUnitTest
./gradlew :app:lintAppDebug
./gradlew :app:assembleAppDebug
```

需要只跑相关单测时，可使用：

```bash
./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.data.entities.rule.ReviewRuleTest' \
  --tests 'io.legado.app.ui.book.source.edit.ReviewRuleEditorTest' \
  --tests 'io.legado.app.model.review.wire.ReviewV1ParserTest' \
  --tests 'io.legado.app.ui.book.read.review.ParagraphReviewUiModelsTest'
```

若当前 Gradle/AGP 版本生成的 task 名不同，先执行：

```bash
./gradlew :app:tasks --all | grep -E 'test.*AppDebug|lintAppDebug|assembleAppDebug'
```

## 10. 真实 proxy smoke

smoke 使用“诡舍”，但记录中只保留脱敏统计，不保存正文和 URL：

```text
书籍：诡舍
合同：fanqie.paragraph-comments.v1
主评页请求：成功/失败
主评 images 数组：存在/缺失
主评图片条数：N
回复页请求：成功/失败
回复 images 数组：存在/缺失
回复图片条数：N
纯图片主评或回复：存在/未命中
缩略图加载：成功/失败
原图预览：成功/失败
分页和返回位置：正常/异常
日志包含 token、URL 或正文：否/是
```

测试要求：

- 通过当前书源和 proxy 请求，不直连上游私有接口。
- 不把 Authorization、完整请求、cursor、评论正文或图片 URL 粘贴到 issue、测试代码或本文。
- 至少验证一条主评图片和一条回复图片；若样本没有图片，应继续换段落，不得把“数组为空”误判为功能成功。
- 主评/回复无图样本也要验证，确认文字列表没有额外空白容器。

## 11. 验收条件

以下条件全部满足才可标记代码适配完成：

- [ ] 当前书源导入和再次保存后 10 个图片规则不丢失。
- [ ] 基础纯文本段评书源仍可使用。
- [ ] 主评、内嵌回复、回复分页和 children 的图片均被严格解析。
- [ ] 无图、图文混合、纯图片三种内容均正常展示。
- [ ] 主评列表、回复 header、根回复和嵌套回复均能显示图片。
- [ ] 图片点击复用 `PhotoDialog`，请求继承正确 source origin/header。
- [ ] 图片失败不影响段评文字、cursor 分页、回复树和阅读正文。
- [ ] RecyclerView 快速滚动无串图、残留或明显卡顿。
- [ ] 单测、lint 和 `AppDebug` 构建通过。
- [ ] “诡舍”真实 proxy smoke 同时命中主评和回复图片。
- [ ] 日志、测试 fixture 和文档中没有真实凭据、正文或图片 URL。

## 12. 关联文档

- [段落评论公开 API v1](./paragraph-comments-api.md)
- [Legado 段落评论适配指南（历史基础能力审计）](./legado-paragraph-comments-adaptation.md)
- [番茄 Legado 书源](./fanqie-legado-source.json)
