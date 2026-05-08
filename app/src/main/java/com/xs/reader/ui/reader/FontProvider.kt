package com.xs.reader.ui.reader

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

object FontProvider {
    /** 内置选项 id；自定义字体使用 prefs.customFontPath 优先。 */
    fun resolve(fontFamilyId: String, customFontPath: String?): FontFamily {
        if (!customFontPath.isNullOrBlank()) {
            val f = File(customFontPath)
            if (f.exists()) {
                return FontFamily(Font(f))
            }
        }
        return when (fontFamilyId) {
            "serif" -> FontFamily.Serif
            "sans" -> FontFamily.SansSerif
            "mono" -> FontFamily.Monospace
            else -> FontFamily.Default
        }
    }
}
