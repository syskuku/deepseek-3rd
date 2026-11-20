package com.dschat.app

import com.dschat.app.ui.MathNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathNormalizerTest {

    @Test
    fun inlineParenDelimiters() {
        val out = MathNormalizer.normalize("质能方程 \\(E=mc^2\\) 成立")
        assertTrue(out.contains("\$\$E=mc^2\$\$"))
    }

    @Test
    fun displayBracketDelimiters() {
        val out = MathNormalizer.normalize("公式 \\[ \\frac{a}{b} \\] 如下")
        assertTrue(out.contains("\$\$\\frac{a}{b}\$\$"))
    }

    @Test
    fun singleDollarInline() {
        val out = MathNormalizer.normalize("值 \$x^2\$ 结束")
        assertTrue(out.contains("\$\$x^2\$\$"))
    }

    @Test
    fun currencyNotConverted() {
        val out = MathNormalizer.normalize("价格 \$5 and \$10 元")
        assertTrue(!out.contains("\$\$"))
    }

    @Test
    fun textcolorMappedToFgcolor() {
        val out = MathNormalizer.normalize("\\(\\textcolor{red}{x}\\)")
        assertTrue(out.contains("\\fgcolor{FF0000}{x}"))
    }

    @Test
    fun colorboxMappedToBgcolor() {
        val out = MathNormalizer.normalize("\\(\\colorbox{yellow}{y}\\)")
        assertTrue(out.contains("\\bgcolor{FFFF00}{y}"))
    }

    @Test
    fun fcolorboxMappedToNested() {
        val out = MathNormalizer.normalize("\\(\\fcolorbox{red}{blue}{z}\\)")
        assertTrue(out.contains("\\bgcolor{0000FF}{\\fgcolor{FF0000}{z}}"))
    }

    @Test
    fun scaleboxPreserved() {
        val out = MathNormalizer.normalize("\\(\\scalebox{2}{x}\\)")
        assertTrue(out.contains("\\scalebox{2}{x}"))
    }

    @Test
    fun nestedBracesInContent() {
        val out = MathNormalizer.normalize("\\(\\textcolor{red}{\\frac{a}{b}}\\)")
        assertTrue(out.contains("\\fgcolor{FF0000}{\\frac{a}{b}}"))
    }

    @Test
    fun htmlColorModel() {
        assertEquals("FF8800", MathNormalizer.colorToHex("[HTML]{FF8800}"))
        assertEquals("FF0000", MathNormalizer.colorToHex("[rgb]{1,0,0}"))
        assertEquals("00FF00", MathNormalizer.colorToHex("[RGB]{0,255,0}"))
        assertEquals("ABCDEF", MathNormalizer.colorToHex("#abcdef"))
        assertEquals("FF0000", MathNormalizer.colorToHex("red"))
    }

    @Test
    fun textcolorWithHtmlModelArg() {
        val out = MathNormalizer.normalize("\\(\\textcolor[HTML]{FF8800}{w}\\)")
        assertTrue(out.contains("\\fgcolor{FF8800}{w}"))
    }

    @Test
    fun bareFcolorboxLineWrappedAndMapped() {
        val out = MathNormalizer.normalize("\\fcolorbox{#8B6FA8}{#FBEDE9}{\\scalebox{0.8}{x}}")
        assertTrue("wrapped in \$\$", out.contains("\$\$"))
        assertTrue("mapped to bgcolor/fgcolor", out.contains("\\bgcolor{FBEDE9}{\\fgcolor{8B6FA8}"))
        assertTrue("scalebox kept", out.contains("\\scalebox{0.8}{x}"))
    }

    @Test
    fun bareBgcolorLineWrapped() {
        val out = MathNormalizer.normalize("\\bgcolor{FBF8F1}{\\fgcolor{6FA698}{\\scalebox{0.8}{y}}}")
        assertTrue(out.contains("\$\$\\bgcolor{FBF8F1}{\\fgcolor{6FA698}"))
    }

    @Test
    fun chineseProseNotWrapped() {
        val out = MathNormalizer.normalize("这是中文说明，含 \\frac 命令但不包裹")
        assertTrue(!out.contains("\$\$"))
    }

    @Test
    fun trailingDollarNotDoubled() {
        val out = MathNormalizer.normalize("\\frac{a}{b} \$\$")
        assertEquals("\$\$\\frac{a}{b}\$\$", out)
        assertTrue(!out.contains("\$\$\$\$"))
    }

    @Test
    fun katexBgcolorMappedAndHashColor() {
        val out = MathNormalizer.normalizeForKatex("\\bgcolor{6FA698}{\\fgcolor{FFFFFF}{x}}")
        assertTrue(out.contains("\\colorbox{#6FA698}{\\textcolor{#FFFFFF}{x}}"))
    }

    @Test
    fun katexScaleboxUnwrapped() {
        val out = MathNormalizer.normalizeForKatex("\\(\\scalebox{1.5}{y}\\)")
        assertTrue(out.contains("\$y\$"))
        assertTrue(!out.contains("scalebox"))
    }

    @Test
    fun katexParenToDollar() {
        val out = MathNormalizer.normalizeForKatex("\\(E=mc^2\\)")
        assertEquals("\$E=mc^2\$", out)
    }
}