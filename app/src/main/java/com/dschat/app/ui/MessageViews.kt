package com.dschat.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dschat.app.data.ChatMessage
import com.dschat.app.data.ReplyBlock
import com.dschat.app.ui.theme.LocalDsColors

/** 用户消息：右对齐气泡，圆角 22dp，padding 10x16，16sp/24，blue-50 背景（原版 UserMessage） */
@Composable
fun UserMessageView(message: ChatMessage) {
    val ds = LocalDsColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (message.files.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                message.files.forEach { f ->
                    Box(
                        modifier = Modifier
                            .background(ds.tag, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            f.name,
                            fontSize = 12.sp,
                            color = ds.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(ds.bubble, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                message.content,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = ds.labelPrimary,
            )
        }
    }
}

/** 等待中的三个渐隐圆点（原版 AssistantMessage.waiting） */
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
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, delayMillis = i * 100, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier.size(6.dp).alpha(alpha).background(ds.labelDimmed, CircleShape),
            )
        }
    }
}

/** 思考区：标题行 + 左侧竖线 + 14sp 次要色内容（原版 ReplyArea + thinking.markdown.css） */
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
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                tint = if (thinkingNow) ds.brand else ds.labelTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                fontSize = 16.sp,
                lineHeight = 28.sp,
                color = ds.labelSecondary,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = ds.labelTertiary,
                modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded || thinkingNow) {
            Box(modifier = Modifier.fillMaxWidth().padding(start = 7.dp)) {
                // 左侧 1px 竖线
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .width(1.dp)
                        .height(if (block.thinking.length > 200) 200.dp else 40.dp)
                        .background(ds.labelDimmed),
                )
                Box(Modifier.padding(start = 15.dp, top = 5.dp, bottom = 5.dp)) {
                    MarkdownText(
                        markdown = block.thinking,
                        textColor = ds.labelSecondary.toArgb(),
                        linkColor = ds.brandText.toArgb(),
                        textSizeSp = 14f,
                    )
                }
            }
        }
    }
}

/** 搜索步骤行：圆点 + 「搜索到 N 个网页」，可展开来源（原版 ReplyArea.row / summary） */
@Composable
fun SearchSection(block: ReplyBlock.Search) {
    val ds = LocalDsColors.current
    var expanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val searching = block.status == "WIP"

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .clickable(enabled = block.results.isNotEmpty()) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(if (searching) ds.brand else ds.labelTertiary, CircleShape),
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = ds.labelSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    searching -> "正在搜索"
                    block.status == "FAILED" -> "联网搜索暂不可用"
                    block.results.isEmpty() -> "未搜索到相关内容"
                    else -> "搜索到 ${block.results.size} 个网页"
                },
                fontSize = 14.sp,
                lineHeight = 24.sp,
                color = ds.labelSecondary,
            )
            if (block.results.isNotEmpty()) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
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
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = ds.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 助手消息：思考/搜索步骤 + Markdown 正文 + 错误提示 + 操作行 */
@Composable
fun AssistantMessageView(
    message: ChatMessage,
    isLast: Boolean,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onCopied: () -> Unit,
) {
    val ds = LocalDsColors.current
    val clipboard = LocalClipboardManager.current
    val streaming = message.status == "streaming" || message.status == "pending"
    val canContinue = isLast && !streaming &&
        (message.status == "stopped" || (message.status == "done" && message.stopReason == "max_tokens")) &&
        message.stopReason != "refusal" &&
        message.blocks.none { it is ReplyBlock.Text && it.isTemplate }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        if (streaming && message.blocks.isEmpty()) {
            WaitingDots()
        }

        message.blocks.forEach { block ->
            when (block) {
                is ReplyBlock.Thinking -> ThinkingSection(block, streaming)
                is ReplyBlock.Search -> SearchSection(block)
                is ReplyBlock.Text -> {
                    if (block.isTemplate) {
                        // 模板/拒绝提示：红字 + 感叹号（原版 notice）
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                null,
                                tint = ds.error,
                                modifier = Modifier.size(16.dp).padding(top = 3.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                block.text,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                color = ds.error,
                            )
                        }
                    } else if (block.text.isNotEmpty()) {
                        MarkdownText(
                            markdown = block.text,
                            textColor = ds.labelPrimary.toArgb(),
                            linkColor = ds.brandText.toArgb(),
                            textSizeSp = 16f,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }

        // 错误提示 + 重试
        if (message.status == "error" && message.errorText != null) {
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.ErrorOutline, null, tint = ds.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    message.errorText,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = ds.error,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (message.errorRetryable) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "重试",
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = ds.brandText,
                        modifier = Modifier.clickable(onClick = onRetry),
                    )
                }
            }
        }

        // 底部操作：复制 / 继续生成
        if (!streaming && (message.replyText.isNotEmpty() || message.status == "error")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.replyText.isNotEmpty()) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(message.replyText))
                        onCopied()
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.ContentCopy, "复制", tint = ds.labelTertiary, modifier = Modifier.size(16.dp))
                    }
                    if (message.status == "stopped") {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.StopCircle, null, tint = ds.labelCaption, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("已停止", fontSize = 12.sp, color = ds.labelCaption)
                    }
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