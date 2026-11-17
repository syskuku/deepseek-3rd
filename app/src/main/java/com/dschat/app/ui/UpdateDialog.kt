package com.dschat.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dschat.app.ui.theme.LocalDsColors

/** 发现新版本：说明 + 下载进度 + 安装 */
@Composable
fun UpdateDialog(vm: ChatViewModel) {
    val info by vm.updateInfo.collectAsState()
    val progress by vm.updateProgress.collectAsState()
    val downloaded by vm.downloadedApk.collectAsState()
    val error by vm.updateError.collectAsState()
    val ds = LocalDsColors.current
    val context = LocalContext.current
    val update = info ?: return

    AlertDialog(
        onDismissRequest = { if (progress == null) vm.dismissUpdate() },
        title = { Text("发现新版本 ${update.tag}") },
        text = {
            Column {
                Column(
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        update.notes.ifBlank { "包含问题修复与体验改进。" },
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = ds.labelSecondary,
                    )
                }
                if (progress != null) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = ds.brand,
                        trackColor = ds.tag,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "正在下载 ${((progress ?: 0f) * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = ds.labelTertiary,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error!!, fontSize = 12.sp, lineHeight = 17.sp, color = ds.error)
                }
            }
        },
        confirmButton = {
            when {
                progress != null -> TextButton(onClick = vm::cancelDownload) { Text("取消下载") }
                downloaded != null -> TextButton(onClick = { vm.installUpdate(context) }) { Text("安装") }
                else -> TextButton(onClick = { vm.downloadUpdate(context) }) { Text("下载并安装") }
            }
        },
        dismissButton = {
            if (progress == null) {
                TextButton(onClick = vm::dismissUpdate) { Text("取消") }
            }
        },
    )
}