package com.dschat.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dschat.app.ui.theme.LocalDsColors
import kotlinx.coroutines.launch

/**
 * 开屏第二屏：API Key 确认后询问是否从 DeepSeek 官方导入历史对话。
 * 引导：官方 App 设置 → 数据管理 → 导出所有历史对话 → 复制下载链接，粘贴即可。
 */
@Composable
fun ImportOnboardingScreen(
    onImport: suspend (String) -> Int,
    onDone: () -> Unit,
) {
    val ds = LocalDsColors.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = ds.bgBase) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("从 DeepSeek 导入历史对话", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ds.labelPrimary)
            Spacer(Modifier.height(12.dp))
            Text(
                "对话可与官方 App 无缝衔接，图片附件也能无缝支持。",
                fontSize = 15.sp, lineHeight = 22.sp, color = ds.labelSecondary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Surface(color = ds.tag, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("如何获取导出链接", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ds.labelPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "打开官方 App → 设置 → 数据管理 → 导出所有历史对话 → 复制下载链接，粘贴到下方即可。",
                        fontSize = 13.sp, lineHeight = 20.sp, color = ds.labelSecondary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "提示：若导入的对话包含大量图片，首次回复需要先上传这些图片，可能需要一些时间，请耐心等待。",
                fontSize = 12.sp, lineHeight = 18.sp, color = ds.labelTertiary,
            )
            Spacer(Modifier.height(16.dp))
            LinkPasteField(
                value = link,
                onChange = { link = it; error = null },
            )
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error!!, fontSize = 12.sp, color = ds.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val count = onImport(link.trim())
                            onDone()
                        } catch (e: Exception) {
                            error = e.message ?: "导入失败，请检查链接"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && link.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.height(0.dp))
                    Text("  正在导入...")
                } else {
                    Text("导入历史对话")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDone, enabled = !busy) {
                Text("跳过，稍后在设置中导入", color = ds.labelTertiary)
            }
        }
    }
}

/** 开屏第三屏：引导用户遇到问题去设置→问题反馈，用邮箱发送日志。 */
@Composable
fun FeedbackTipScreen(onDone: () -> Unit) {
    val ds = LocalDsColors.current
    Surface(modifier = Modifier.fillMaxSize(), color = ds.bgBase) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("遇到问题？一键反馈", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ds.labelPrimary)
            Spacer(Modifier.height(12.dp))
            Text(
                "使用中遇到任何 bug，可在「设置 → 帮助与反馈 → 问题反馈」一键导出日志，" +
                    "并在打开方式中选择邮箱软件发送给维护者，帮助我们快速定位修复。",
                fontSize = 15.sp, lineHeight = 22.sp, color = ds.labelSecondary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("开始使用") }
        }
    }
}