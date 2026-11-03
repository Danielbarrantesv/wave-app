package com.mp3tify.ui.theme

import androidx.compose.ui.graphics.Color

/** Selectable accent colors (key persists in DataStore). */
enum class AccentColor(
    val key: String,
    val displayKey: String,
    val light: Color,
    val dark: Color,
    val lightContainer: Color,
    val darkContainer: Color
) {
    VERDE(
        "verde", "color_verde",
        // Brand green for Wave: #32C98B
        Color(0xFF32C98B), Color(0xFF32C98B),
        Color(0xFFC8F0DC), Color(0xFF0F5D3E)
    ),
    ROJO(
        "rojo", "color_rojo",
        Color(0xFFD32F2F), Color(0xFFEF5350),
        Color(0xFFF3C1C1), Color(0xFF5C1A1A)
    ),
    AZUL(
        "azul", "color_azul",
        Color(0xFF1976D2), Color(0xFF42A5F5),
        Color(0xFFC3DDF6), Color(0xFF173F5F)
    ),
    PURPURA(
        "purpura", "color_purpura",
        Color(0xFF7B1FA2), Color(0xFFAB47BC),
        Color(0xFFE3C6EE), Color(0xFF4A1B5C)
    ),
    NARANJA(
        "naranja", "color_naranja",
        Color(0xFFEF6C00), Color(0xFFFF9800),
        Color(0xFFF8DCC0), Color(0xFF5D3A10)
    ),
    ROSA(
        "rosa", "color_rosa",
        Color(0xFFC2185B), Color(0xFFF06292),
        Color(0xFFF4C6D6), Color(0xFF5C1633)
    ),
    CIAN(
        "cian", "color_cian",
        Color(0xFF00838F), Color(0xFF26C6DA),
        Color(0xFFC1E7EA), Color(0xFF0E4A52)
    ),
    AMARILLO(
        "amarillo", "color_amarillo",
        Color(0xFFF9A825), Color(0xFFFFD54F),
        Color(0xFFF7E9B5), Color(0xFF5E4B10)
    );

    companion object {
        fun fromKey(key: String?): AccentColor = entries.firstOrNull { it.key == key } ?: VERDE
    }
}
