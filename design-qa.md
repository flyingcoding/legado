# 段评界面设计 QA

## 验收范围

- 页面：阅读页行内段评角标、段评底部抽屉、主评论、内联回复、评论图片和头像状态。
- 参考图：
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/reference-target-badge.jpg`
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/reference-target-dialog.jpg`
- 实现截图：
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/implementation-reader-theme-light-pass2.png`
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/implementation-dialog-theme-light-pass2.png`
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/implementation-reader-theme-dark-pass2.png`
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/implementation-dialog-theme-dark-pass2.png`
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/implementation-dialog-final-checked.png`
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/implementation-dialog-replies.png`
- 同屏对比：
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/comparison-badge-final.jpg`
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/comparison-dialog-final.jpg`
  - `.trellis/tasks/08-03-paragraph-comment-ui/research/comparison-theme-final.jpg`

## 对比条件

- 模拟器：`emulator-5554`，物理视口 `1080 x 2424`。
- 参考图原始尺寸：`1440 x 3168`。
- 对比归一化：参考图按宽度缩放至 `1080 x 2376`，上下各补 `24px`；实现截图保持 `1080 x 2424`。
- 状态差异：参考 App 为白色阅读主题；Legado 分别在用户已有的浅绿色阅读主题和深色阅读主题下验收。抽屉颜色复用阅读器自身主题输出，不强制复制参考 App 的白色背景。
- 内容差异：评论数量、昵称、正文、点赞和图片均来自当前真实数据，只比较层级、对齐、间距、圆角、头像和交互结构。

## 视觉检查

### 阅读页角标

- 角标文字根据正文高度分档放大，三位数和 `999+` 均可读。
- 水平位置以气泡主体矩形为中心，不再受左侧气泡尖角干扰。
- 垂直位置使用字体度量计算基线，数字不再贴顶或贴底。
- 长文本按实际测量宽度收缩，`999+` 未出现裁切或越界。
- 气泡轮廓与数字每次绘制前同步当前 `ReadBookConfig.textColor`；由浅绿色主题运行时切换到深色主题后立即变为灰白色，没有残留黑色角标。
- 正文布局、点击占位和阅读主题颜色保持原有产品语义。

### 段评抽屉

- 抽屉提升至约 90% 高度，顶部圆角和遮罩层显示正常。
- 顶部改为 64dp 简洁头部：左侧 48dp 下箭头关闭入口、标题视觉居中、无错误灰色 Toolbar 和独立拖拽横条。
- 主列表取消全宽分割线，以留白组织评论分组，整体密度接近参考图。
- 主评论采用圆形头像、弱化用户名、突出正文、时间与点赞同一元信息行、独立回复入口。
- 内联回复保持有限缩进，头像、回复目标、正文、时间、点赞和图片均无重叠、截断或越界。
- 评论缩略图比例正确，无拉伸；展开回复后大图与文本布局正常。
- 抽屉背景、标题、关闭图标、主/次文字、回复入口、分隔线、刷新与加载控件统一使用当前阅读主题 palette；浅绿色与深色主题均无独立白底残留。
- 纯色主题与阅读页使用同一背景色；图片主题按设计复用阅读器的 `bgMeanColor`，不把背景图重复铺入滚动列表。

### 头像与图片

- 当前首屏可升级的远程头像均正常显示为圆形，不再批量回退默认头像。
- HTTP 输入只升级为 HTTPS 后加载，不允许明文回退；不支持 TLS、空值或非法地址继续使用本地占位。
- Glide 日志未出现本轮有效头像对应的 `Received null model`，也未发现 Fatal 或 AndroidRuntime 崩溃。
- 占位头像仍有一条真实数据展示，尺寸、圆形容器和对齐正常。

### 字体、间距、颜色和文案

- 标题、昵称、正文、时间、点赞、回复入口的字号和权重层级清楚。
- 评论左右边距、头像与正文间距、评论之间垂直留白一致。
- 正文使用阅读页正文色；弱文本、分隔线、回复入口、触摸反馈、刷新和进度色均从当前阅读正文色派生，主题切换后层级与对比度保持一致。
- 评论数量、时间、点赞和回复文案均使用真实数据，未引入占位文案。

## 交互检查

- 点击行内角标能打开对应段评抽屉，未触发正文翻页。
- 点击左上角下箭头能关闭抽屉并返回阅读页。
- 评论列表可滚动。
- “展开 7 条回复”可点击，内联回复成功展开，评论图片正常显示。
- 在阅读菜单中切换浅色/深色主题后，阅读页角标与重新打开的抽屉同步更新背景和前景色。
- 关闭后阅读 Activity 保持前台，无状态丢失或崩溃。

## 差异分级

- P0：无。
- P1：无。
- P2：无。
- P3：参考 App 含底部评论输入栏和互动图标，而当前 Legado 段评功能为只读展示；本任务未要求新增评论发布能力，故保留现有产品边界。
- 已知覆盖限制：当前真实章节提供三位数和 `999+` 角标；单字符字号分档由单元测试覆盖，未获得同章节真实单字符截图。

## 迭代记录

1. 基线复现：角标文字过小且偏位；抽屉固定 75% 高度、Toolbar 样式错误、头像大量占位。
2. 实现后对比：修正画笔初始化与字体基线；重构抽屉头部和评论层级；头像地址安全升级为 HTTPS。
3. 第一轮模拟器复核：角标、圆角抽屉、圆形远程头像、滚动、回复展开和关闭交互通过。
4. 用户补充主题复用要求：抽屉从 App 通用背景切换为统一的阅读主题 palette，浅绿色与深色抽屉均和阅读页打通。
5. 双主题 QA 发现并修复 P1：深色模式下角标 Paint 曾保留浅色主题的黑色；改为每次绘制同步阅读正文色后复验通过。
6. 独立检查补齐 EInk 动态背景顶边框后，重新组装、覆盖安装最终 APK；浅绿色主题下再次打开真实段评并检查日志，无视觉回归、Glide 错误或崩溃。

final result: passed
