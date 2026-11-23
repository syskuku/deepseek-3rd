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
 *
 * 性能约定（长会话卡顿修复）：
 * - 只有正文变化才重新渲染；重组（滚动/流式/主题）不再全量重排；
 * - 高度只在真的变化时回写，避免「高度变化 → 重组 → 再渲染」循环；
 * - 离开组合时销毁 WebView，防止滚动来回累积 WebView 泄漏。
 */
@Composable
fun RichMarkdown(markdown: String, modifier: Modifier = Modifier) {
    val ds = LocalDsColors.current
    var heightDp by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    val pending = remember { arrayOfNulls<String>(1) }
    val rendered = remember { arrayOfNulls<String>(1) }
    val themeKey = remember { arrayOfNulls<String>(1) }

    val fg = css(ds.labelPrimary)
    val accent = css(ds.brandText)
    val border = css(ds.borderL2)
    val codeBg = css(ds.tag)
    val themeJs = "setTheme('$fg','transparent','$accent','$border','$codeBg')"
    val themeSignature = "$fg|$accent|$border|$codeBg"

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 4.dp)
            .then(if (heightDp > 0) Modifier.height(heightDp.dp) else Modifier),
        onRelease = { wv ->
            runCatching {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            }
        },
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
                        post { if (h > 0 && h != heightDp) heightDp = h }
                    }
                }, "Android")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded = true
                        themeKey[0] = themeSignature
                        view.evaluateJavascript(themeJs, null)
                        val text = pending[0]
                        if (text != null) {
                            val js = MathNormalizer.normalizeForKatex(text)
                            rendered[0] = js
                            view.evaluateJavascript("renderMarkdown(${JSONObject.quote(js)})", null)
                        }
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
            if (!loaded) {
                pending[0] = markdown
                return@AndroidView
            }
            if (themeKey[0] != themeSignature) {
                themeKey[0] = themeSignature
                wv.evaluateJavascript(themeJs, null)
            }
            val js = MathNormalizer.normalizeForKatex(markdown)
            if (rendered[0] != js) {
                rendered[0] = js
                wv.evaluateJavascript("renderMarkdown(${JSONObject.quote(js)})", null)
            }
        },
    )
}
