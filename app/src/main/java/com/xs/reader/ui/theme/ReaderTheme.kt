package com.xs.reader.ui.theme

import androidx.compose.ui.graphics.Color

data class ReaderTheme(
    val id: String,
    val name: String,
    val bgColor: Color,
    val textColor: Color,
    val secondaryColor: Color,
    val isDark: Boolean
)

object ReaderThemes {
    val Day = ReaderTheme(
        id = "day",
        name = "日间",
        bgColor = Color(0xFFFFF8EE),
        textColor = Color(0xFF2A201A),
        secondaryColor = Color(0xFF8A7A66),
        isDark = false
    )
    val Night = ReaderTheme(
        id = "night",
        name = "夜间",
        bgColor = Color(0xFF111111),
        textColor = Color(0xFFCFC8BD),
        secondaryColor = Color(0xFF7A746B),
        isDark = true
    )
    val Eye = ReaderTheme(
        id = "eye",
        name = "护眼",
        bgColor = Color(0xFFCEE5BF),
        textColor = Color(0xFF1F2A1B),
        secondaryColor = Color(0xFF526545),
        isDark = false
    )
    val Parchment = ReaderTheme(
        id = "parchment",
        name = "羊皮纸",
        bgColor = Color(0xFFE9D9B5),
        textColor = Color(0xFF3B2C1A),
        secondaryColor = Color(0xFF8B7048),
        isDark = false
    )
    val Oled = ReaderTheme(
        id = "oled",
        name = "纯黑",
        bgColor = Color(0xFF000000),
        textColor = Color(0xFFB6B0A4),
        secondaryColor = Color(0xFF6A655B),
        isDark = true
    )

    val all = listOf(Day, Night, Eye, Parchment, Oled)
    fun byId(id: String): ReaderTheme = all.firstOrNull { it.id == id } ?: Day
}
