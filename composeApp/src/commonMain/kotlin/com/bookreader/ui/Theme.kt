package com.bookreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B6EA5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF7A5900),
    secondaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFFFDFBF7),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFBF7),
    surfaceVariant = Color(0xFFE0E2EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5C8FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF1C4975),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFF0C048),
    secondaryContainer = Color(0xFF5C4300),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121316),
    surfaceVariant = Color(0xFF43474E),
)

/** Reader page tint, independent of the app chrome. */
enum class ReaderTheme(val background: Color, val text: Color) {
    LIGHT(Color(0xFFFDFBF7), Color(0xFF1A1C1E)),
    SEPIA(Color(0xFFF4ECD8), Color(0xFF3A2F21)),
    DARK(Color(0xFF121316), Color(0xFFE3E2E6)),
}

val LocalReaderTheme = compositionLocalOf { ReaderTheme.LIGHT }

/** True when the UI language is right-to-left, used to mirror Persian content. */
val LocalIsRtl = compositionLocalOf { false }

@Composable
fun BookReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/** Persian and Arabic script need right-to-left layout for their own text. */
fun isRtlLanguage(tag: String = Locale.current.language): Boolean =
    tag.startsWith("fa") || tag.startsWith("ar") || tag.startsWith("he") || tag.startsWith("ur")

/**
 * Detects Persian/Arabic text so a mixed-script definition list can lay each
 * entry out in its own direction rather than forcing one direction on both.
 */
fun isRtlText(text: String): Boolean {
    val firstStrong = text.firstOrNull { it.isLetter() } ?: return false
    val code = firstStrong.code
    return code in 0x0590..0x08FF || code in 0xFB1D..0xFDFF || code in 0xFE70..0xFEFF
}
