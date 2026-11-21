package com.dschat.app.ui

import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dschat.app.ui.theme.LocalDsColors

/**
 * 圆角链接粘贴卡片 + 「从剪贴板一键读取」按钮，契合输入区设计语言。
 * 用于开屏导入引导与设置页链接导入。
 */
@Composable
fun LinkPasteField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ds = LocalDsColors.current
    val clipboard = LocalClipboardManager.current

    Column(modifier = modifier.fillMaxWidth()) {
        // 一键读取剪贴板
        Surface(
            onClick = {
                val text = clipboard.getText()?.text?.trim()
                if (!text.isNullOrBlank()) onChange(text)
            },
            shape = RoundedCornerShape(16.dp),
            color = ds.ghostActiveFill,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(Icons.Filled.ContentPaste, null, tint = ds.brandText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("从剪贴板一键读取", fontSize = 15.sp, color = ds.brandText)
            }
        }
        Spacer(Modifier.height(10.dp))
        // 圆角可编辑链接卡片
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .border(1.dp, ds.borderL2, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = ds.labelPrimary),
                cursorBrush = SolidColor(ds.brandText),
                maxLines = 4,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text("或手动粘贴下载链接", fontSize = 14.sp, lineHeight = 20.sp, color = ds.labelCaption)
                        }
                        inner()
                    }
                },
            )
        }
    }
}