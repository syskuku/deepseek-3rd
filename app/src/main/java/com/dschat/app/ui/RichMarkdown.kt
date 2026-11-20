package com.dschat.app.ui

import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dschat.app.ui.theme.LocalDsColors
import org.json.JSONObject

private fun css(c: Color): String {
    val a = c.toArgb()
    return String.format("#%06X", 0xFFFFFF and a)
}

/**
 * 用内置 KaTeX + markdown-it + mermaid 的 WebView 渲染回复，
 * 与官方 demo 一致地支持 array/颜色/scalebox/中文公式等复杂 LaTeX。
 * 高度由页面回传，自适应内容。
 */
@Composable
fun RichMarkdown(markdown: String, modifier: Modifier = Modifier) {
    val ds = LocalDsColors.current
    var heightDp by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    val pending = remember { arrayOfNulls<String>(1) }

    val fg = css(ds.labelPrimary)
    val accent = css(ds.brandText)
    val border = css(ds.borderL2)
    val codeBg = css(ds.tag)

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 4.dp)
            .then(if (heightDp > 0) Modifier.height(heightDp.dp) else Modifier),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.textZoom = 100
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun setHeight(h: Int) {
                        post { if (h > 0) heightDp = h }
                    }
                }, "Android")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded = true
                        view.evaluateJavascript("setTheme('$fg','transparent','$accent','$border','$codeBg')", null)
                        pending[0]?.let { view.evaluateJavascript("renderMarkdown(${JSONObject.quote(MathNormalizer.normalizeForKatex(it))})", null) }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        return true
                    }
                }
                loadUrl("file:///android_asset/render.html")
            }
        },
        update = { wv ->
            if (loaded) {
                wv.evaluateJavascript("setTheme('$fg','transparent','$accent','$border','$codeBg')", null)
                wv.evaluateJavascript("renderMarkdown(${JSONObject.quote(MathNormalizer.normalizeForKatex(markdown))})", null)
            } else {
                pending[0] = markdown
            }
        },
    )
}