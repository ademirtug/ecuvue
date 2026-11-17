package app.leximo.ecuvue.ui

import androidx.compose.material3.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal data class Words(val translations: Translations, val language: String) {
    operator fun invoke(key: String, fallback: String = key) = translations.text(language, key, fallback)
}

internal val LocalWords = staticCompositionLocalOf<Words> { error("Translations not loaded") }

internal val languages = linkedMapOf("" to "System default", "en" to "English", "tr" to "Türkçe", "es" to "Español", "de" to "Deutsch", "ja" to "日本語", "zh" to "简体中文", "fr" to "Français", "ar" to "العربية", "it" to "Italiano")

internal fun appColors(palette: String, dark: Boolean): ColorScheme {
    val accent = when (palette) {
        "iceBlue" -> if (dark) Color(0xFF9DD7FF) else Color(0xFF17649A)
        "racingGreen" -> if (dark) Color(0xFF9ED7B1) else Color(0xFF24633D)
        "sunset" -> if (dark) Color(0xFFFFC08A) else Color(0xFF9A4F16)
        else -> if (dark) Color(0xFFF0F0F0) else Color(0xFF202020)
    }
    val container = when (palette) {
        "iceBlue" -> if (dark) Color(0xFF183B50) else Color(0xFFDCEFFF)
        "racingGreen" -> if (dark) Color(0xFF1D3C2A) else Color(0xFFDCEFE1)
        "sunset" -> if (dark) Color(0xFF4A2B16) else Color(0xFFFFE4CC)
        else -> if (dark) Color(0xFF303030) else Color(0xFFEAEAEA)
    }
    val background = if (dark) Color(0xFF101010) else when (palette) {
        "iceBlue" -> Color(0xFFF1F7FB); "racingGreen" -> Color(0xFFF2F7F1); "sunset" -> Color(0xFFFBF5EF); else -> Color(0xFFF5F5F5)
    }
    return if (dark) darkColorScheme(primary = accent, onPrimary = Color(0xFF161616), primaryContainer = container,
        onPrimaryContainer = accent, secondary = accent, secondaryContainer = container, onSecondaryContainer = accent,
        surfaceContainer = Color(0xFF191919), surfaceContainerHigh = Color(0xFF262626),
        background = background, surface = Color(0xFF191919), surfaceVariant = Color(0xFF262626), outlineVariant = Color(0xFF383838))
    else lightColorScheme(primary = accent, onPrimary = Color.White, primaryContainer = container,
        onPrimaryContainer = accent, secondary = accent, secondaryContainer = container, onSecondaryContainer = accent,
        surfaceContainer = Color(0xFFEFEFEF), surfaceContainerHigh = Color(0xFFE8E8E8),
        background = background, surface = Color.White, surfaceVariant = Color(0xFFEFEFEF), outlineVariant = Color(0xFFDDDDDD))
}
