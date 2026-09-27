package dev.zcodemobile.shared.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens.
 *
 * The palette follows the indigo-accent / near-neutral-surface language that
 * coding-agent chat clients have converged on: a saturated primary that only
 * appears on interactive affordances, and everything else carried by surface
 * elevation plus a single muted secondary text colour.
 */
object ZcColors {
    val Indigo = Color(0xFF5B5BD6)
    val IndigoPressed = Color(0xFF4A4AC4)
    val IndigoContainer = Color(0xFFE5E5FB)

    val LightBg = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFF6F6F8)
    val LightSurfaceVariant = Color(0xFFEFEFF2)
    val LightSelected = Color(0xFFEBEBF0)
    val LightOutline = Color(0xFFE3E3E8)

    val DarkBg = Color(0xFF191919)
    val DarkSurface = Color(0xFF232326)
    val DarkSurfaceVariant = Color(0xFF2C2C30)
    val DarkSelected = Color(0xFF303036)
    val DarkOutline = Color(0xFF34343A)

    val TextPrimaryLight = Color(0xFF14141A)
    val TextSecondaryLight = Color(0xFF8A8A93)
    val TextPrimaryDark = Color(0xFFECECEF)
    val TextSecondaryDark = Color(0xFF9A9AA4)

    val Success = Color(0xFF2E9E5B)
    val Danger = Color(0xFFD9453D)
    val Warning = Color(0xFFD98A1F)
}

/** Extra tokens Material 3 has no slot for. */
data class ZcTokens(
    val secondaryText: Color,
    val chipBackground: Color,
    val codeBackground: Color,
    val userBubble: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val selectedRow: Color,
)

val LocalZcTokens = staticCompositionLocalOf {
    ZcTokens(
        secondaryText = ZcColors.TextSecondaryLight,
        chipBackground = ZcColors.LightSurfaceVariant,
        codeBackground = ZcColors.LightSurfaceVariant,
        userBubble = ZcColors.LightSurface,
        success = ZcColors.Success,
        danger = ZcColors.Danger,
        warning = ZcColors.Warning,
        selectedRow = ZcColors.LightSelected,
    )
}

/**
 * Message text scale, set from the conversation menu (小/标准/大/特大).
 * Row renderers multiply their text styles by it; 1f is the standard size.
 */
val LocalZcMsgScale = staticCompositionLocalOf { 1f }

val ZcTokens.dimens: Unit get() = Unit

private val LightScheme = lightColorScheme(
    primary = ZcColors.Indigo,
    onPrimary = Color.White,
    primaryContainer = ZcColors.IndigoContainer,
    onPrimaryContainer = Color(0xFF1B1B4D),
    background = ZcColors.LightBg,
    onBackground = ZcColors.TextPrimaryLight,
    surface = ZcColors.LightBg,
    onSurface = ZcColors.TextPrimaryLight,
    surfaceVariant = ZcColors.LightSurfaceVariant,
    onSurfaceVariant = ZcColors.TextSecondaryLight,
    surfaceContainer = ZcColors.LightSurface,
    surfaceContainerHigh = ZcColors.LightSurfaceVariant,
    outline = ZcColors.LightOutline,
    outlineVariant = ZcColors.LightOutline,
    error = ZcColors.Danger,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9B9BF5),
    onPrimary = Color(0xFF16163D),
    primaryContainer = Color(0xFF2E2E5C),
    onPrimaryContainer = Color(0xFFDCDCFF),
    background = ZcColors.DarkBg,
    onBackground = ZcColors.TextPrimaryDark,
    surface = ZcColors.DarkBg,
    onSurface = ZcColors.TextPrimaryDark,
    surfaceVariant = ZcColors.DarkSurfaceVariant,
    onSurfaceVariant = ZcColors.TextSecondaryDark,
    surfaceContainer = ZcColors.DarkSurface,
    surfaceContainerHigh = ZcColors.DarkSurfaceVariant,
    outline = ZcColors.DarkOutline,
    outlineVariant = ZcColors.DarkOutline,
    error = Color(0xFFF2938C),
)

/** Slightly tighter than Material defaults; chat text reads better at 15sp. */
private val ZcTypography = Typography(
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 27.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

val MonoFamily = FontFamily.Monospace

object Dims {
    val screenPadding = 16.dp
    val rowGap = 10.dp
    val cardRadius = 22.dp
    val chipRadius = 16.dp
    val avatarSize = 42.dp
    val iconButton = 34.dp
}

@Composable
fun ZCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val tokens = if (darkTheme) {
        ZcTokens(
            secondaryText = ZcColors.TextSecondaryDark,
            chipBackground = ZcColors.DarkSurfaceVariant,
            codeBackground = Color(0xFF2A2A2F),
            userBubble = ZcColors.DarkSurface,
            success = Color(0xFF5DBE84),
            danger = Color(0xFFF2938C),
            warning = Color(0xFFE0A94E),
            selectedRow = ZcColors.DarkSelected,
        )
    } else {
        ZcTokens(
            secondaryText = ZcColors.TextSecondaryLight,
            chipBackground = ZcColors.LightSurfaceVariant,
            codeBackground = Color(0xFFF4F4F6),
            userBubble = ZcColors.LightSurface,
            success = ZcColors.Success,
            danger = ZcColors.Danger,
            warning = ZcColors.Warning,
            selectedRow = ZcColors.LightSelected,
        )
    }

    CompositionLocalProvider(
        LocalZcTokens provides tokens,
        // Without this, any `Text` that does not name a colour inherits
        // Compose's default `LocalContentColor`, which is black — so dark mode
        // showed black text wherever a component relied on the ambient colour
        // (file-change chips, control chips, attachment chips). Seeding it from
        // the scheme makes "forgot the colour" degrade to the right colour in
        // both themes instead of only in light.
        LocalContentColor provides scheme.onBackground,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ZcTypography,
            content = content,
        )
    }
}
