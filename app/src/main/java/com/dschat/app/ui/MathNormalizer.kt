package com.dschat.app.ui

/**
 * 公式适配（纯逻辑，可单元测试）：
 * 1) Markwon ext-latex 仅识别 $$...$$；把 \(...\)、\[...\]、单 $...$ 统一升级为 $$。
 * 2) JLatexMath 不支持 \textcolor/\colorbox/\fcolorbox，映射为其原生 \fgcolor/\bgcolor（颜色转十六进制）。
 *    \scalebox/\boxed/\frac/\sqrt 等 JLatexMath 原生支持，保留。
 */
object MathNormalizer {

    fun normalize(md: String): String {
        var s = md
        s = Regex("\\\\\\[([\\s\\S]*?)\\\\\\]").replace(s) { "\$\$" + it.groupValues[1].trim() + "\$\$" }
        s = Regex("\\\\\\(([\\s\\S]*?)\\\\\\)").replace(s) { "\$\$" + it.groupValues[1].trim() + "\$\$" }
        s = Regex("(?<!\\$)\\$(?!\\$)([^$\\n]+?)(?<!\\$)\\$(?!\\$)").replace(s) { m ->
            val inner = m.groupValues[1]
            if (inner.startsWith(" ") || inner.endsWith(" ")) m.value else "\$\$$inner\$\$"
        }
        s = rewriteColorCommands(s)
        s = wrapBareFormulaLines(s)
        return s
    }

    private val MATH_BLOCK = Regex("\$\$[\\s\\S]*?\$\$")
    private val FORMULA_CMD = Regex("\\\\(fcolorbox|colorbox|textcolor|bgcolor|fgcolor|scalebox|boxed|frac|sqrt|sum|int|lim|begin|mathrm|mathbb|text|alpha|beta|gamma|theta|lambda|pi|sigma|omega|infty|partial|nabla|cdot|times|leq|geq|neq|approx|equiv|subset|cup|cap|forall|exists|rightarrow|leftarrow|Rightarrow|overline|underline|hat|bar|vec|dot)\\b")
    private val CJK = Regex("[一-鿿]")

    /** 模型有时输出不带定界符的裸公式行；把纯公式行包裹成 $$ 以便渲染（跳过已有 $$ 块与中文 prose） */
    private fun wrapBareFormulaLines(s: String): String {
        val sb = StringBuilder()
        var last = 0
        for (m in MATH_BLOCK.findAll(s)) {
            sb.append(wrapPlain(s.substring(last, m.range.first)))
            sb.append(m.value)
            last = m.range.last + 1
        }
        sb.append(wrapPlain(s.substring(last)))
        return sb.toString()
    }

    private fun wrapPlain(seg: String): String = seg.lines().joinToString("\n") { line ->
        val t = line.trim()
        if (t == "\$\$") return@joinToString line // 独立定界符行保持原样
        // 去掉行内已有的首尾 $$，避免包裹后出现重复 $$ 漏在公式后
        val core = t.removePrefix("\$\$").removeSuffix("\$\$").trim()
        if (core.isNotEmpty() && FORMULA_CMD.containsMatchIn(core) && !CJK.containsMatchIn(core)) "\$\$$core\$\$" else line
    }

    private fun readCmd(s: String, i: Int): Pair<String, Int> {
        var j = i + 1
        while (j < s.length && s[j].isLetter()) j++
        return s.substring(i + 1, j) to j
    }

    private fun readArgs(s: String, start: Int, n: Int): Pair<List<String>, Int>? {
        var i = start
        val args = ArrayList<String>()
        repeat(n) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length || s[i] != '{') return null
            var depth = 0
            val sb = StringBuilder()
            i++
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '{' -> { depth++; sb.append(c) }
                    c == '}' -> {
                        if (depth == 0) { i++; args.add(sb.toString()); return@repeat }
                        depth--; sb.append(c)
                    }
                    c == '\\' && i + 1 < s.length -> { sb.append(c); i++; sb.append(s[i]) }
                    else -> sb.append(c)
                }
                i++
            }
            return null
        }
        return args to i
    }

    private val NAMED_COLORS = mapOf(
        "black" to "000000", "white" to "FFFFFF", "red" to "FF0000", "green" to "008000",
        "blue" to "0000FF", "yellow" to "FFFF00", "orange" to "FFA500", "purple" to "800080",
        "cyan" to "00FFFF", "magenta" to "FF00FF", "gray" to "808080", "grey" to "808080",
        "brown" to "A52A2A", "pink" to "FFC0CB", "lime" to "00FF00", "teal" to "008080",
        "violet" to "EE82EE", "gold" to "FFD700", "silver" to "C0C0C0", "navy" to "000080",
        "darkred" to "8B0000", "darkgreen" to "006400", "darkblue" to "00008B", "lightgray" to "D3D3D3",
    )

    fun colorToHex(raw: String): String {
        var c = raw.trim()
        val model = Regex("^\\[([a-zA-Z]+)]\\s*").find(c)
        if (model != null) {
            val m = model.groupValues[1].lowercase()
            c = c.substring(model.range.last + 1).trim().removeSurrounding("{", "}").trim()
            when (m) {
                "rgb" -> {
                    val p = c.split(",").map { it.trim().toFloatOrNull() ?: 0f }
                    if (p.size >= 3) return p.take(3).joinToString("") { (it.coerceIn(0f, 1f) * 255).toInt().toString(16).padStart(2, '0') }.uppercase()
                }
                "RGB" -> {
                    val p = c.split(",").map { it.trim().toIntOrNull() ?: 0 }
                    if (p.size >= 3) return p.take(3).joinToString("") { it.coerceIn(0, 255).toString(16).padStart(2, '0') }.uppercase()
                }
            }
        }
        c = c.removeSurrounding("{", "}").removePrefix("#").trim()
        NAMED_COLORS[c.lowercase()]?.let { return it }
        if (Regex("^[0-9a-fA-F]{6}$").matches(c)) return c.uppercase()
        if (Regex("^[0-9a-fA-F]{3}$").matches(c)) return c.map { "$it$it" }.joinToString("").uppercase()
        return "000000"
    }

    /** 读取可选 [model] 参数（如 [HTML]/[rgb]），返回 (模型串, 下一索引) */
    private fun readModel(s: String, start: Int): Pair<String, Int> {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        if (i < s.length && s[i] == '[') {
            val close = s.indexOf(']', i)
            if (close > i) return s.substring(i, close + 1) to (close + 1)
        }
        return "" to start
    }

    private fun rewriteColorCommands(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length && s[i + 1].isLetter()) {
                val (cmd, next) = readCmd(s, i)
                when (cmd) {
                    "textcolor", "colorbox", "fcolorbox" -> {
                        val (model, afterModel) = readModel(s, next)
                        val n = if (cmd == "fcolorbox") 3 else 2
                        val parsed = readArgs(s, afterModel, n)
                        if (parsed != null) {
                            val (args, end) = parsed
                            val inner = rewriteColorCommands(args.last())
                            when (cmd) {
                                "textcolor" -> sb.append("\\fgcolor{${colorToHex(model + args[0])}}{$inner}")
                                "colorbox" -> sb.append("\\bgcolor{${colorToHex(model + args[0])}}{$inner}")
                                "fcolorbox" -> sb.append("\\bgcolor{${colorToHex(model + args[1])}}{\\fgcolor{${colorToHex(model + args[0])}}{$inner}}")
                            }
                            i = end
                            continue
                        }
                    }
                }
                sb.append('\\').append(cmd)
                i = next
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }
}