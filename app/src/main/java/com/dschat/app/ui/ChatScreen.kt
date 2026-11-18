package com.dschat.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dschat.app.ui.theme.LocalDsColors
import kotlinx.coroutines.launch
import java.io.File

/** 深度思考 / 智能搜索胶囊开关（原版 .toggle） */
@Composable
private fun ToggleChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val ds = LocalDsColors.current
    Row(
        modifier = Modifier
            .height(34.dp)
            .background(if (selected) ds.ghostActiveFill else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(18.dp))
            .border(1.dp, if (selected) ds.ghostActiveBorder else ds.borderL2, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = if (selected) ds.brandText else ds.labelPrimary, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = if (selected) ds.brandText else ds.labelPrimary)
    }
}

/** 待发送附件条：缩略图/文档卡片 + 移除 */
@Composable
private fun DraftRow(drafts: List<DraftAttachment>, fileOf: (String) -> File, onRemove: (String) -> Unit) {
    val ds = LocalDsColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        drafts.forEach { d ->
            Box {
                if (d.isImage) {
                    AsyncImage(
                        model = fileOf(d.localPath),
                        contentDescription = d.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(ds.tag),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ds.tag)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.Description, null, tint = ds.labelTertiary, modifier = Modifier.size(16.dp))
                        Text(
                            d.name, fontSize = 9.sp, lineHeight = 11.sp, color = ds.labelSecondary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(18.dp)
                        .background(ds.labelSecondary, CircleShape)
                        .clickable { onRemove(d.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, "移除", tint = ds.bgBase, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

/** 输入区（原版 ChatInput）：附件条 + 文本 + 功能行 */
@Composable
fun ChatInputBar(
    streaming: Boolean,
    thinking: Boolean,
    search: Boolean,
    drafts: List<DraftAttachment>,
    fileOf: (String) -> File,
    onToggleThinking: () -> Unit,
    onToggleSearch: () -> Unit,
    onPickFiles: () -> Unit,
    onRemoveDraft: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ds = LocalDsColors.current
    var text by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ds.inputMajor, RoundedCornerShape(24.dp))
            .border(1.dp, ds.borderL2, RoundedCornerShape(24.dp)),
    ) {
        if (drafts.isNotEmpty()) DraftRow(drafts, fileOf, onRemoveDraft)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp, max = 180.dp)
                .padding(start = 16.dp, end = 12.dp, top = 12.dp),
            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = ds.labelPrimary),
            cursorBrush = SolidColor(ds.brandText),
            maxLines = 8,
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("请输入消息", fontSize = 16.sp, lineHeight = 24.sp, color = ds.labelCaption)
                    inner()
                }
            },
        )
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPickFiles, enabled = !streaming, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.AttachFile, "上传文件", tint = if (streaming) ds.labelDimmed else ds.labelSecondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            ToggleChip("深度思考", Icons.Filled.Psychology, thinking, onToggleThinking)
            Spacer(Modifier.width(10.dp))
            ToggleChip("智能搜索", Icons.Filled.Search, search, onToggleSearch)
            Spacer(Modifier.weight(1f))
            if (streaming) {
                Box(
                    modifier = Modifier.size(34.dp).background(ds.brand, CircleShape).clickable(onClick = onStop),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Stop, "停止生成", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
                }
            } else {
                val enabled = text.isNotBlank() || drafts.isNotEmpty()
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(if (enabled) ds.brand else ds.brand.copy(alpha = 0.4f), CircleShape)
                        .clickable(enabled = enabled) { onSend(text); text = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** 主界面：抽屉 + 顶栏 + 消息列表/欢迎页 + 输入区 */
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val ds = LocalDsColors.current
    val sessions by vm.sessions.collectAsState()
    val active by vm.active.collectAsState()
    val streamingId by vm.streamingSessionId.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val search by vm.search.collectAsState()
    val drafts by vm.drafts.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val session = active
    val messages = remember(session) { session?.visibleMessages() ?: emptyList() }
    val streaming = streamingId != null && streamingId == session?.id

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addDrafts(uris)
    }

    // 是否处于底部：决定流式输出时是否自动跟随
    val isAtBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val lastTick = messages.lastOrNull()?.let { it.blocks.size * 100000 + it.replyText.length } ?: 0

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex, Int.MAX_VALUE)
    }
    LaunchedEffect(lastTick) {
        if (messages.isNotEmpty() && isAtBottom) listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = ds.sidebarFill, modifier = Modifier.width(300.dp)) {
                SessionDrawerContent(
                    sessions = sessions,
                    activeId = session?.id,
                    onNewChat = { vm.newChat(); scope.launch { drawerState.close() } },
                    onOpen = { vm.openSession(it); scope.launch { drawerState.close() } },
                    onRename = vm::renameSession,
                    onDelete = vm::deleteSession,
                    onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().background(ds.bgBase)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Filled.Menu, "打开边栏", tint = ds.labelPrimary)
                }
                Text(
                    session?.title?.ifBlank { "新对话" } ?: "新对话",
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ds.labelPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                IconButton(onClick = { vm.newChat() }) {
                    Icon(Icons.Filled.Add, "开启新对话", tint = ds.labelPrimary)
                }
            }

            if (messages.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("今天有什么可以帮到你？", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = ds.labelPrimary)
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                            if (message.role == "user") {
                                UserMessageView(
                                    message = message,
                                    session = session!!,
                                    fileOf = vm::attachmentFile,
                                    onSwitchVersion = { vm.switchVersion(message.id, it) },
                                )
                            } else {
                                AssistantMessageView(
                                    message = message,
                                    session = session!!,
                                    isLast = index == messages.lastIndex,
                                    onRetry = vm::retryLast,
                                    onContinue = vm::continueLast,
                                    onRegenerate = { vm.regenerate(message.id) },
                                    onSwitchVersion = { vm.switchVersion(message.id, it) },
                                    onCopied = { vm.toast("已复制") },
                                )
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isAtBottom && messages.isNotEmpty(),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut(),
                    ) {
                        androidx.compose.material3.Surface(
                            shape = CircleShape, color = ds.layer3, shadowElevation = 4.dp,
                            modifier = Modifier.size(36.dp).clickable {
                                scope.launch { listState.animateScrollToItem(messages.lastIndex, Int.MAX_VALUE) }
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.KeyboardArrowDown, "回到底部", tint = ds.labelSecondary, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp),
            ) {
                ChatInputBar(
                    streaming = streaming,
                    thinking = thinking,
                    search = search,
                    drafts = drafts,
                    fileOf = vm::attachmentFile,
                    onToggleThinking = { vm.setThinking(!thinking) },
                    onToggleSearch = { vm.setSearch(!search) },
                    onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                    onRemoveDraft = vm::removeDraft,
                    onSend = vm::send,
                    onStop = vm::stop,
                )
                Text(
                    "内容由 AI 生成，请仔细甄别",
                    fontSize = 11.sp, lineHeight = 16.sp, color = ds.labelTertiary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}