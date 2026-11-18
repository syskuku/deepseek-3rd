package com.dschat.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dschat.app.data.HistoryArchive
import com.dschat.app.ui.theme.LocalDsColors
import kotlinx.coroutines.launch

/**
 * 设置页，对照原版 SettingsDialog 的「通用设置 / 数据管理」分区，
 * 并按 MIT 许可与原版 README 的分发要求增加「关于与开源许可」。
 */
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val ds = LocalDsColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val theme by vm.theme.collectAsState()
    val busy by vm.busy.collectAsState()

    var showApiKeyEdit by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showDeleteAll by remember { mutableStateOf(false) }
    var showLinkImport by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImportUri = uri
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out -> vm.exportArchive(out) }
                    vm.toast("导出成功")
                } catch (e: Exception) {
                    vm.toast("导出失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    if (showApiKeyEdit) {
        ApiKeyScreen(
            initialKey = vm.currentApiKey(),
            title = "修改 API Key",
            onVerified = { key -> vm.verifyAndSaveApiKey(key); showApiKeyEdit = false; vm.toast("API Key 已更新") },
            onCancel = { showApiKeyEdit = false },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(ds.bgBase)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = ds.labelPrimary)
            }
            Text("设置", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ds.labelPrimary)
            if (busy) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ds.brand)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            SectionTitle("通用设置")
            // 主题
            SettingRow(title = "主题", subtitle = null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("light" to "浅色", "dark" to "深色", "system" to "跟随系统").forEach { (value, label) ->
                        val selected = theme == value
                        Text(
                            label,
                            fontSize = 13.sp,
                            color = if (selected) ds.brandText else ds.labelSecondary,
                            modifier = Modifier
                                .background(
                                    if (selected) ds.ghostActiveFill else ds.tag,
                                    RoundedCornerShape(100.dp),
                                )
                                .clickable { vm.setTheme(value) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            // 自动朗读
            val autoRead by vm.autoRead.collectAsState()
            SettingRow(title = "自动朗读", subtitle = "开启后每次回复自动发声") {
                androidx.compose.material3.Switch(
                    checked = autoRead,
                    onCheckedChange = { vm.setAutoRead(it) },
                )
            }
            // API Key
            SettingRow(
                title = "API Key",
                subtitle = maskKey(vm.currentApiKey()),
                onClick = { showApiKeyEdit = true },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Key, null, tint = ds.labelTertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("修改", fontSize = 14.sp, color = ds.brandText)
                }
            }

            SectionTitle("数据管理")
            SettingRow(
                title = "导入历史对话",
                subtitle = "导入历史对话会覆盖全部本地对话和附件，支持 DeepSeek 导出的 ZIP 文件。",
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
            ) {
                Icon(Icons.Filled.Upload, null, tint = ds.labelTertiary, modifier = Modifier.size(18.dp))
            }
            SettingRow(
                title = "从链接导入",
                subtitle = "粘贴 DeepSeek 官方导出链接，自动下载并导入（同样覆盖本地对话）。",
                onClick = { linkUrl = ""; showLinkImport = true },
            ) {
                Icon(Icons.Filled.Link, null, tint = ds.labelTertiary, modifier = Modifier.size(18.dp))
            }
            SettingRow(
                title = "导出所有历史对话",
                subtitle = "包含所有历史对话；不包含 API Key。",
                onClick = { exportLauncher.launch(HistoryArchive.exportFileName()) },
            ) {
                Icon(Icons.Filled.Download, null, tint = ds.labelTertiary, modifier = Modifier.size(18.dp))
            }
            SettingRow(
                title = "删除所有对话",
                subtitle = "删除后，所有对话都将被清空且无法找回",
                titleColor = ds.error,
                onClick = { showDeleteAll = true },
            ) {
                Icon(Icons.Filled.DeleteOutline, null, tint = ds.error, modifier = Modifier.size(18.dp))
            }

            SectionTitle("关于与开源许可")
            SettingRow(title = "DeepSeek(3rd)", subtitle = "版本 0.3.0 · 第三方 Android 移植，非 DeepSeek 官方产品") {}
            SettingRow(
                title = "检查更新",
                subtitle = "优先国内云，GitHub 兜底",
                onClick = { vm.checkUpdate(manual = true) },
            ) {
                Icon(Icons.Filled.ChevronRight, null, tint = ds.labelTertiary, modifier = Modifier.size(18.dp))
            }
            SettingRow(
                title = "贡献者：Syskuku",
                subtitle = "www.imikufans.com",
                onClick = { uriHandler.openUri("https://www.imikufans.com") },
            ) {
                Icon(Icons.Filled.ChevronRight, null, tint = ds.labelTertiary, modifier = Modifier.size(18.dp))
            }
            SettingRow(
                title = "基于 deepseek-ai/chat-demo",
                subtitle = "MIT License · Copyright (c) 2026 DeepSeek",
                onClick = { uriHandler.openUri("https://github.com/deepseek-ai/chat-demo") },
            ) {
                Icon(Icons.Filled.ChevronRight, null, tint = ds.labelTertiary, modifier = Modifier.size(18.dp))
            }
            SettingRow(
                title = "开源许可",
                subtitle = "MIT 许可证全文与第三方组件许可",
                onClick = { showLicense = true },
            ) {
                Icon(Icons.Filled.ChevronRight, null, tint = ds.labelTertiary, modifier = Modifier.size(18.dp))
            }
            Text(
                "本应用为社区移植作品，与 DeepSeek 官方无关。API Key 仅保存在本机加密存储中，聊天请求直连 api.deepseek.com，不经过任何第三方服务器。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = ds.labelTertiary,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // 导入覆盖确认（原版 historyImportConfirm）
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("确认导入历史对话？") },
            text = { Text("上传的历史对话会覆盖当前所有对话记录，是否确认导入？") },
            confirmButton = {
                TextButton(onClick = {
                    val u = uri
                    pendingImportUri = null
                    scope.launch {
                        try {
                            val count = vm.importArchive {
                                context.contentResolver.openInputStream(u) ?: throw IllegalStateException("无法读取文件")
                            }
                            vm.toast(if (count == 1) "已导入 1 个对话" else "已导入 $count 个对话")
                        } catch (e: Exception) {
                            vm.toast(e.message ?: "文件导入失败，可能是文件格式错误或使用了旧版的对话导出 ZIP 文件，请前往 DeepSeek 重新导出历史对话文件后再次导入。")
                        }
                    }
                }) { Text("确认导入") }
            },
            dismissButton = { TextButton(onClick = { pendingImportUri = null }) { Text("取消") } },
        )
    }

    // 从链接导入
    if (showLinkImport) {
        AlertDialog(
            onDismissRequest = { showLinkImport = false },
            title = { Text("从链接导入历史对话") },
            text = {
                Column {
                    Text(
                        "粘贴 DeepSeek 官方导出的下载链接，将自动下载并导入。导入会覆盖当前所有对话记录。",
                        fontSize = 13.sp, lineHeight = 19.sp, color = ds.labelSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinkPasteField(
                        value = linkUrl,
                        onChange = { linkUrl = it },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = linkUrl.isNotBlank(),
                    onClick = {
                        val u = linkUrl.trim()
                        showLinkImport = false
                        scope.launch {
                            try {
                                val count = vm.importFromUrl(u)
                                vm.toast(if (count == 1) "已导入 1 个对话" else "已导入 $count 个对话")
                            } catch (e: Exception) {
                                vm.toast(e.message ?: "导入失败，请检查链接是否有效")
                            }
                        }
                    },
                ) { Text("下载并导入") }
            },
            dismissButton = { TextButton(onClick = { showLinkImport = false }) { Text("取消") } },
        )
    }

    // 删除所有确认
    if (showDeleteAll) {
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text("删除后，所有对话都将被清空且无法找回") },
            text = { Text("本地保存的附件及未发送的草稿也将被删除。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteAllSessions(); showDeleteAll = false }) { Text("删除", color = ds.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteAll = false }) { Text("取消") } },
        )
    }

    // 开源许可全文
    if (showLicense) {
        LicenseDialog(onDismiss = { showLicense = false })
    }
}

@Composable
private fun SectionTitle(text: String) {
    val ds = LocalDsColors.current
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = ds.labelTertiary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val ds = LocalDsColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ds.layer2, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = titleColor ?: ds.labelPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = ds.labelTertiary)
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
    Spacer(Modifier.height(8.dp))
}

private fun maskKey(key: String): String =
    if (key.length <= 8) "已设置" else key.take(5) + "····" + key.takeLast(4)

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val ds = LocalDsColors.current
    val context = LocalContext.current
    val mitText = remember {
        runCatching { context.assets.open("LICENSE").bufferedReader().readText() }
            .getOrDefault("MIT License · Copyright (c) 2026 DeepSeek")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开源许可") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("本应用基于 DeepSeek chat-demo 移植，按 MIT 许可证分发：", fontSize = 13.sp, color = ds.labelSecondary)
                Spacer(Modifier.height(8.dp))
                Text(
                    mitText,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = ds.labelSecondary,
                    modifier = Modifier.background(ds.tag, RoundedCornerShape(8.dp)).padding(10.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("第三方开源组件", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ds.labelPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "· OkHttp / Okio — Apache License 2.0 (Square, Inc.)\n" +
                        "· kotlinx.serialization / kotlinx.coroutines — Apache License 2.0 (JetBrains)\n" +
                        "· Markwon — Apache License 2.0 (Noties)\n" +
                        "· AndroidX / Jetpack Compose / Material 3 — Apache License 2.0 (The Android Open Source Project)\n" +
                        "· Tink (via androidx.security:security-crypto) — Apache License 2.0 (Google)\n\n" +
                        "Android 平台本身依 Apache License 2.0 授权。各组件完整许可文本见其官方仓库。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = ds.labelSecondary,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}