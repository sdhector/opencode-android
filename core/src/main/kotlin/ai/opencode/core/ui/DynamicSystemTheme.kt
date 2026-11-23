package ai.opencode.core.ui

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Android's System theme follows the wallpaper (Material You). The curated themes (OpenCode, Tokyo
 * Night, …) keep their fixed palettes.
 *
 * Code highlighting and diff colours stay on [ThemePalette.system]'s values: dynamic colour
 * has no roles for them, and tinting them with the wallpaper would hurt legibility.
 */
object DynamicSystemTheme {
    /** The wallpaper's Material colour scheme. */
    fun colorScheme(context: Context, isDark: Boolean): ColorScheme {
        if (isDark) {
            return dynamicDarkColorScheme(context)
        }
        return dynamicLightColorScheme(context)
    }

    /** The System theme's palette built from the wallpaper, for swatches and previews. */
    fun definition(context: Context): ThemeDefinition {
        return ThemeDefinition(
            id = AppTheme.System,
            title = AppTheme.System.title,
            light = variant(colorScheme(context, isDark = false), ThemePalette.system.light),
            dark = variant(colorScheme(context, isDark = true), ThemePalette.system.dark),
        )
    }

    /** Maps Material roles onto the palette slots; [fixed] supplies syntax and diff colours. */
    fun variant(scheme: ColorScheme, fixed: ThemeVariant): ThemeVariant {
        return ThemeVariant(
            canvas = scheme.surface.rgba(),
            canvasWeak = scheme.surfaceContainer.rgba(),
            codeBand = scheme.surfaceContainerLow.rgba(),
            userBubble = scheme.surfaceContainerHigh.rgba(),
            surfaceStrong = scheme.surfaceContainerHighest.rgba(),
            text = scheme.onSurface.rgba(),
            bodyText = scheme.onSurface.rgba(),
            textStrong = scheme.onSurface.rgba(),
            textWeak = scheme.onSurfaceVariant.rgba(),
            textTertiary = scheme.outline.rgba(),
            border = scheme.outlineVariant.rgba(),
            borderStrong = scheme.outline.rgba(),
            accent = scheme.primary.rgba(),
            link = scheme.primary.rgba(),
            syntaxKeyword = fixed.syntaxKeyword,
            syntaxString = fixed.syntaxString,
            syntaxNumber = fixed.syntaxNumber,
            syntaxComment = fixed.syntaxComment,
            syntaxType = fixed.syntaxType,
            diffAddedFill = fixed.diffAddedFill,
            diffRemovedFill = fixed.diffRemovedFill,
            glassDiffAddedFill = fixed.glassDiffAddedFill,
            glassDiffRemovedFill = fixed.glassDiffRemovedFill,
            diffAddedText = scheme.onSurface.rgba(),
            diffRemovedText = scheme.onSurface.rgba(),
            swatchCanvas = scheme.surfaceContainerLow.rgba(),
            swatchRing = scheme.outlineVariant.rgba(),
            swatchAccent = scheme.primary.rgba(),
        )
    }

    /** The app's semantic colours for the System theme, read from the wallpaper's [scheme]. */
    fun colors(scheme: ColorScheme, isDark: Boolean, highContrast: Boolean, strongBorders: Boolean = highContrast): OpenCodeColors {
        val fixed = ThemePalette.system.variant(isDark)
        val onSurface = scheme.onSurface
        val bodyResolver: (Double) -> Color = { tone ->
            if (highContrast || tone >= 0.999) {
                onSurface
            } else {
                onSurface.copy(alpha = tone.toFloat())
            }
        }
        val border = if (highContrast || strongBorders) scheme.outline else scheme.outlineVariant
        return OpenCodeColors(
            theme = AppTheme.System,
            isDark = isDark,
            highContrast = highContrast,
            strongBorders = strongBorders || highContrast,
            variant = variant(scheme, fixed),
            canvas = scheme.surface,
            groupedCanvas = scheme.surfaceContainer,
            groupedCard = scheme.surfaceBright,
            codeBand = scheme.surfaceContainerLow,
            userBubble = scheme.surfaceContainerHigh,
            inlineCodeFill = scheme.surfaceContainerHighest,
            text = onSurface,
            strongText = onSurface,
            secondaryText = scheme.onSurfaceVariant,
            tertiaryText = if (highContrast) scheme.onSurfaceVariant else scheme.outline,
            defaultBodyText = bodyResolver(0.92),
            border = border,
            accent = scheme.primary,
            sendButtonBackground = scheme.primary,
            sendButtonForeground = scheme.onPrimary,
            link = scheme.primary,
            signal = scheme.primary,
            selection = scheme.primary,
            onSelection = scheme.onPrimary,
            focusRing = scheme.primary,
            rowDivider = if (highContrast || strongBorders) scheme.outline else onSurface.copy(alpha = 0.12f),
            syntaxKeyword = fixed.syntaxKeyword.color,
            syntaxString = fixed.syntaxString.color,
            syntaxNumber = fixed.syntaxNumber.color,
            syntaxComment = fixed.syntaxComment.color,
            syntaxType = fixed.syntaxType.color,
            diffAddedFill = SystemColors.green(isDark).withAlpha(0.16).color,
            diffRemovedFill = SystemColors.red(isDark).withAlpha(0.14).color,
            diffAddedText = onSurface,
            diffRemovedText = onSurface,
            destructive = scheme.error,
            danger = scheme.error,
            warning = SystemColors.orange(isDark).color,
            followUp = SystemColors.purple(isDark).color,
            bodyTextResolver = bodyResolver,
        )
    }

    private fun Color.rgba(): ThemeRGBA {
        return ThemeRGBA(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())
    }
}

/**
 * The palette [theme] draws with: the wallpaper's for [AppTheme.System], the fixed one
 * otherwise. Use this instead of [ThemePalette.definition] for anything shown on screen.
 */
@Composable
fun rememberThemeDefinition(theme: AppTheme): ThemeDefinition {
    if (theme != AppTheme.System) {
        return ThemePalette.definition(theme)
    }
    val context = LocalContext.current
    // A wallpaper change arrives as a configuration change.
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { DynamicSystemTheme.definition(context) }
}
