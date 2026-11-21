# AGENTS.md — DeepSeek(3rd) 维护指南与踩坑总结

非官方 DeepSeek 聊天 Android 客户端（Kotlin + Jetpack Compose），移植自官方 `deepseek-ai/chat-demo`（MIT）。
本文件沉淀本项目反复踩过的坑与既定约定，**接手前必读**，避免重犯。

---

## 0. 发布纪律（最重要，用户多次强调）

1. **先验证、再确认、后发布**。任何改动必须真机/模拟器 + 真实 API Key 验证，并**经用户明确确认（"发版/可以发"）后才发布**。严禁未验证就发、严禁猜诊断就发。
2. **绝不覆盖用户已填的真实 API Key**。debug 旁路 `--ez bypass_key true` 只在 `apiKey` 为空时写入测试值（见 `MainActivity`）。
3. 更新说明**默认短**（约 20 字，如"修复bug和新功能"），不写细节、不写"确认无bug"。个别版本用户允许写长时才写长。
4. 发布三处保持一致：国内云 `update.json`+APK、CF 官网、GitHub Release。发完核验 version/sha/size 一致。
5. 误发的版本要**删 Release**（`gh release delete vX --yes --cleanup-tag`）并改正官网/云清单说法。

## 1. 构建 / 发布命令

- JDK17: `C:\Users\OMEN\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2`；ANDROID_HOME: `C:\Users\OMEN\AppData\Local\Android\Sdk`。
- 构建: `.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug assembleRelease`（workdir `D:\dschat\DeepSeekChat`）。
- 版本: `app/build.gradle.kts` versionCode/versionName 递增；同步 `SettingsScreen` 版本文案。
- 发布脚本（**含密钥，只在 `D:\dschat` 本地，绝不进仓库**）:
  - `publish-cloud.ps1 -Version X -Notes "…" -Apk … -VersionCode N`（传 APK+update.json 到七牛 S3，url 指向 Worker 域名，并拷贝清单给 Worker 内嵌）
  - `build-worker.mjs`（node 生成 worker.mjs）→ `deploy-cf.ps1`（复用已有 Worker `deepseek3rd`，**不要新建**）
  - `publish-github.ps1`（REST 推源码，blob 上传带重试）→ `publish-release.ps1 -Version X`
- node: `C:\Users\OMEN\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe`；python 同目录 `python\python.exe`。
- adb: `…\Sdk\platform-tools\adb.exe`。截图用 `shell screencap -p /sdcard/x.png` + `pull`（**不要** exec-out 重定向）。

## 2. 网络 / 分发坑

- **七牛 S3 直连不稳定，必须走 CF**。对象存 S3，对外下载/清单走 Worker 域名 `ds.imikufans.com`（Worker 服务端 SigV4 代取 S3）。
- `kodo.imikufans.com` CDN 套 Cloudflare **bot 人机验证**，okhttp/机房 IP 直接被拦。解法：给 `kodo…/ds.imikufans.com/ds3rd/*` 挂 **Worker 路由**（Worker 先于 bot 执行），旧版硬编码 kodo 也能拿到清单/下载。
- Worker 对**未知路径返回首页 HTML**！新增静态资源（如 whale.png）必须**既上传 S3 又加 Worker 路由**，否则返回 HTML（魔数 `3C21`）而非文件。PNG 魔数 `8950`、ZIP/APK `504B` 用来验真。
- S3 SigV4 用**原始字节 HMAC 链**（bat 里的 hex 链是错的）；path-style URL 需 `/imikufans/` 前缀。
- 更新检查顺序：Worker 清单 → kodo 清单 → GitHub；OkHttp 加**浏览器 UA 拦截器**防 bot 拦截。
- CF 部署 metadata 用 `@file` 保 JSON 引号；成功校验用 `-notmatch '"success":\s*true'`。GitHub blob 偶发 EOF/TLS 超时→**重试**。
- 账号有 `imikufans.com` zone 但 token **无 WAF/bot 编辑权**，别试图关 bot 验证，走 Worker 路由。

## 3. 数据 / 导入坑

- 官方导出 ZIP 的图片是 **STORED+EXT**：必须用 `java.util.zip.ZipFile`（读中央目录），**ZipInputStream 在 Android 读 0 字节**（曾致 `'file' is empty`）。
- 导入附件 `serverId=null`，首次发送才补传 → 大会话（上百图）首send 很慢，属正常；**不要**为此截断上下文。
- **上下文与官方 demo 一致：全量可用轮次，不截断**（曾误加截断被用户要求 revert）。
- **prompt caching**：须显式加 `cache_control:{type:ephemeral}` 断点（system 块数组 + 末轮助手末块），0.4.4 真机长会话实测 cacheRead=34944>0 确认有效。短会话（前缀<阈值）cacheRead=0 属正常。前缀保持稳定（system 常量、latest_reminder 放历史之后）。用 AppLog usage 埋点核对；验证用真实 Key 连发两轮看 cache_read。
- 消息树：流式回写**保留 parentId/selectedChildId**；新增用 `appendRound`；否则回复不可见/错误分叉。
- 个别图片上传失败→**跳过该图不阻断发送**（`localPath=null` 排除出请求）。
- **单对话 JSON**（`SessionJson`）：导出 `{format:"ds3rd-session",session,attachments:base64}`；导入**合并为新会话**（新 id、附件落盘、serverId 置空），不覆盖。入口：抽屉「导出为 JSON」、设置「导入单个对话(JSON)」。
- **反馈邮件**（`FeedbackHelper.emailFeedback(context, sessionFiles)`）：可勾选问题会话，`ACTION_SEND_MULTIPLE` 附日志+会话 JSON。
- **长按用户消息复制**：`UserMessageView` `combinedClickable`（需 `@OptIn(ExperimentalFoundationApi)`）+ 剪贴板 + Toast。

## 4. 渲染 / 媒体坑

- **回复正文改用内置 KaTeX WebView 渲染**（`RichMarkdown` + `assets/render.html` + markdown-it + KaTeX + mermaid），对齐官方，支持 array/颜色/中文/复杂块。JLatexMath(Markwon) 只用于思考块。
- **markdown-it 会转义 `\\` 破坏 LaTeX**：render.html 必须先把公式段（$$/$/\(/\[）stash 保护，markdown 渲染后再回填给 KaTeX auto-render。
- **KaTeX 方言差异**（`MathNormalizer.normalizeForKatex`）：模型可能输出 `\bgcolor/\fgcolor`→映射 `\colorbox/\textcolor`；`\scalebox` KaTeX 不支持→解包保留内容；颜色裸 hex 需补 `#`；`\(...\)`→`$`、`\[...\]`→`$$` 前置转换。
- WebView 高度由页面 `Android.setHeight` 回传自适应；`setTheme`/`renderMarkdown` 只能在 `onPageFinished` 后调用（否则 ReferenceError）。
- **每条回复只建一个 WebView**：模型搜索穿插会产生多个 Text 块，若每块各建 WebView 会 OOM 闪退（导入/聊天崩溃）。`AssistantMessageView` 须把非模板 Text 块合并成一段再交给单个 `RichMarkdown`。
- **长图**：Files API 能传但模型拒收超长图（`unsupported image`）→**上传前缩到 ≤4096**（`ImageResize`）。全屏看长图也要限制解码尺寸（超 Android 纹理上限会崩）。
- **公式（JLatexMath 路径）**：只认 `$$`；转换 `\(...\)`、`\[...\]`、单 `$`；裸公式行包裹且去首尾 `$$` 防漏；`\fcolorbox/\colorbox/\textcolor`→`\fgcolor/\bgcolor`；`[HTML]/[rgb]` 与 `#hex`；基线反射 `ALIGN_BASELINE`。
- **TTS 爆音**：PCM 16-bit 奇数字节跨块错位→carry 尾字节、只写偶数字节（`TtsPlayer`）。
- **滚动贴底**：`lastTick` 必须计入 **Thinking 块长度**，否则思考阶段不跟随（`ChatScreen`）。

## 5. 工具 / 环境坑

- PowerShell 5.1：`Get-Content -Raw` 把 UTF-8-no-BOM 当 ANSI→**中文乱码**；改文件用 edit 工具或 `[IO.File]::WriteAllText(…,UTF8-no-BOM)`；`Set-Content -Encoding UTF8` 带 BOM 会坏 gh JSON。
- 模拟器：首帧白等 ~6s；点击偶尔需重试；debug 与 release 签名冲突→先 `uninstall` 再装。
- `adb shell input text` 对 `+ = 空格` 等特殊字符会截断→测试输入用纯 ASCII 无空格。
- 真实报错要靠**真实 Key 调 API 复现**（如 unsupported image），不要凭空猜。

## 6. 验证清单（发布前）

- [ ] `testDebugUnitTest` 全过。
- [ ] 真机/模拟器 + 真实 Key：发送、思考贴底、长图会话不报错、公式无漏 `$$`。
- [ ] 三处 version/sha/size 一致；logo/静态资源魔数验真。
- [ ] 用户明确确认后才执行发布脚本。