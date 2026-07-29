# phase_p17_legado_booksource — 输出 legado(阅读3.0) 书源

- generated: 2026-07-26
- verdict: **PLANNED → (待实现填充)**

## 目标

把 P16 的 `fanqie-server` API 包装成 legado(阅读3.0)书源 JSON，用户导入阅读 App 即可搜索/看书。

## 权威来源

本地 `/Users/flying/Desktop/do/legado` 源码：
- 规则字段：`data/entities/BookSource.kt` + `rule/{SearchRule,BookInfoRule,TocRule,ContentRule}.kt`
- URL 语法：`AnalyzeUrl.kt`（`{{key}}`/`{{page}}` 在 searchUrl；`{{js}}` 内嵌）；URL 内嵌 JSONPath 用 `{$.field}`；相对路径自动补全 `bookSourceUrl`。

## fanqie-server API ↔ legado 规则映射

| legado 阶段 | URL（相对 bookSourceUrl） | 响应根 → 规则 |
|---|---|---|
| searchUrl | `/api/search?query={{key}}&count=20&offset={{(page-1)*20}}` | `$.data.results[]` |
| ruleSearch | — | name=`$.book_name` author=`$.author` kind=`$.category` coverUrl=`$.cover_url` intro=`$.description` wordCount=`$.word_count` bookUrl=`/api/book/detail?book_id={$.book_id}` |
| ruleBookInfo（bookUrl 响应） | — | name=`$.data.book_name` author=`$.data.author` kind=`$.data.category` coverUrl=`$.data.cover_url` intro=`$.data.description` wordCount=`$.data.word_count` tocUrl=`/api/book/directory?book_id={$.data.book_id}` |
| ruleToc（tocUrl 响应） | — | chapterList=`$.data.chapter_list` chapterName=`$.title` chapterUrl=`/api/book/chapter?book_id={$.book_id}&item_id={$.item_id}` |
| ruleContent（chapterUrl 响应） | — | content=`$.data.content` title=`$.data.title` |

## 关键改动（跨层 book_id）

ruleToc 迭代 `chapter_list` 时，章节项只有 `item_id`，但 chapterUrl 需 `book_id`（目录顶层）。**最可靠方案：目录响应每章自带 book_id**：
- `model.ChapterItem` 加 `BookID string json:"book_id,omitempty"`（omitempty，不影响直连番茄路径）
- `upstream.go` Directory 映射每章填 `BookID`
- 规则 `chapterUrl=/api/book/chapter?book_id={$.book_id}&item_id={$.item_id}` 极简可靠（不用 js/正则/baseUrl 技巧）

## 交付

- `fanqie-legado-source.json`（单书源，规则完整，bookSourceUrl 占位）
- Go 改动：model + upstream（加 book_id）
- 验证：重新部署 fanqie-server；jsonpath 模拟解析验证规则；说明导入 + 改地址方式

## 部署地址

bookSourceUrl 需用户手机可访问的 fanqie-server 地址。home-server 内网 `192.168.5.100`；用占位符 + 说明替换。

## 边界

书源仅包装 P16 API，签名仍是 unidbg 模拟（`pure_algorithm_ok=false`）。
