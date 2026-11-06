package io.embodit.quoridorwalls.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.quoridorwalls.domain.Language
import io.embodit.quoridorwalls.platform.Graph
import java.util.Locale

/** design-package colours: lacquer teal board, walnut frame, maple walls, ivory / cinnabar-orange pawns, brass, gold / blue bands. */
object QColors {
    val Primary = Color(0xFF1F4F4A)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFCFE3DF)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    /** Error text on glass / tag face. */
    val ErrorText = Color(0xFF7A1A18)
    val Amber = Color(0xFFB07A10)

    // 3D / tags
    val Lacquer = Color(0xFF1F4F4A)
    val Walnut = Color(0xFF4A3222)
    val Maple = Color(0xFFE3C892)
    val Ivory = Color(0xFFF2E8D5)
    val Cinnabar = Color(0xFFFF8C42)
    val Brass = Color(0xFFC9A24A)
    val BandGold = Color(0xFFF2C14E)
    val BandBlue = Color(0xFF4FA3E0)
    val TagFace = Color(0xFFFBF6EC)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun QTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = QColors.OnPrimary,
        labelPrimary = QColors.Ink,
        labelSecondary = QColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = QColors.Error,
        alert = QColors.Amber,
        passable = QColors.Walnut,
        interaction = QColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
