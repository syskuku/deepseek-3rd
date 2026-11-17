package com.dschat.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Markdown 渲染（Markwon TextView）。流式输出时按文本变化节流刷新，
 * 避免每个 token 都触发完整解析。
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
                markwon(view.context).setMarkdown(view, markdown)
            }
        },
    )
}

private var cached: Markwon? = null
private var cachedContext: Context? = null

private fun markwon(context: Context): Markwon {
    val app = context.applicationContext
    if (cached == null || cachedContext !== app) {
        cached = Markwon.builder(app)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(app))
            .usePlugin(LinkifyPlugin.create())
            .build()
        cachedContext = app
    }
    return cached!!
}