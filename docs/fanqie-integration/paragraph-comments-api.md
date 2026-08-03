# 段落评论公开 API v1

本文定义 `fanqie-server` 面向修改版 Legado 和其他只读客户端的段落评论合同。合同标识固定为：

```text
fanqie.paragraph-comments.v1
```

> 图片增量（2026-07-31）：当前 proxy 在同一 v1 合同下为主评和回复增加 `images` 数组；无图片返回 `[]`。Legado 的字段、解析、缓存、UI 和测试待修改项见 [Legado 段评图片适配待修改清单](./paragraph-comment-images-code-todo.md)。本文后续章节保留基础文字段评合同，实施图片能力时以该增量清单为补充约束。

本文是接口合同，不是生产切换证明。只有 Java/Go 新版本成对部署、健康门禁和真实 smoke 均通过后，才能声明生产环境已经提供该能力。

## 1. 范围与调用链

v1 仅提供三个公开 `GET` 端点：

| 用途 | 公开端点 |
| --- | --- |
| 章节段评索引和可选首屏摘要 | `/api/book/paragraph_comments` |
| 单段主评分页 | `/api/book/paragraph_comment_page` |
| 单条主评的回复页 | `/api/book/paragraph_comment_replies` |

生产调用链如下：

```text
修改版 Legado
  -> fanqie-server 公共 API
  -> owned Java/full-java 私有评论端点
  -> runtime profile + Unidbg MetaSec
  -> FQNovel UGC
```

正式链路不依赖模拟器、真机、Frida Server 或 Frida signer sidecar。Frida 只允许用于开发探测，不属于生产启动、健康检查或回滚依赖。

v1 不提供发评、发回复、点赞、点踩、删除或举报能力。客户端不得根据本文拼装任何 FQNovel 上游 URL，也不得直连 Java 私有端点。

## 2. 通用请求规则

### 2.1 Base URL 与认证

以下示例使用占位地址：

```text
https://fanqie.example.invalid
```

生产环境应使用 HTTPS。若服务启用了 `FANQIE_API_KEY`，所有 `/api/*` 请求都必须携带公共凭据：

```http
Authorization: Bearer YOUR_SECRET_KEY
```

公共 API key 与 Go 调用 Java 的内部 Bearer 必须是两个不同随机值。内部 Bearer 只存在于服务端只读凭据文件中，绝不能下发给 Legado。

全局认证中间件发生的 `401` 继续使用服务原有通用错误 envelope，可能没有 `contract` 字段。这是评论 v1 envelope 的唯一例外。虽然服务保留 `api_key` query 参数用于本地调试，生产和书源配置只应使用 Authorization header，避免凭据进入 URL、历史记录和代理日志。

### 2.2 编码与参数约定

- 所有端点只接受 `GET`；`HEAD` 和其他方法返回 `405`，并携带 `Allow: GET`。
- ID 参数按十进制纯数字传输。响应中的主评/回复/用户 ID 使用 JSON string，客户端不得转成 `Long` 后再持久化，以免未来溢出或丢失格式。
- `para_id` 是 JSON integer，范围 `0..2147483647`。
- `count` 类参数范围 `1..50`；超界不截断，直接返回 `400 invalid_argument`。
- `detail_limit` 范围 `0..30`；`0` 表示只读取章节索引，不 fan-out 主评详情。
- `include_replies` 只接受 `0`、`1`、`false`、`true`。
- `item_version` 按 UTF-8 计 `1..128` 字节，默认 `0`；不得包含 CR/LF。
- `cursor` 是不透明 UTF-8 字符串，最大 4096 字节。客户端只能原样保存、URL 编码后回传，不能解析、拼接、递增或从列表长度推导。
- 同一个参数名最多出现一次；重复参数返回 `400 invalid_argument`。未使用的未知参数不属于 v1 扩展机制，客户端不应发送。
- 所有模板变量必须通过 URL builder 编码。特别是 cursor，不允许直接做字符串连接。

本文示例中的数字 ID、文本和 `example.invalid` URL 均为合成占位数据，不对应真实书籍、章节、评论或用户。

## 3. 通用响应 envelope

### 3.1 成功

三个端点成功时均返回 HTTP `200`：

```json
{
  "contract": "fanqie.paragraph-comments.v1",
  "code": 0,
  "message": "SUCCESS",
  "data": {}
}
```

| 字段 | JSON 类型 | 必返 | 语义 |
| --- | --- | --- | --- |
| `contract` | string | 是 | 固定为 `fanqie.paragraph-comments.v1` |
| `code` | integer | 是 | 成功固定为 `0` |
| `message` | string | 是 | 成功固定为 `SUCCESS` |
| `data` | object | 是 | 端点数据对象 |

空列表也是成功，使用 `[]`，不使用 `null`。分页字符串在没有下一页时使用空串 `""`。

### 3.2 评论路由内错误

```json
{
  "contract": "fanqie.paragraph-comments.v1",
  "code": -1,
  "message": "上游暂不可用",
  "error": {
    "type": "upstream_unavailable",
    "retryable": true,
    "parameter": ""
  }
}
```

| 字段 | JSON 类型 | 必返 | 语义 |
| --- | --- | --- | --- |
| `contract` | string | 是 | 固定合同版本 |
| `code` | integer | 是 | 错误固定为 `-1` |
| `message` | string | 是 | 稳定、可展示的中文摘要；不得用于程序分支 |
| `error.type` | string | 是 | 稳定错误分类，见错误矩阵 |
| `error.retryable` | boolean | 是 | 是否适合退避后重试 |
| `error.parameter` | string | 是 | 参数错误时指出参数名；无适用参数时为 `""` |

客户端分支应组合使用 HTTP 状态、`error.type` 和 `retryable`，不要匹配 `message` 文本。

## 4. 章节段评索引

```http
GET /api/book/paragraph_comments
```

该端点先读取章节所有段落的评论计数；`detail_limit>0` 时，再按评论总数选择最多 N 个热门段拉取主评首屏。最终 `paragraphs` 始终按 `para_id` 升序返回，不能依赖热门选择顺序。

### 4.1 请求参数

| 参数 | 必填 | 默认值 | 约束与语义 |
| --- | --- | --- | --- |
| `item_id` | 是 | — | 纯数字章节 ID |
| `book_id` | 条件必填 | 空 | 纯数字书 ID；`detail_limit>0` 或 eager 回复时必填 |
| `item_version` | 否 | `0` | UTF-8 `1..128` 字节 |
| `detail_limit` | 否 | `5` | `0..30`；`0` 只取计数 |
| `comment_count` | 否 | `10` | `1..50`；每个选中段的主评首屏条数 |
| `include_replies` | 否 | `false` | 是否对首屏主评 eager 加载回复；生产客户端应保持 false |
| `reply_count` | 条件可选 | `20` | `1..50`；只有 `include_replies=true/1` 时允许传入 |

因为 `detail_limit` 默认是 `5`，省略 `book_id` 时必须显式传 `detail_limit=0`。书源 v1 的章节索引模板正是纯索引模式。

```bash
BASE_URL=https://fanqie.example.invalid
ITEM_ID=2000000000000000001

curl --fail-with-body \
  -H 'Authorization: Bearer YOUR_SECRET_KEY' \
  "$BASE_URL/api/book/paragraph_comments?item_id=$ITEM_ID&detail_limit=0"
```

拉取最多 5 个热门段的主评首屏，但不加载回复正文：

```bash
BOOK_ID=1000000000000000001

curl --fail-with-body \
  -H 'Authorization: Bearer YOUR_SECRET_KEY' \
  "$BASE_URL/api/book/paragraph_comments?book_id=$BOOK_ID&item_id=$ITEM_ID&detail_limit=5&comment_count=10&include_replies=false"
```

### 4.2 `data` 字段

| 字段 | JSON 类型 | 必返 | 语义 |
| --- | --- | --- | --- |
| `item_id` | string | 是 | 请求章节 ID |
| `book_id` | string | 是 | 请求书 ID；纯索引兼容调用可为空串 |
| `item_version` | string | 是 | 上游返回版本优先，否则为请求值 |
| `paragraphs` | array | 是 | 段落数组，按 `para_id` 升序；空章为 `[]` |
| `partial` | boolean | 是 | 任一已请求的详情或 eager 回复失败时为 true |
| `warnings` | array | 是 | 脱敏局部失败列表；完整成功为 `[]` |

### 4.3 段落项

| 字段 | JSON 类型 | 必返 | 可空/省略 | 单位与语义 |
| --- | --- | --- | --- | --- |
| `para_id` | integer | 是 | 否 | FQNovel 服务端段落标识，不等同本地行号 |
| `count` | integer | 是 | 否 | 该段主评总数，非负 |
| `hot` | string | 是 | 可为空串 | 上游热度标记，客户端不自定义枚举 |
| `user_count` | integer | 是 | 否 | 参与用户数，非负 |
| `detail_loaded` | boolean | 是 | 否 | 本次是否成功加载主评详情 |
| `comments` | array | 是 | 空数组允许 | 主评数组；未请求、失败或真实为空都可能是 `[]` |

纯索引成功示例：

```json
{
  "contract": "fanqie.paragraph-comments.v1",
  "code": 0,
  "message": "SUCCESS",
  "data": {
    "item_id": "2000000000000000001",
    "book_id": "",
    "item_version": "0",
    "paragraphs": [
      {
        "para_id": 12,
        "count": 3,
        "hot": "1",
        "user_count": 3,
        "detail_loaded": false,
        "comments": []
      }
    ],
    "partial": false,
    "warnings": []
  }
}
```

空章成功示例：

```json
{
  "contract": "fanqie.paragraph-comments.v1",
  "code": 0,
  "message": "SUCCESS",
  "data": {
    "item_id": "2000000000000000001",
    "book_id": "",
    "item_version": "0",
    "paragraphs": [],
    "partial": false,
    "warnings": []
  }
}
```

## 5. 单段主评分页

```http
GET /api/book/paragraph_comment_page
```

该端点用于点击评论气泡后的主评列表和继续分页，不加载回复正文。

### 5.1 请求参数

| 参数 | 必填 | 默认值 | 约束 |
| --- | --- | --- | --- |
| `item_id` | 是 | — | 纯数字章节 ID |
| `book_id` | 是 | — | 纯数字书 ID |
| `para_id` | 是 | — | integer `0..2147483647` |
| `item_version` | 否 | `0` | UTF-8 `1..128` 字节 |
| `count` | 否 | `20` | `1..50` |
| `cursor` | 否 | 空 | 不透明 UTF-8 字符串，最大 4096 字节 |

第一页：

```bash
BOOK_ID=1000000000000000001
ITEM_ID=2000000000000000001
PARA_ID=12

curl --fail-with-body --get \
  -H 'Authorization: Bearer YOUR_SECRET_KEY' \
  --data-urlencode "book_id=$BOOK_ID" \
  --data-urlencode "item_id=$ITEM_ID" \
  --data-urlencode "para_id=$PARA_ID" \
  --data-urlencode 'item_version=0' \
  --data-urlencode 'count=20' \
  "$BASE_URL/api/book/paragraph_comment_page"
```

下一页必须使用服务端返回的 `next_cursor`：

```bash
NEXT_CURSOR='OPAQUE_CURSOR_FROM_PREVIOUS_RESPONSE'

curl --fail-with-body --get \
  -H 'Authorization: Bearer YOUR_SECRET_KEY' \
  --data-urlencode "book_id=$BOOK_ID" \
  --data-urlencode "item_id=$ITEM_ID" \
  --data-urlencode "para_id=$PARA_ID" \
  --data-urlencode 'count=20' \
  --data-urlencode "cursor=$NEXT_CURSOR" \
  "$BASE_URL/api/book/paragraph_comment_page"
```

### 5.2 `data` 字段

| 字段 | JSON 类型 | 必返 | 语义 |
| --- | --- | --- | --- |
| `item_id` | string | 是 | 请求章节 ID |
| `book_id` | string | 是 | 请求书 ID |
| `item_version` | string | 是 | 本页对应的章节版本 |
| `para_id` | integer | 是 | 请求段落 ID |
| `comments` | array | 是 | 本页主评；空页为 `[]` |
| `total` | integer | 是 | 上游报告的主评总数，非负；不能用于推导 cursor |
| `has_more` | boolean | 是 | 是否可以继续请求 |
| `next_cursor` | string | 是 | 下一页 opaque cursor；无更多时为 `""` |

示例：

```json
{
  "contract": "fanqie.paragraph-comments.v1",
  "code": 0,
  "message": "SUCCESS",
  "data": {
    "item_id": "2000000000000000001",
    "book_id": "1000000000000000001",
    "item_version": "0",
    "para_id": 12,
    "comments": [
      {
        "comment_id": "3000000000000000001",
        "text": "合成主评文本",
        "user_id": "4000000000000000001",
        "user_name": "示例用户",
        "user_avatar": "https://cdn.example.invalid/avatar.png",
        "create_timestamp": 1700000000,
        "digg_count": 2,
        "reply_count": 1,
        "replies_loaded": false
      }
    ],
    "total": 21,
    "has_more": true,
    "next_cursor": "OPAQUE_CURSOR_PAGE_2"
  }
}
```

## 6. 主评回复页

```http
GET /api/book/paragraph_comment_replies
```

点击某条主评的回复数时调用。服务端只组装当前页能够确定的树；跨页关系由客户端按关系 ID 合并。

### 6.1 请求参数

| 参数 | 必填 | 默认值 | 约束 |
| --- | --- | --- | --- |
| `item_id` | 是 | — | 纯数字章节 ID |
| `book_id` | 是 | — | 纯数字书 ID |
| `comment_id` | 是 | — | 纯数字主评 ID |
| `count` | 否 | `20` | `1..50` |
| `cursor` | 否 | 空 | 不透明 UTF-8 字符串，最大 4096 字节 |

```bash
COMMENT_ID=3000000000000000001

curl --fail-with-body --get \
  -H 'Authorization: Bearer YOUR_SECRET_KEY' \
  --data-urlencode "book_id=$BOOK_ID" \
  --data-urlencode "item_id=$ITEM_ID" \
  --data-urlencode "comment_id=$COMMENT_ID" \
  --data-urlencode 'count=20' \
  "$BASE_URL/api/book/paragraph_comment_replies"
```

### 6.2 `data` 字段

| 字段 | JSON 类型 | 必返 | 语义 |
| --- | --- | --- | --- |
| `item_id` | string | 是 | 请求章节 ID |
| `book_id` | string | 是 | 请求书 ID |
| `comment_id` | string | 是 | 请求主评 ID |
| `replies` | array | 是 | 本页可组装的回复树；空页为 `[]` |
| `total` | integer | 是 | 上游报告的回复总数 |
| `has_more` | boolean | 是 | 是否可以继续请求 |
| `next_cursor` | string | 是 | 下一页 cursor；无更多时为 `""` |

```json
{
  "contract": "fanqie.paragraph-comments.v1",
  "code": 0,
  "message": "SUCCESS",
  "data": {
    "item_id": "2000000000000000001",
    "book_id": "1000000000000000001",
    "comment_id": "3000000000000000001",
    "replies": [
      {
        "reply_id": "5000000000000000001",
        "reply_to_comment_id": "3000000000000000001",
        "text": "合成一级回复",
        "user_id": "4000000000000000002",
        "user_name": "回复用户",
        "create_timestamp": 1700000001,
        "digg_count": 1,
        "reply_count": 1,
        "children": [
          {
            "reply_id": "5000000000000000002",
            "parent_reply_id": "5000000000000000001",
            "reply_to_comment_id": "3000000000000000001",
            "reply_to_reply_id": "5000000000000000001",
            "reply_to_user_name": "回复用户",
            "text": "合成二级回复",
            "create_timestamp": 1700000002,
            "digg_count": 0,
            "reply_count": 0
          }
        ]
      }
    ],
    "total": 2,
    "has_more": false,
    "next_cursor": ""
  }
}
```

## 7. 主评字段字典

| 字段 | JSON 类型 | 必返 | 可空/省略 | 单位与语义 |
| --- | --- | --- | --- | --- |
| `comment_id` | string | 是 | 不省略 | 主评唯一 ID；按字符串保存 |
| `text` | string | 是 | 可为空串 | 正文 |
| `user_id` | string | 否 | 上游无值时省略 | 用户 ID，不是稳定头像键 |
| `user_name` | string | 否 | 上游无值时省略 | 展示名 |
| `user_avatar` | string | 否 | 空时省略 | 可能过期的 CDN URL |
| `create_timestamp` | integer | 是 | 未知为 `0` | Unix 秒，不是毫秒 |
| `digg_count` | integer | 是 | 不省略 | 点赞展示数；v1 不可写 |
| `reply_count` | integer | 是 | 不省略 | 回复统计数 |
| `replies_loaded` | boolean | 是 | 不省略 | eager 回复是否成功加载；普通主评页为 false |
| `replies` | array | 否 | 仅 eager 成功时返回 | 当前 eager 回复树 |
| `reply_total` | integer | 否 | 仅 eager 成功时返回 | eager 页的回复总数 |
| `reply_has_more` | boolean | 否 | 仅 eager 成功时返回 | eager 页是否还有更多 |
| `reply_next_cursor` | string | 否 | 无更多时可空或省略 | eager 下一页 cursor |

`include_replies=true` 是兼容和低流量调试能力。生产 Legado 应先显示 `reply_count`，用户展开时再请求回复端点，避免章节首屏产生 N+1 请求。

## 8. 回复字段字典

| 字段 | JSON 类型 | 必返 | 可空/省略 | 单位与语义 |
| --- | --- | --- | --- | --- |
| `reply_id` | string | 是 | 不省略 | 回复唯一 ID |
| `parent_reply_id` | string | 否 | 无值省略 | 上游父/根关系标识 |
| `reply_to_comment_id` | string | 否 | 无值省略 | 所属主评 ID |
| `reply_to_reply_id` | string | 否 | 无值省略 | 直接回复目标；跨页挂接主键 |
| `text` | string | 是 | 可为空串 | 回复正文 |
| `user_id` | string | 否 | 无值省略 | 回复用户 ID |
| `user_name` | string | 否 | 无值省略 | 回复用户名 |
| `user_avatar` | string | 否 | 空时省略 | 可能过期的 CDN URL |
| `reply_to_user_name` | string | 否 | 无值省略 | 被回复用户名，仅展示 |
| `create_timestamp` | integer | 是 | 未知为 `0` | Unix 秒 |
| `digg_count` | integer | 是 | 不省略 | 点赞展示数 |
| `reply_count` | integer | 是 | 不省略 | 该回复的子回复统计 |
| `children` | array | 否 | 无同页子项时省略 | 当前页可确定的子回复 |

服务端单页回复树最多展开 4096 个节点（包括上游嵌套 `sub_reply`）；超过上限按 `502 upstream_protocol` 失败，避免畸形上游数据耗尽内存。公共请求的 `count<=50` 是第一道容量边界，不代表嵌套节点可以无限增长。

## 9. partial 与 warnings

只有章节端点可能在 HTTP `200` 中返回 `partial=true`。含义是章节索引成功，但某个已请求的段落详情或 eager 回复失败；服务端不会把局部故障伪装成“没有评论”。

warning 字段：

| 字段 | JSON 类型 | 必返 | 语义 |
| --- | --- | --- | --- |
| `scope` | string | 是 | `paragraph` 或 `reply` |
| `type` | string | 是 | 与公开 `error.type` 相同的稳定分类 |
| `retryable` | boolean | 是 | 是否适合退避重试 |
| `para_id` | integer | 否 | 段落详情或该段 eager 回复失败时返回 |
| `comment_id` | string | 否 | eager 回复失败时与 `para_id` 一同返回 |

```json
{
  "contract": "fanqie.paragraph-comments.v1",
  "code": 0,
  "message": "SUCCESS",
  "data": {
    "item_id": "2000000000000000001",
    "book_id": "1000000000000000001",
    "item_version": "0",
    "paragraphs": [
      {
        "para_id": 12,
        "count": 3,
        "hot": "1",
        "user_count": 3,
        "detail_loaded": false,
        "comments": []
      }
    ],
    "partial": true,
    "warnings": [
      {
        "scope": "paragraph",
        "type": "upstream_timeout",
        "retryable": true,
        "para_id": 12
      }
    ]
  }
}
```

客户端必须同时检查 `detail_loaded` 和对应 warning：

- `detail_loaded=false` 且索引模式 `detail_limit=0`：未请求详情，不是错误。
- `detail_loaded=false` 且存在该 `para_id` warning：详情失败，可显示计数并提供重试。
- `detail_loaded=true` 且 `comments=[]`：详情请求成功，本页确实为空。

## 10. 错误矩阵

| HTTP | `error.type` | `retryable` | 典型条件 | 客户端建议 |
| --- | --- | --- | --- | --- |
| 400 | `invalid_argument` | false | 缺失、格式错误、越界、非法参数组合 | 修正请求，不自动重试 |
| 405 | `method_not_allowed` | false | 使用非 GET 方法 | 修正客户端 |
| 429 | `rate_limited` | true | Java 有界准入饱和 | 尊重合法 `Retry-After`，加抖动退避 |
| 503 | `upstream_not_ready` | true | Java、signer 或 registerkey 未就绪 | 短暂退避，检查 readiness |
| 503 | `upstream_unavailable` | true | Go 到 Java 或 Java 到上游的普通传输失败 | 退避重试，检查网络和进程 |
| 503 | `upstream_business` | 由响应给出 | FQNovel 非零业务码 | 不解释上游正文，按 retryable 处理 |
| 502 | `upstream_protocol` | false | 内部合同、JSON、空 body 或 4 MiB 大小异常 | 停止自动重试，检查版本配对 |
| 504 | `upstream_timeout` | true | 内部或 FQNovel 请求超时 | 退避重试，避免并发放大 |
| 500 | `internal_error` | false | Go 本地不可恢复错误 | 记录脱敏诊断并升级处理 |

`429` 可能携带 `Retry-After`。服务端只透传合法的非负秒数或 RFC HTTP-date；Java admission 正常返回正整数秒数。客户端应按 HTTP 语义解析，解析失败时使用自身指数退避。建议第一页最多自动重试 2 次，基础间隔 1 秒并加入随机抖动；切章或 generation 变化时立即取消。

## 11. 分页、去重与回复树合并

### 11.1 主评分页

1. 第一页不传 cursor。
2. 将响应中 `comments` 按 `comment_id` 放入有序映射；已存在 ID 更新内容但不重复插入。
3. 只有 `has_more=true` 且 `next_cursor` 非空时才能请求下一页。
4. 下一页原样回传 `next_cursor`；服务端返回相同 cursor 时停止并报告协议异常，防止死循环。
5. 主动刷新时清空旧 cursor 链和已加载页；可以按 `comment_id` 保留 UI 展开状态。

### 11.2 回复跨页合并

服务端当前页内的 `reply_to_reply_id` 若能命中同页回复，会将其放入父项 `children`。父项不在当前页时，该回复暂时作为页根返回，但关系 ID 不丢失。

客户端合并算法：

```text
输入：按请求顺序得到的回复页 pages
nodes = LinkedHashMap<replyId, Reply>()

for page in pages:
  for reply in depthFirst(page.replies):
    nodes[reply.replyId] = merge(nodes[reply.replyId], reply without children)

roots = []
for node in nodes.values:
  parentId = node.replyToReplyId
  if parentId is not empty and nodes contains parentId and parentId != node.replyId:
    attach node once to nodes[parentId].children
  else:
    roots.add(node)  // 父项尚未出现、关系缺失或自环，作为 orphan 根展示

对每个 children 以 replyId 去重；检测访问栈，出现环时断开该边并保留为根。
```

不要只依赖 `parent_reply_id` 或用户名。主要挂接键是 `reply_to_reply_id`，`reply_to_comment_id` 用来验证回复仍属于当前主评。分页期间评论可能新增、删除或重排，因此 `total` 只用于展示，不能作为循环终止条件。

## 12. `para_id` 与正文映射限制

`para_id` 是 FQNovel 服务端段落标识。Legado 会执行去标题、净化替换、图片占位、空行处理和本地重新分段，服务端 `para_id` 不保证与 `BookContent.textList` 下标一一对应。

客户端必须采用可验证的映射：

1. 优先保留章节响应中的稳定段落元数据；若正文合同没有该元数据，不得从评论文本或标题猜 ID。
2. 对当前番茄书源，可在适配层验证 `para_id` 与净化前段序的关系，并将映射结果绑定到 `(bookId,itemId,itemVersion,contentHash)`。
3. 任何越界、重复、映射证据不足或正文 hash 变化都应隐藏对应气泡，而不是把评论放到可能错误的段落。
4. 标题评论与正文评论分开建模；不能默认标题占用 `para_id=0`。

## 13. 时间、头像与隐私

- `create_timestamp` 单位固定为 Unix 秒；`0` 表示未知。Android 转毫秒时应先转 `Long` 再乘 `1000L`，并对 `0` 显示“时间未知”。
- `user_avatar` 可能为空、过期或带时效签名。使用 HTTPS 图片加载器、短期内存/磁盘缓存和占位图；403/过期时刷新评论页，不永久缓存失败 URL。
- 头像 URL 不是用户身份主键，不能用于去重。
- 服务端不代理、不改写、不长期缓存头像。
- 日志、埋点和崩溃报告不得记录 cursor、评论/回复正文、头像完整 URL、Authorization、Cookie、签名头、device/install ID 或内部错误正文。

## 14. 缓存、并发与容量

- Go/Java 默认不缓存评论成功响应；客户端应接受评论计数和内容随时变化。
- 推荐 Legado 缓存：章节索引 TTL 60 秒；主评和回复页 TTL 30 秒。
- 索引 key：`(sourceUrl,bookId,itemId,itemVersion)`。
- 主评 key：`(sourceUrl,bookId,itemId,itemVersion,paraId,cursor)`。
- 回复 key：`(sourceUrl,bookId,itemId,commentId,cursor)`。
- 切换书源、章节版本变化、正文 hash 变化、用户主动刷新时失效相关缓存。
- 不缓存错误响应；离线时可以展示未过期或带明确“缓存”标识的最近成功值。
- 章节索引应异步加载；同一章节只保留一个 in-flight 索引请求。主评和回复分页分别串行，不并发请求同一 cursor。
- 客户端不得将 `detail_limit`、`comment_count`、`reply_count` 或 `count` 放大到合同上限之外，也不得用 eager 回复批量预取整章。

## 15. 故障排查

| 现象 | 检查 |
| --- | --- |
| `401` 且没有 `contract` | 公共 `YOUR_SECRET_KEY` 是否正确；是否误用了内部 token |
| `400 invalid_argument` | 必填 ID、纯数字格式、布尔字面量、count 范围、cursor UTF-8 字节数 |
| `429 rate_limited` | 降低客户端并发，尊重 `Retry-After`；检查 Java admission 配置 |
| `503 upstream_not_ready` | Java `/readyz`、signer、registerkey、最近上游成功状态 |
| `502 upstream_protocol` | Java/Go 是否为同一评论合同版本；代理是否截断响应 |
| `partial=true` | 按 warning scope 局部重试，不清空已成功的索引或段落 |
| 计数有但无气泡 | Legado 是否完成 `ruleReview` 持久化、para 映射和 `TextChapterLayout` 改造 |
| 头像 403 | 丢弃失败 URL，刷新对应评论页，继续显示占位图 |
| 重复回复 | 是否按 `reply_id` 跨页去重；是否错误使用数组下标或用户名作为键 |

## 16. 发布验证边界

静态 JSON 校验、Go/Java 单元测试和合成 fixture 只能证明合同实现，不等于生产实测。真实验证必须在具备 runtime profile、Unidbg 资产和可达上游网络的非正式端口完成，且只记录业务 code、计数、分页布尔、耗时和不可逆 hash；不得保存真实正文、头像、ID、cursor、请求体或签名材料。
