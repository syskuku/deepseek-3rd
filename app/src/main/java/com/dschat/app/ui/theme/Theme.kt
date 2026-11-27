package com.dschat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 设计令牌逐行对照官方 src/app/theme.css（dsw 变量）。
 * 浅色 / 深色两套，与原版一致。
 */
data class DsColors(
    val brand: Color,
    val brandText: Color,
    val bgBase: Color,
    val layer2: Color,
    val layer3: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelDimmed: Color,
    val bubble: Color,
    val sidebarFill: Color,
    val sidebarActive: Color,
    val sidebarHover: Color,
    val inputMajor: Color,
    val borderL2: Color,
    val ghostActiveFill: Color,
    val ghostActiveBorder: Color,
    val ghostActiveHover: Color,
    val hover: Color,
    val error: Color,
    val codeBlock: Color,
    val inlineCode: Color,
    val tag: Color,
    val isDark: Boolean,
)

val DsLight = DsColors(
    brand = Color(0xFF3964FE),          // blue-500
    brandText = Color(0xFF3964FE),
    bgBase = Color(0xFFFFFFFF),
    layer2 = Color(0xFFFFFFFF),
    layer3 = Color(0xFFFFFFFF),
    labelPrimary = Color(0xFF0F1115),   // bluish-1000
    labelSecondary = Color(0xFF61666B), // bluish-700
    labelTertiary = Color(0xFF81858C),  // bluish-600
    labelCaption = Color(0xFFADB2B8),   // bluish-400
    labelDimmed = Color(0xFFE1E5EE),    // bluish-200
    bubble = Color(0xFFEDF3FE),         // blue-50
    sidebarFill = Color(0xFFF9FAFB),    // bluish-50
    sidebarActive = Color(0xFFE4EDFD),  // blue-100
    sidebarHover = Color(0xFFF1F3F5),   // bluish-75
    inputMajor = Color(0xFFFFFFFF),
    borderL2 = Color(0x1A000000),       // rgba(0,0,0,0.1)
    ghostActiveFill = Color(0xFFEDF3FE),
    ghostActiveBorder = Color(0xFFB7C8FE), // blue-300
    ghostActiveHover = Color(0xFFE4EDFD),
    hover = Color(0x0F263148),          // rgba(38,49,72,0.06)
    error = Color(0xFFEC1313),          // red-600
    codeBlock = Color(0xFFF9FAFB),
    inlineCode = Color(0xFFEBEEF2),
    tag = Color(0xFFF1F3F5),
    isDark = false,
)

val DsDark = DsColors(
    brand = Color(0xFF5686FE),          // blue-450
    brandText = Color(0xFF679EFE),      // blue-400
    bgBase = Color(0xFF151517),         // bluish-950
    layer2 = Color(0xFF2C2C2E),         // bluish-850
    layer3 = Color(0xFF353638),         // bluish-800
    labelPrimary = Color(0xFFF9FAFB),
    labelSecondary = Color(0xFFCFD3D6), // bluish-300
    labelTertiary = Color(0xFFADB2B8),
    labelCaption = Color(0xFF81858C),
    labelDimmed = Color(0xFF43454A),    // bluish-750
    bubble = Color(0xFF2C2C2E),
    sidebarFill = Color(0xFF1B1B1C),    // bluish-900
    sidebarActive = Color(0xFF353638),
    sidebarHover = Color(0xFF2C2C2E),
    inputMajor = Color(0xFF2C2C2E),
    borderL2 = Color(0x1FFFFFFF),       // rgba(255,255,255,0.12)
    ghostActiveFill = Color(0xFF283142), // blue-900
    ghostActiveBorder = Color(0xFF4868B2), // blue-600
    ghostActiveHover = Color(0xFF34415B),  // blue-800
    hover = Color(0x14FFFFFF),          // rgba(255,255,255,0.08)
    error = Color(0xFFF25A5A),          // red-400
    codeBlock = Color(0xFF1B1B1C),
    inlineCode = Color(0xFF2C2C2E),
    tag = Color(0xFF2C2C2E),
    isDark = true,
)

val LocalDsColors = staticCompositionLocalOf { DsLight }

@Composable
fun DeepSeekTheme(themePreference: String, content: @Composable () -> Unit) {
    val dark = when (themePreference) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val ds = if (dark) DsDark else DsLight
    val scheme = if (dark) {
        darkColorScheme(
            primary = ds.brand,
            onPrimary = Color.White,
            background = ds.bgBase,
            onBackground = ds.labelPrimary,
            surface = ds.bgBase,
            onSurface = ds.labelPrimary,
            surfaceVariant = ds.layer2,
            onSurfaceVariant = ds.labelSecondary,
            outline = ds.borderL2,
            error = ds.error,
        )
    } else {
        lightColorScheme(
            primary = ds.brand,
            onPrimary = Color.White,
            background = ds.bgBase,
            onBackground = ds.labelPrimary,
            surface = ds.bgBase,
            onSurface = ds.labelPrimary,
            surfaceVariant = ds.sidebarFill,
            onSurfaceVariant = ds.labelSecondary,
            outline = ds.borderL2,
            error = ds.error,
        )
    }
    CompositionLocalProvider(LocalDsColors provides ds) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}