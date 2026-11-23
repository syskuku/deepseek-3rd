package com.dschat.app.ui

import android.webkit.WebView
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dschat.app.ui.theme.LocalDsColors
import org.json.JSONObject

/** 富文本分段：普通 Markdown 与 mermaid 代码块交替 */
data class RichSegment(val isMermaid: Boolean, val content: String)

private val MERMAID_REGEX = Regex("```mermaid[ \\t]*\\r?\\n([\\s\\S]*?)```")

fun splitRichContent(text: String): List<RichSegment> {
    val segments = ArrayList<RichSegment>()
    var last = 0
    for (match in MERMAID_REGEX.findAll(text)) {
        if (match.range.first > last) {
            segments += RichSegment(false, text.substring(last, match.range.first))
        }
        segments += RichSegment(true, match.groupValues[1].trim())
        last = match.range.last + 1
    }
    if (last < text.length) segments += RichSegment(false, text.substring(last))
    return segments.filter { it.content.isNotBlank() || it.isMermaid }
}

/** 助手正文：Markdown 段 + Mermaid 图表卡片 */
@Composable
fun RichContent(
    text: String,
    textColor: Int,
    linkColor: Int,
    modifier: Modifier = Modifier,
) {
    val segments = remember(text) { splitRichContent(text) }
    Column(modifier = modifier) {
        segments.forEach { seg ->
            if (seg.isMermaid) {
                MermaidCard(seg.content)
            } else {
                MarkdownText(seg.content, textColor, linkColor, Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

/** Mermaid 卡片：代码预览 + 查看图表（内置 mermaid.min.js 离线渲染） */
@Composable
fun MermaidCard(code: String) {
    val ds = LocalDsColors.current
    var show by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(ds.codeBlock, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountTree, null, tint = ds.brand, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("图表", fontSize = 13.sp, color = ds.labelSecondary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { show = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                Text("查看图表", fontSize = 13.sp)
            }
        }
        Text(
            code.lines().take(6).joinToString("\n"),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = ds.labelTertiary,
            maxLines = 6,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (show) MermaidDialog(code, onDismiss = { show = false })
}

@Composable
private fun MermaidDialog(code: String, onDismiss: () -> Unit) {
    val ds = LocalDsColors.current
    val html = remember(code, ds.isDark) {
        val theme = if (ds.isDark) "dark" else "default"
        val bg = if (ds.isDark) "#1B1B1C" else "#FFFFFF"
        val fg = if (ds.isDark) "#F9FAFB" else "#0F1115"
        """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html,body{margin:0;padding:12px;background:$bg;color:$fg;font-family:sans-serif}
          #out{display:flex;justify-content:center;overflow:auto}
          #out svg{max-width:100%;height:auto}
          #err{color:#F25A5A;font-size:13px;white-space:pre-wrap}
        </style></head><body>
        <div id="out"></div><div id="err"></div>
        <script src="mermaid.min.js"></script>
        <script>
          mermaid.initialize({startOnLoad:false, theme:'$theme', securityLevel:'loose', fontFamily:'sans-serif'});
          var code = ${JSONObject.quote(code)};
          mermaid.render('dsmermaid', code).then(function(r){
            document.getElementById('out').innerHTML = r.svg;
          }).catch(function(e){
            document.getElementById('err').textContent = '渲染失败：' + (e && e.message ? e.message : e);
          });
        </script></body></html>
        """.trimIndent()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("图表") },
        text = {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 480.dp)) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            settings.domStorageEnabled = true
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                        }
                    },
                    update = { it.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}