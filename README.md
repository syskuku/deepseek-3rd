# DeepSeek(3rd)

非官方 DeepSeek 聊天 Android 客户端，使用 **Kotlin + Jetpack Compose** 原生实现，基于 DeepSeek 官方 [chat-demo](https://github.com/deepseek-ai/chat-demo)（MIT）移植。

> ⚠️ 本项目为社区第三方移植，与 DeepSeek 官方无关。应用内「设置 → 关于与开源许可」包含完整归属与许可声明。

## 功能

- **首次启动引导填入 API Key**：通过 `GET /models` 免费校验后保存（Android Keystore 加密存储，`EncryptedSharedPreferences`）
- **流式聊天**：对接官方 demo 同款 Anthropic 兼容接口 `POST https://api.deepseek.com/anthropic/v1/messages`，模型 `deepseek-flash`，SSE 逐块渲染
- **附件上传**：图片 / PDF / Office / 文本代码，对接官方 Files API（purpose 映射、64/32MB 限制、file_id 失效自动重传），缩略图展示
- **消息分支切换**：重新生成产生兄弟分支，‹ 1/2 › 切换版本；导入导出保留完整消息树
- **深度思考**：`thinking: enabled/disabled` 开关，思考过程可折叠展示，显示「已思考（用时 X 秒）」
- **智能搜索**：`deepseek_web_search` 工具，展示搜索步骤与来源网页
- **KaTeX 公式 · Mermaid 图表**：JLatexMath 离线公式渲染；Mermaid 运行时内置，离线渲染图表
- **自动标题**：首条回复由服务端生成会话标题（`title.enabled`）
- **会话管理**：侧栏按 今天 / 昨天 / 7 天内 / 30 天内 / 更早 分组，新建、重命名、删除、删除全部
- **停止生成 / 重试 / 继续生成**；错误分类提示（Key 无效 401 / 余额不足 402 / 限流 429 / 服务器繁忙 5xx / 网络异常），可重试错误自动退避重试
- **Markdown 渲染**（Markwon：表格、删除线、链接、公式）
- **历史导入 / 导出**：兼容 DeepSeek 官方 `export_format_version: 3` ZIP 归档（完整消息树 + 图片原件），导入前确认覆盖，导出文件名 `deepseek_data-日期.zip`，不含 API Key
- **浅色 / 深色 / 跟随系统**主题，设计令牌逐行对照原版 `theme.css`
- **智能自动滚动**：流式输出仅在底部时跟随，手动上滑不被打断；离底时显示回底箭头
- **应用内更新**：启动静默检查 GitHub 最新 Release（设置 → 关于 → 检查更新），弹窗下载并调起系统安装器覆盖升级，直连失败自动切换镜像
- 拒绝回复（`refusal`）按协议丢弃正文与思考，展示模板提示

## 与原版差异（v0.2.0）

- 不含语音输入/朗读、消息编辑（重新生成已支持分支）
- 历史上下文不回传 `read_link` 签名块（原版用于恢复网页上下文）
- 界面语言为简体中文

## 测试

```sh
./gradlew testDebugUnitTest    # 归档往返/分支树/请求格式/附件规则 单元测试
```

## 构建

环境要求：JDK 17、Android SDK（compileSdk 35）、Gradle 8.14（wrapper 已内置）。

```sh
./gradlew assembleDebug        # 调试包
./gradlew assembleRelease      # 发布包（当前使用 debug 签名，便于直接安装；正式发布请替换签名）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。minSdk 26（Android 8.0+）。

在 `local.properties` 中配置 `sdk.dir` 指向你的 Android SDK。

## 分发与许可

本项目按 **MIT License** 分发（Copyright (c) 2026 DeepSeek，见 [LICENSE](LICENSE)），并遵循原版 README 的要求：

> The build copies the license to dist/LICENSE; include it when distributing the app. Third-party dependencies are subject to their own licenses.

- LICENSE 已随仓库提供，并打包进 APK `assets/LICENSE`，应用内可查看全文
- 第三方组件：OkHttp/Okio、kotlinx.serialization/coroutines、Markwon、AndroidX/Jetpack Compose、Tink（均为 Apache-2.0），许可清单见应用内「设置 → 关于与开源许可」

## 隐私

- API Key 仅存于本机加密存储，聊天请求直连 `api.deepseek.com`，不经过任何第三方服务器
- 会话数据保存在应用私有目录（`filesDir/sessions`），卸载即清除
- 导出的 ZIP 不包含 API Key 与设置