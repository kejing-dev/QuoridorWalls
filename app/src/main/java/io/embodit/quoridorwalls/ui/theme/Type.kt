package io.embodit.quoridorwalls.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.pico.spatial.ui.design.Typography
import io.embodit.quoridorwalls.R

/**
 * Noto Sans (SIL Open Font License 1.1, see third_party/noto-sans/OFL.txt). Chinese glyphs fall
 * back to the system CJK face. The PicoTheme type scale keeps its sizes and weights; only the
 * typeface changes.
 */
val NotoSans: FontFamily =
    FontFamily(
        Font(R.font.noto_sans_regular, FontWeight.Normal),
        Font(R.font.noto_sans_medium, FontWeight.Medium),
        Font(R.font.noto_sans_semibold, FontWeight.SemiBold),
        Font(R.font.noto_sans_bold, FontWeight.Bold),
    )

private fun TextStyle.inNotoSans(): TextStyle = copy(fontFamily = NotoSans)

fun Typography.withNotoSans(): Typography =
    copy(
        bodyLarge = bodyLarge.inNotoSans(),
        bodyLargeMultiline = bodyLargeMultiline.inNotoSans(),
        bodyMedium = bodyMedium.inNotoSans(),
        bodyMediumMultiline = bodyMediumMultiline.inNotoSans(),
        bodySmall = bodySmall.inNotoSans(),
        bodyTiny = bodyTiny.inNotoSans(),
        displayLarge = displayLarge.inNotoSans(),
        displayMedium = displayMedium.inNotoSans(),
        displaySmall = displaySmall.inNotoSans(),
        headlineLarge = headlineLarge.inNotoSans(),
        headlineMedium = headlineMedium.inNotoSans(),
        headlineSmall = headlineSmall.inNotoSans(),
        labelLarge = labelLarge.inNotoSans(),
        labelMedium = labelMedium.inNotoSans(),
        labelSmall = labelSmall.inNotoSans(),
        titleLarge = titleLarge.inNotoSans(),
        titleMedium = titleMedium.inNotoSans(),
        titleSmall = titleSmall.inNotoSans(),
    )
