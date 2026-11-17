package com.dschat.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dschat.app.data.SessionMeta
import com.dschat.app.ui.theme.LocalDsColors
import java.util.Calendar

/** 会话分组：今天 / 昨天 / 7 天内 / 30 天内 / 更早（原版 sessionGroups） */
private fun groupLabel(updatedAt: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = updatedAt }
    val startOfToday = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val days = ((startOfToday.timeInMillis - updatedAt) / 86_400_000L).toInt()
    return when {
        updatedAt >= startOfToday.timeInMillis -> "今天"
        days < 1 -> "昨天"
        days < 7 -> "7 天内"
        days < 30 -> "30 天内"
        else -> "更早"
    }
}

/**
 * 侧栏抽屉，对照原版 Sider.module.css（mobile 300dp）：
 * 侧栏底色 bluish-50、应用名品牌色 20sp/600、新对话白色胶囊按钮、
 * 分组标题 14sp 次要色、选中项 blue-100、底部设置入口。
 */
@Composable
fun SessionDrawerContent(
    sessions: List<SessionMeta>,
    activeId: String?,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val ds = LocalDsColors.current
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<SessionMeta?>(null) }
    var deleteFor by remember { mutableStateOf<SessionMeta?>(null) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(ds.sidebarFill)
            .padding(horizontal = 12.dp),
    ) {
        // 顶部应用名
        Box(modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                "DeepSeek(3rd)",
                fontSize = 20.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = ds.brand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        // 新对话按钮：白色胶囊 + 阴影
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(ds.layer3, RoundedCornerShape(100.dp))
                .clickable(onClick = onNewChat)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Add, null, tint = ds.labelPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("开启新对话", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ds.labelPrimary)
        }
        Spacer(Modifier.height(16.dp))

        // 分组会话列表
        val grouped = sessions.groupBy { groupLabel(it.updatedAt) }
        val order = listOf("今天", "昨天", "7 天内", "30 天内", "更早")
        LazyColumn(modifier = Modifier.weight(1f)) {
            order.forEach { label ->
                val items = grouped[label] ?: return@forEach
                item(key = "header-$label") {
                    Text(
                        label,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = ds.labelTertiary,
                        modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(items, key = { it.id }) { meta ->
                    val active = meta.id == activeId
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (active) ds.sidebarActive else androidx.compose.ui.graphics.Color.Transparent,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { onOpen(meta.id) }
                                .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                meta.title.ifBlank { "新对话" },
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                color = ds.labelPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            )
                            IconButton(onClick = { menuFor = meta.id }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.MoreHoriz, "更多", tint = ds.labelTertiary, modifier = Modifier.size(16.dp))
                            }
                        }
                        DropdownMenu(expanded = menuFor == meta.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                leadingIcon = { Icon(Icons.Filled.Edit, null, modifier = Modifier.size(18.dp)) },
                                onClick = { menuFor = null; renameFor = meta },
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = ds.error) },
                                leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = ds.error, modifier = Modifier.size(18.dp)) },
                                onClick = { menuFor = null; deleteFor = meta },
                            )
                        }
                    }
                }
            }
        }

        // 底部设置入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                .clickable(onClick = onSettings)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, null, tint = ds.labelPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("设置", fontSize = 16.sp, lineHeight = 22.sp, color = ds.labelPrimary)
        }
    }

    // 重命名对话框
    renameFor?.let { meta ->
        var text by remember(meta.id) { mutableStateOf(meta.title) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("新对话") },
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(meta.id, text.ifBlank { "新对话" }); renameFor = null }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } },
        )
    }

    // 删除确认
    deleteFor?.let { meta ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("永久删除对话") },
            text = { Text("删除后，该对话及其中上传的文件将不可恢复。确认删除吗？") },
            confirmButton = {
                TextButton(onClick = { onDelete(meta.id); deleteFor = null }) { Text("删除", color = ds.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } },
        )
    }
}