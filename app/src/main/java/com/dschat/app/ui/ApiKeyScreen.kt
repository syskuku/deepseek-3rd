package com.dschat.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dschat.app.net.ApiError
import com.dschat.app.net.classifyError
import com.dschat.app.net.ApiCondition
import kotlinx.coroutines.launch

/** 首次启动 / 更换 Key：输入并校验 DeepSeek API Key */
@Composable
fun ApiKeyScreen(
    initialKey: String = "",
    title: String = "输入 API Key 开始使用",
    onVerified: suspend (String) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var key by remember { mutableStateOf(initialKey) }
    var visible by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("DeepSeek(3rd)", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("请输入 API Key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!verifying && key.isNotBlank()) {
                        scope.launch {
                            verifying = true
                            error = null
                            try {
                                onVerified(key.trim())
                            } catch (e: ApiError) {
                                error = when (classifyError(e)) {
                                    ApiCondition.UNAUTHORIZED -> "API Key 无效，请检查后重试"
                                    ApiCondition.NETWORK -> "网络异常，请检查你的网络状况"
                                    else -> "校验失败：${e.message}"
                                }
                            } catch (e: Exception) {
                                error = "校验失败：${e.message ?: "未知错误"}"
                            } finally {
                                verifying = false
                            }
                        }
                    }
                }),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) "隐藏" else "显示",
                        )
                    }
                },
                isError = error != null,
                supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    scope.launch {
                        verifying = true
                        error = null
                        try {
                            onVerified(key.trim())
                        } catch (e: ApiError) {
                            error = when (classifyError(e)) {
                                ApiCondition.UNAUTHORIZED -> "API Key 无效，请检查后重试"
                                ApiCondition.NETWORK -> "网络异常，请检查你的网络状况"
                                else -> "校验失败：${e.message}"
                            }
                        } catch (e: Exception) {
                            error = "校验失败：${e.message ?: "未知错误"}"
                        } finally {
                            verifying = false
                        }
                    }
                },
                enabled = !verifying && key.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (verifying) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("正在验证...")
                } else {
                    Text("确定")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            if (onCancel != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel) { Text("取消") }
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { uriHandler.openUri("https://platform.deepseek.com/api_keys") }) {
                Text("前往 DeepSeek 开放平台获取 API Key", fontSize = 13.sp)
            }
            Text(
                "API Key 仅保存在本机加密存储中，请求直连 api.deepseek.com",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}