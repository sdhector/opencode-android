package ai.opencode.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * The app's semantic colours for one theme, mode and contrast state. Read with
 * `OpenCodeTheme.colors`; roles Material has are also in `MaterialTheme.colorScheme`.
 */
@Immutable
class OpenCodeColors(
    val theme: AppTheme,
    val isDark: Boolean,
    val highContrast: Boolean,
    /**
     * Hairlines draw in the strong border colour: desktop hairlines are under 3:1 against the
     * canvas.
     */
    val strongBorders: Boolean,
    /** The raw palette, for swatches (`swatchCanvas`, `swatchRing`, `swatchAccent`) and contrast checks. */
    val variant: ThemeVariant,
    /** Plain backgrounds. */
    val canvas: Color,
    /** Grouped lists' background, Settings. */
    val groupedCanvas: Color,
    /** A card or row group on [groupedCanvas]. */
    val groupedCard: Color,
    /** Code block band behind code. */
    val codeBand: Color,
    /** The user's message bubble. */
    val userBubble: Color,
    /** Inline `code` fill. */
    val inlineCodeFill: Color,
    /** Primary text. */
    val text: Color,
    /** Strongest text: titles, emphasis. */
    val strongText: Color,
    /** Secondary text. */
    val secondaryText: Color,
    /** Tertiary text. */
    val tertiaryText: Color,
    /** Default transcript prose ([bodyText] at tone 0.92). */
    val defaultBodyText: Color,
    /** Separators and hairlines. */
    val border: Color,
    /** Tint for controls and selection. */
    val accent: Color,
    val sendButtonBackground: Color,
    val sendButtonForeground: Color,
    val link: Color,
    /**
     * Unread and new-item indicators (drawer dots, Catch Up). The desktop draws its unread dot
     * in `--v2-background-bg-accent` (blue), not the neutral [accent]; this is the theme's link blue.
     */
    val signal: Color,
    /**
     * Fill for on and selected controls (switch tracks, segments, checkmarks). Never for primary
     * buttons or text.
     */
    val selection: Color,
    /** Content drawn on [selection]: a switch thumb, a check inside a filled radio. */
    val onSelection: Color,
    /** Focus ring on text and search fields (desktop `--v2-border-border-focus`, blue-500). */
    val focusRing: Color,
    /**
     * Hairline between rows inside a card (desktop `--v2-border-border-base`: black or white at
     * 10%), so it reads on [groupedCard] in light and dark. The strong border under Increase Contrast.
     */
    val rowDivider: Color,
    val syntaxKeyword: Color,
    val syntaxString: Color,
    val syntaxNumber: Color,
    val syntaxComment: Color,
    val syntaxType: Color,
    val diffAddedFill: Color,
    val diffRemovedFill: Color,
    val diffAddedText: Color,
    val diffRemovedText: Color,
    /** Destructive roles only. */
    val destructive: Color,
    /**
     * Failure text and glyphs (a failed tool, a run error): the desktop's `--v2-state-fg-danger`,
     * deeper than [destructive] in light and softer in dark. Not for fills behind white labels.
     */
    val danger: Color,
    /** Needs attention without having failed: a rejected sign-in, a weak connection, a paste that didn't work. */
    val warning: Color,
    /** Follow Up's outlined dot: a reply read but not handled yet, apart from unread's [signal]. */
    val followUp: Color,
    private val bodyTextResolver: (Double) -> Color,
) {
    /** Prose at [tone] (`0..1`) between canvas and text, mixed in OKLab. */
    fun bodyText(tone: Double): Color = when {
        tone >= 0.999 -> strongText
        abs(tone - 0.92) < 0.0005 -> defaultBodyText
        else -> bodyTextResolver(tone)
    }

    companion object {
        /** Resolves every semantic colour for [theme], [isDark] and [highContrast]. */
        fun resolve(theme: AppTheme, isDark: Boolean, highContrast: Boolean = false, strongBorders: Boolean = highContrast): OpenCodeColors {
            val v = ThemePalette.definition(theme).variant(isDark)
            val system = theme == AppTheme.System
            val label = v.text
            val text = if (!system && highContrast) v.textStrong else v.text
            val bodyResolver: (Double) -> Color = { tone ->
                when {
                    system -> if (highContrast || tone >= 0.999) label.color else label.withAlpha(tone).color
                    highContrast || tone >= 0.999 -> (if (highContrast) v.textStrong else v.text).color
                    abs(tone - 0.92) < 0.0005 -> v.bodyText.color
                    else -> v.canvas.mixedInOklab(v.text, tone).color
                }
            }
            return OpenCodeColors(
                theme = theme,
                isDark = isDark,
                highContrast = highContrast,
                strongBorders = strongBorders || highContrast,
                variant = v,
                canvas = v.canvas.color,
                groupedCanvas = (if (isDark) v.canvas else v.canvasWeak).color,
                groupedCard = when {
                    !isDark -> v.canvas
                    !system && highContrast -> v.surfaceStrong
                    else -> v.userBubble
                }.color,
                codeBand = if (system) label.withAlpha(if (isDark) 0.035 else 0.025).color else v.codeBand.color,
                userBubble = (if (!system && highContrast) v.surfaceStrong else v.userBubble).color,
                inlineCodeFill = if (system) SystemColors.tertiarySystemFill(isDark).color else v.surfaceStrong.color,
                text = text.color,
                strongText = (if (system) label else v.textStrong).color,
                secondaryText = (if (!system && highContrast) v.text else v.textWeak).color,
                tertiaryText = (if (!system && highContrast) v.textWeak else v.textTertiary).color,
                defaultBodyText = bodyResolver(0.92),
                border = (if ((!system && highContrast) || strongBorders) v.borderStrong else v.border).color,
                accent = v.accent.color,
                sendButtonBackground = (if (system) label else v.accent).color,
                sendButtonForeground = (if (system) v.canvas else v.accent.highContrastForeground).color,
                link = v.link.color,
                signal = v.link.color,
                selection = when {
                    theme == AppTheme.OpenCode -> DesktopAccent.bgAccent
                    system -> v.link.color
                    else -> v.accent.color
                },
                onSelection = if (theme == AppTheme.OpenCode || system) Color.White else v.accent.highContrastForeground.color,
                focusRing = when {
                    theme == AppTheme.OpenCode -> DesktopAccent.borderFocus
                    system -> v.link.color
                    else -> v.accent.color
                },
                rowDivider = when {
                    (!system && highContrast) || strongBorders -> v.borderStrong.color
                    isDark -> DesktopAccent.borderBaseDark
                    else -> DesktopAccent.borderBaseLight
                },
                syntaxKeyword = v.syntaxKeyword.color,
                syntaxString = v.syntaxString.color,
                syntaxNumber = v.syntaxNumber.color,
                syntaxComment = v.syntaxComment.color,
                syntaxType = v.syntaxType.color,
                diffAddedFill = (if (system) SystemColors.green(isDark).withAlpha(0.16) else v.glassDiffAddedFill).color,
                diffRemovedFill = (if (system) SystemColors.red(isDark).withAlpha(0.14) else v.glassDiffRemovedFill).color,
                diffAddedText = (if (system) label else v.diffAddedText).color,
                diffRemovedText = (if (system) label else v.diffRemovedText).color,
                destructive = SystemColors.red(isDark).color,
                danger = (if (system) SystemColors.red(isDark) else v.diffRemovedText).color,
                warning = SystemColors.orange(isDark).color,
                followUp = SystemColors.purple(isDark).color,
                bodyTextResolver = bodyResolver,
            )
        }
    }
}

/** Exact desktop tokens (`packages/ui/src/styles/tokens/theme.css`), the same in light and dark. */
internal object DesktopAccent {
    /** `--v2-background-bg-accent`: blue-600. */
    val bgAccent = Color(0xFF3B5CF6)

    /** `--v2-border-border-focus`: blue-500. */
    val borderFocus = Color(0xFF7698FD)

    /** `--v2-border-border-base`: black at 10% (light), white at 10% (dark). */
    val borderBaseLight = Color(0x1A000000)
    val borderBaseDark = Color(0x1AFFFFFF)
}

/** System colours the System theme reads that have no field in [ThemeVariant]. */
internal object SystemColors {
    fun red(isDark: Boolean) = if (isDark) ThemeRGBA(1.0, 0.2706, 0.2275) else ThemeRGBA(1.0, 0.2314, 0.1882)
    fun orange(isDark: Boolean) = if (isDark) ThemeRGBA(1.0, 0.6235, 0.0392) else ThemeRGBA(1.0, 0.5843, 0.0)
    fun green(isDark: Boolean) = if (isDark) ThemeRGBA(0.1882, 0.8196, 0.3451) else ThemeRGBA(0.2039, 0.7804, 0.349)
    fun purple(isDark: Boolean) = if (isDark) ThemeRGBA(0.749, 0.3529, 0.949) else ThemeRGBA(0.6863, 0.3216, 0.8706)

    /** `UIColor.tertiarySystemFill`: rgba(118,118,128) at 0.12 light / 0.24 dark. */
    fun tertiarySystemFill(isDark: Boolean) = ThemeRGBA(0.4627, 0.4627, 0.502, if (isDark) 0.24 else 0.12)
}

/**
 * Maps the semantic colours onto Material 3 roles. Translucent colours are composited over the
 * canvas, because Material derives its own alpha states from these roles.
 */
fun OpenCodeColors.toColorScheme(): ColorScheme {
    val v = variant
    val canvas = v.canvas
    val system = theme == AppTheme.System
    val accent = v.accent
    val strong = if (system) v.text else v.textStrong
    val textRgba = if (!system && highContrast) v.textStrong else v.text
    val secondary = (if (!system && highContrast) v.text else v.textWeak).composited(canvas)
    val outline = v.borderStrong.composited(canvas)
    val outlineVariant = (if ((!system && highContrast) || strongBorders) v.borderStrong else v.border).composited(canvas)
    val error = if (system) SystemColors.red(isDark) else v.diffRemovedText
    val container = v.canvasWeak.mixedInOklab(v.userBubble, 0.5)
    // Desktop floating surfaces (sheets, menus, popovers): white in light, a step up in dark.
    val floatingSurface = if (isDark) v.darkFloatingFill() else canvas
    val primaryContainer = canvas.mixedInOklab(accent, 0.22)
    val errorContainer = (if (system) SystemColors.red(isDark).withAlpha(0.14) else v.glassDiffRemovedFill).composited(canvas)
    val surfaceBright = if (isDark) v.surfaceStrong else canvas
    val surfaceDim = if (isDark) canvas else v.canvasWeak
    // System tints are designed for white labels, so the luminance pick applies only to theme
    // palettes.
    val white = ThemeRGBA(1.0, 1.0, 1.0)
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent.color,
        onPrimary = (if (system) white else accent.highContrastForeground).color,
        primaryContainer = primaryContainer.color,
        onPrimaryContainer = strong.color,
        inversePrimary = accent.mixedInOklab(canvas.highContrastForeground, 0.35).color,
        secondary = secondary.color,
        onSecondary = canvas.color,
        secondaryContainer = v.surfaceStrong.color,
        onSecondaryContainer = textRgba.color,
        tertiary = v.link.color,
        onTertiary = (if (system) white else v.link.highContrastForeground).color,
        tertiaryContainer = canvas.mixedInOklab(v.link, 0.22).color,
        onTertiaryContainer = strong.color,
        background = canvas.color,
        onBackground = textRgba.color,
        surface = canvas.color,
        onSurface = textRgba.color,
        surfaceVariant = v.userBubble.color,
        onSurfaceVariant = secondary.color,
        // Flat surfaces (desktop): no tonal-elevation tint; depth comes from hairlines.
        surfaceTint = Color.Transparent,
        inverseSurface = v.text.color,
        inverseOnSurface = canvas.color,
        error = error.color,
        onError = (if (system) white else error.highContrastForeground).color,
        errorContainer = errorContainer.color,
        onErrorContainer = textRgba.color,
        outline = outline.color,
        outlineVariant = outlineVariant.color,
        surfaceBright = surfaceBright.color,
        surfaceDim = surfaceDim.color,
        surfaceContainer = (if (system) container else floatingSurface).color,
        surfaceContainerHigh = v.userBubble.color,
        surfaceContainerHighest = v.surfaceStrong.color,
        surfaceContainerLow = (if (system) v.canvasWeak else floatingSurface).color,
        surfaceContainerLowest = canvas.color,
        scrim = Color.Black,
    )
}

/** The current [OpenCodeColors]; provided by [OpenCodeTheme]. Read via `OpenCodeTheme.colors`. */
val LocalOpenCodeColors = staticCompositionLocalOf { OpenCodeColors.resolve(AppTheme.System, isDark = false) }

/** Crisp dark or white foreground over this colour. */
fun Color.highContrastForeground(): Color =
    ThemeRGBA(red.toDouble(), green.toDouble(), blue.toDouble()).highContrastForeground.color
