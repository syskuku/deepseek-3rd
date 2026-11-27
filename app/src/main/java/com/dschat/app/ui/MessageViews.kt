package com.dschat.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dschat.app.data.ChatMessage
import com.dschat.app.data.ReplyBlock
import com.dschat.app.data.Session
import com.dschat.app.net.AttachmentRules
import com.dschat.app.ui.theme.LocalDsColors
import java.io.File

/** 消息版本切换器（官方 BranchSwitcher）：‹ 2/3 › */
@Composable
fun BranchSwitcher(
    session: Session,
    message: ChatMessage,
    onSwitch: (String) -> Unit,
) {
    val ds = LocalDsColors.current
    val siblings = remember(session.messages, message.id) { session.siblingsOf(message) }
    if (siblings.size <= 1) return
    val index = siblings.indexOfFirst { it.id == message.id }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        IconButton(
            onClick = { if (index > 0) onSwitch(siblings[index - 1].id) },
            enabled = index > 0,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一个版本",
                tint = if (index > 0) ds.labelSecondary else ds.labelDimmed,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            "${index + 1}/${siblings.size}",
            fontSize = 12.sp,
            color = ds.labelTertiary,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        IconButton(
            onClick = { if (index < siblings.lastIndex) onSwitch(siblings[index + 1].id) },
            enabled = index < siblings.lastIndex,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一个版本",
                tint = if (index < siblings.lastIndex) ds.labelSecondary else ds.labelDimmed,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 附件展示：图片缩略图（点击看长图）/ 文档卡片 */
@Composable
fun AttachmentRow(message: ChatMessage, fileOf: (String) -> File) {
    val ds = LocalDsColors.current
    var viewing by remember { mutableStateOf<String?>(null) }
    if (message.files.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        message.files.forEach { f ->
            if (f.isImage && f.localPath != null) {
                AsyncImage(
                    model = fileOf(f.localPath),
                    contentDescription = f.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ds.tag)
                        .clickable { viewing = f.localPath },
                )
            } else {
                Row(
                    modifier = Modifier
                        .background(ds.tag, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Description, null, tint = ds.labelTertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            f.name,
                            fontSize = 12.sp,
                            color = ds.labelPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                        Text(AttachmentRules.formatSize(f.size), fontSize = 10.sp, color = ds.labelTertiary)
                    }
                }
            }
        }
    }

    // 长图全屏可滚动查看（限制解码尺寸，避免超长图超出纹理上限崩溃）
    viewing?.let { path ->
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val request = remember(path) {
            coil.request.ImageRequest.Builder(ctx)
                .data(fileOf(path))
                .size(coil.size.Size(1080, 4096))
                .crossfade(true)
                .build()
        }
        androidx.compose.ui.window.Dialog(onDismissRequest = { viewing = null }) {
            androidx.compose.material3.Surface(
                color = ds.bgBase,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}

/** 用户消息：右对齐气泡 + 附件 + 版本切换 + 点击编辑 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun UserMessageView(
    message: ChatMessage,
    session: Session,
    fileOf: (String) -> File,
    onSwitchVersion: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val ds = LocalDsColors.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        BranchSwitcher(session, message) { onSwitchVersion(it) }
        AttachmentRow(message, fileOf)
        if (message.content.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(ds.bubble, RoundedCornerShape(22.dp))
                    .combinedClickable(
                        onClick = onEdit,
                        onLongClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.content))
                            android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(message.content, fontSize = 16.sp, lineHeight = 24.sp, color = ds.labelPrimary)
            }
        }
    }
}

/** 等待中的三个渐隐圆点 */
@Composable
fun WaitingDots() {
    val ds = LocalDsColors.current
    val transition = rememberInfiniteTransition(label = "waiting")
    Row(
        modifier = Modifier.height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, delayMillis = i * 100, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(Modifier.size(6.dp).alpha(alpha).background(ds.labelDimmed, CircleShape))
        }
    }
}

/** 思考区：标题行 + 左侧竖线 + 次要色内容 */
@Composable
fun ThinkingSection(block: ReplyBlock.Thinking, streaming: Boolean) {
    val ds = LocalDsColors.current
    var expanded by remember { mutableStateOf(false) }
    val seconds = if (block.startedAt != null && block.endedAt != null) {
        maxOf(1, Math.round((block.endedAt - block.startedAt) / 1000.0))
    } else null
    val thinkingNow = streaming && block.endedAt == null
    val title = when {
        thinkingNow -> "正在思考"
        seconds != null -> "已思考（用时 $seconds 秒）"
        else -> "已深度思考"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Psychology, null,
                tint = if (thinkingNow) ds.brand else ds.labelTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 16.sp, lineHeight = 28.sp, color = ds.labelSecondary, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.ExpandMore, if (expanded) "收起" else "展开",
                tint = ds.labelTertiary,
                modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded || thinkingNow) {
            Box(modifier = Modifier.fillMaxWidth().padding(start = 7.dp)) {
                Box(
                    Modifier.align(Alignment.TopStart).width(1.dp)
                        .height(if (block.thinking.length > 200) 200.dp else 40.dp)
                        .background(ds.labelDimmed),
                )
                Box(Modifier.padding(start = 15.dp, top = 5.dp, bottom = 5.dp)) {
                    MarkdownText(block.thinking, ds.labelSecondary.toArgb(), ds.brandText.toArgb(), textSizeSp = 14f)
                }
            }
        }
    }
}

/** 搜索步骤行 */
@Composable
fun SearchSection(block: ReplyBlock.Search) {
    val ds = LocalDsColors.current
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val searching = block.status == "WIP"

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            modifier = Modifier.height(34.dp).clickable(enabled = block.results.isNotEmpty()) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(5.dp).background(if (searching) ds.brand else ds.labelTertiary, CircleShape))
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Search, null, tint = ds.labelSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    searching -> "正在搜索"
                    block.status == "FAILED" -> "联网搜索暂不可用"
                    block.results.isEmpty() -> "未搜索到相关内容"
                    else -> "搜索到 ${block.results.size} 个网页"
                },
                fontSize = 14.sp, lineHeight = 24.sp, color = ds.labelSecondary,
            )
            if (block.results.isNotEmpty()) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = ds.labelTertiary,
                    modifier = Modifier.size(16.dp).rotate(if (expanded) 90f else 0f),
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 22.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                block.results.forEach { r ->
                    Row(
                        modifier = Modifier
                            .background(ds.tag, RoundedCornerShape(6.dp))
                            .clickable { runCatching { uriHandler.openUri(r.url) } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Language, null, tint = ds.labelTertiary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            r.title.ifBlank { r.url },
                            fontSize = 13.sp, lineHeight = 20.sp, color = ds.labelSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp),
                        )
                    }
                }
            }
        }
    }
}

private val MERMAID_FENCE = Regex("(?m)^\\s*```\\s*mermaid")

/**
 * 助手正文渲染：
 * - 普通 Markdown 走原生 Markwon（高度同步测量，滚动不跳、无 WebView 白闪）；
 * - 只有含 ```mermaid 的消息才用 WebView（图表必须网页渲染）。
 * 之前每条回复都建 WebView，异步回传高度会让列表在滚动/流式时不断跳动。
 */
@Composable
private fun AssistantBody(markdown: String) {
    val ds = LocalDsColors.current
    if (MERMAID_FENCE.containsMatchIn(markdown)) {
        RichMarkdown(markdown = markdown, modifier = Modifier.padding(bottom = 6.dp))
    } else {
        MarkdownText(
            markdown = markdown,
            textColor = ds.labelPrimary.toArgb(),
            linkColor = ds.brandText.toArgb(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            textSizeSp = 15f,
        )
    }
}

/** 助手消息：版本切换 + 思考/搜索 + 富文本正文 + 错误 + 操作行 */
@Composable
fun AssistantMessageView(
    message: ChatMessage,
    session: Session,
    isLast: Boolean,
    reading: Boolean,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onRegenerate: () -> Unit,
    onSwitchVersion: (String) -> Unit,
    onCopied: () -> Unit,
    onReadAloud: () -> Unit,
) {
    val ds = LocalDsColors.current
    val clipboard = LocalClipboardManager.current
    val streaming = message.status == "streaming" || message.status == "pending"
    val canContinue = isLast && !streaming &&
        (message.status == "stopped" || (message.status == "done" && message.stopReason == "max_tokens")) &&
        message.stopReason != "refusal" &&
        message.blocks.none { it is ReplyBlock.Text && it.isTemplate }
    val canRegenerate = !streaming && message.status != "refusal"

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        BranchSwitcher(session, message) { onSwitchVersion(it) }

        if (streaming && message.blocks.isEmpty()) WaitingDots()

        // 思考/搜索按序渲染；正文 Text 块合并为一段，只用一个 WebView（避免多 WebView OOM 闪退）
        val textParts = StringBuilder()
        val templates = mutableListOf<String>()
        message.blocks.forEach { block ->
            when (block) {
                is ReplyBlock.Thinking -> ThinkingSection(block, streaming)
                is ReplyBlock.Search -> SearchSection(block)
                is ReplyBlock.Text -> {
                    if (block.isTemplate) templates.add(block.text)
                    else if (block.text.isNotEmpty()) textParts.append(block.text).append("\n\n")
                }
            }
        }
        templates.forEach { t ->
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.ErrorOutline, null, tint = ds.error, modifier = Modifier.size(16.dp).padding(top = 3.dp))
                Spacer(Modifier.width(6.dp))
                Text(t, fontSize = 14.sp, lineHeight = 22.sp, color = ds.error)
            }
        }
        if (textParts.isNotBlank()) {
            AssistantBody(textParts.toString().trim())
        }

        if (message.status == "error" && message.errorText != null) {
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.ErrorOutline, null, tint = ds.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(message.errorText, fontSize = 14.sp, lineHeight = 22.sp, color = ds.error, modifier = Modifier.weight(1f, fill = false))
                if (message.errorRetryable) {
                    Spacer(Modifier.width(8.dp))
                    Text("重试", fontSize = 14.sp, lineHeight = 22.sp, color = ds.brandText, modifier = Modifier.clickable(onClick = onRetry))
                }
            }
        }

        if (!streaming && (message.replyText.isNotEmpty() || message.status == "error")) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (message.replyText.isNotEmpty()) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(message.replyText))
                        onCopied()
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.ContentCopy, "复制", tint = ds.labelTertiary, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onReadAloud, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (reading) Icons.Filled.StopCircle else Icons.Filled.VolumeUp,
                            if (reading) "停止朗读" else "朗读",
                            tint = if (reading) ds.brand else ds.labelTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (canRegenerate) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Refresh, "重新生成", tint = ds.labelTertiary, modifier = Modifier.size(16.dp))
                    }
                }
                if (message.status == "stopped") {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.StopCircle, null, tint = ds.labelCaption, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("已停止", fontSize = 12.sp, color = ds.labelCaption)
                }
                Spacer(Modifier.weight(1f))
                if (canContinue) {
                    Row(
                        modifier = Modifier
                            .background(ds.bgBase, RoundedCornerShape(100.dp))
                            .clickable(onClick = onContinue)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Refresh, null, tint = ds.labelSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("继续生成", fontSize = 14.sp, color = ds.labelPrimary)
                    }
                }
            }
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)