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
            }
        },
    )
}

/**
 * Markwon ext-latex 仅识别 $$...$$；官方 demo 支持单 $...$。
 * 这里把单 $ 行内公式升级为 $$（跳过含首尾空格的金额写法，如 "$5 and $10"）。
 */
private fun normalizeMath(md: String): String =
    Regex("(?<!\\$)\\$(?!\\$)([^$\\n]+?)(?<!\\$)\\$(?!\\$)").replace(md) { m ->
        val inner = m.groupValues[1]
        if (inner.startsWith(" ") || inner.endsWith(" ")) m.value else "\$\$$inner\$\$"
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
            .usePlugin(JLatexMathPlugin.create(px) { it.inlinesEnabled(true) })
            .build()
        cachedContext = app
        cachedSize = textSizeSp
    }
    return cached!!
}