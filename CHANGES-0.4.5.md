# DeepSeek(3rd) 0.4.5 本地改动说明

- 日期：2026-11-23
- 基线：仓库 main（versionName 0.4.5 / versionCode 22），未改版本号
- 内容：① 渲染与滚动性能修复 ② 长上下文自动压缩
- 数据兼容：会话 JSON 字段只增不减、均有默认值，旧会话 / 官方导出 ZIP 可直接导入

---

## 一、性能修复（解决卡顿、闪烁、贴底跳跃）

### 问题 1：导入长对话后滑动历史突然卡住
样本：63 条消息、32 条助手回复、**0 公式 0 图表**。
- 每条回复建一个 WebView；
- `render.html` 无条件加载 `mermaid.min.js`（3.2 MB）与 KaTeX（约 600 KB）；
- 滚动时 WebView 反复销毁/重建，每次重新解析 3.2 MB JS；
- `AndroidView` 无 `onRelease`，滚出去的 WebView 等 GC，内存持续上涨。

### 问题 2：闪、贴底难、上滑跳
- WebView 高度由 JS 异步回传（0 → 实际高度），列表反复重排；
- 贴底用 `!canScrollForward` 判断，高度一抖就翻转；
- 流式每个 token 都 `scrollToItem`，与用户上滑抢滚动。

### 改动
| 文件 | 改动 |
|---|---|
| `assets/render.html` | KaTeX / mermaid 按需注入，无公式、无图表时不加载 |
| `ui/RichMarkdown.kt` | 仅正文变化才重渲染；高度只在变化时回写；`onRelease` 销毁 WebView |
| `ui/MessageViews.kt` | 正文默认原生 Markwon（高度同步），仅 ` ```mermaid ` 用 WebView |
| `ui/ChatScreen.kt` | 贴底改按「最后一项完整可见」判断；仅手指拖动停止跟随；流式用 `requestScrollToItem` |
| `data/SessionJson.kt` | 导入时 `pending/streaming` 归一为 `stopped` |

已知取舍：代码块 / 表格 / 引用块样式比 WebView 版朴素（Markwon 原生）。

---

## 二、长上下文自动压缩

### 触发条件
- 以「上一次请求服务端回报的上下文 token」为准：`input_tokens + cache_read_input_tokens + cache_creation_input_tokens`；
- 若没有回报（如刚导入），退化为本地估算（约 1.6 字符/token，含系统开销）；
- 两者取较大值 ≥ `128000 × 95% = 121600` 时，在**下一次发送前**触发压缩。

### 压缩方式
1. 只处理「用户提问 + 可用回答」配对，**保留最近 50 轮原文**（1 轮 = 提问 + 回答）；
2. 更早的对话交给同一个模型（`deepseek-flash`，关闭思考、不启用工具）压缩成中文摘要，**上限 18000 字**（保留目标、事实、数据、决定、约束、未完成事项，不编造）；
3. 摘要作为 `system` 的第二块随请求发送，prompt caching 断点移到摘要块之后；
4. 摘要写入会话（`Session.contextSummary`），后续请求复用，不会每轮重复压缩。

### 提示与交互
- 自动压缩时输入框上方显示「正在压缩上下文，请稍候…」进度提示，并弹 toast；
- 顶栏「压缩上下文」按钮可手动压缩当前对话（不必等 95%）；
- 列表顶部显示「更早 N 轮对话已压缩为摘要 · 点击查看」，可查看摘要或取消压缩；
- 压缩期间禁止发送 / 重新生成 / 编辑。

### 安全设计（防 bug）
- **非破坏性**：原始消息一条都不删不改，只额外存摘要；取消压缩即恢复全量上下文；
- **指纹校验**：摘要绑定截断点消息 id + 前缀 SHA-256；分支切换、编辑、删除导致前缀变化时摘要自动失效，回退全量发送；
- **失败兜底**：压缩请求失败、返回空、或不足 50 轮无法压缩 → 按原样发送，不写任何数据；
- **并发保护**：手动压缩写回前校验可见消息未变化，否则放弃写回。

### 涉及文件
- 新增 `data/ContextCompressor.kt`（纯逻辑：配对、裁剪、指纹、阈值）
- 新增 `net/SummaryApi.kt`（压缩请求）
- `data/Models.kt`：新增 `ContextSummary`、`Session.contextSummary`、`Session.lastInputTokens`（均有默认值）
- `net/RequestBuilder.kt`：`buildBody` 增加可选 `summary`，system 块与缓存断点调整
- `ui/ChatViewModel.kt`：发送前检查与压缩、token 回报记录、手动压缩 / 取消
- `ui/ChatScreen.kt`：压缩按钮、压缩中提示、摘要提示条与查看弹窗

### 已知边界
- 上下文上限按 128K 常量估算，若模型上限变化需改 `ContextCompressor.CONTEXT_LIMIT_TOKENS`；
- 压缩请求本身要携带接近上限的历史，若服务端此时报超长，会回退为原样发送（与旧行为一致）；
- 不足 50 轮的对话不会自动压缩，手动点击会提示「不足 50 轮」；
- 取消压缩后，若下一轮仍超过阈值，会再次自动压缩；
- 未在真机验证摘要质量与极端长会话下的耗时。

---

## 三、测试与构建

- 单测：`./gradlew testDebugUnitTest` 通过（新增 7 个用例，覆盖配对、裁剪、指纹失效、阈值、估算、长度截断）
- 构建：JDK 17 + Android SDK 35 + AGP 8.11.1 + Gradle 8.14，`assembleRelease`（R8）成功，APK 约 3.6 MB
- 共存测试包：`./gradlew assembleRelease -PappIdSuffix=.test`（包名 `com.dschat.app.test`）

## 四、回滚

改动均可按文件回滚；`Session` 新字段有默认值，回退旧版本后旧代码会忽略它们，不影响读取。
完整 diff 见 `changes-0.4.5.diff`。
