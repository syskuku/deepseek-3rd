package com.dschat.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Markdown 渲染（Markwon TextView）：表格、删除线、链接、KaTeX 风格公式（JLatexMath 离线渲染）。
 * 流式输出时按文本变化刷新。
 */
@SuppressLint("RememberReturnType")
@Composable
fun MarkdownText(
    markdown: String,
    textColor: Int,
    linkColor: Int,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 16f,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextIsSelectable(true)
                textSize = textSizeSp
                setLineSpacing(0f, 1.25f)
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                tag = ""
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            if (view.tag != markdown) {
                view.tag = markdown
                markwon(view.context, textSizeSp).setMarkdown(view, normalizeMath(markdown))
                alignMathBaseline(view.text)
            }
        },
    )
}

/** 公式适配委托给纯逻辑 MathNormalizer（定界符转换 + 颜色命令映射），便于单元测试。 */
private fun normalizeMath(md: String): String = MathNormalizer.normalize(md)

/** 行内公式默认 ALIGN_BOTTOM 显得偏下，反射改为 ALIGN_BASELINE 与文字基线对齐。 */
private fun alignMathBaseline(text: CharSequence) {
    if (text !is android.text.Spanned) return
    val spans = text.getSpans(0, text.length, io.noties.markwon.image.AsyncDrawableSpan::class.java)
    if (spans.isEmpty()) return
    try {
        val f = io.noties.markwon.image.AsyncDrawableSpan::class.java.getDeclaredField("alignment")
        f.isAccessible = true
        for (s in spans) runCatching { f.setInt(s, io.noties.markwon.image.AsyncDrawableSpan.ALIGN_BASELINE) }
    } catch (_: Throwable) {
        // 反射失败则保持默认对齐，不影响渲染
    }
}

private var cached: Markwon? = null
private var cachedContext: Context? = null
private var cachedSize: Float = -1f

private fun markwon(context: Context, textSizeSp: Float): Markwon {
    val app = context.applicationContext
    if (cached == null || cachedContext !== app || cachedSize != textSizeSp) {
        val px = textSizeSp * app.resources.displayMetrics.scaledDensity
        cached = Markwon.builder(app)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(app))
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(MarkwonInlineParserPlugin.create())
            // 图片：<img> 用 Coil 加载（缺少该插件时 Markwon 只渲染占位符 → 正文图片读不出）
            .usePlugin(io.noties.markwon.image.coil.CoilImagesPlugin.create(app))
            .usePlugin(JLatexMathPlugin.create(px) { it.inlinesEnabled(true) })
            .build()
        cachedContext = app
        cachedSize = textSizeSp
    }
    return cached!!
}