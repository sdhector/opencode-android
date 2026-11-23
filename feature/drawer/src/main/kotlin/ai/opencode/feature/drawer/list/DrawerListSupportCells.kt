package ai.opencode.feature.drawer.list

import ai.opencode.feature.drawer.style.drawerCanvas
import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole

import ai.opencode.core.ui.AccessibilityFocusRequest
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.RollingNumberAlignment
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.feature.drawer.DrawerListFooter
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.catchup.DrawerCatchUpFinishedLabel
import ai.opencode.feature.drawer.motion.DrawerMotionTunables
import ai.opencode.feature.drawer.motion.DrawerShortcutMotion
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerSectionBreak
import ai.opencode.feature.drawer.style.DrawerSectionHeaderKind
import ai.opencode.feature.drawer.style.DrawerSectionTitle
import ai.opencode.feature.drawer.style.DrawerShortcutLabel
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.drawer.style.RecentRowMenu
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The list's rows that aren't sessions: the Pinned and Recents headers (or a gap or hairline
 * between sections), Catch Up and What’s New above them,
 * no sessions / no results, and a failed deletion. They are plain items of the same `LazyColumn`.
 */

/** Pinned or Recents (Ungrouped while the server has groups), as a header row or a gap or hairline between sections. */
@Composable
fun DrawerListHeaderCell(kind: DrawerSectionHeaderKind, pinDrag: DrawerPinDrag, modifier: Modifier = Modifier, ungrouped: Boolean = false) {
    val title = when {
        kind == DrawerSectionHeaderKind.Pinned -> stringResource(R.string.drawer_section_pinned)
        ungrouped -> stringResource(R.string.drawer_section_ungrouped)
        else -> stringResource(R.string.drawer_section_recents)
    }
    val key = if (kind == DrawerSectionHeaderKind.Pinned) DrawerPinDrag.ItemKey.PinnedHeader else DrawerPinDrag.ItemKey.RecentsHeader
    val density = LocalDensity.current
    val tag = if (kind == DrawerSectionHeaderKind.Pinned) "drawer-section-pinned" else "drawer-section-recents"
    val moved = modifier
        .fillMaxWidth()
        .graphicsLayer {
            translationY = pinDrag.translation(key) * density.density
            alpha = (if (kind == DrawerSectionHeaderKind.Pinned) pinDrag.pinnedHeaderAlpha else 1f) * pinDrag.alpha(key)
        }
    when (DrawerTunables.sectionBreak) {
        DrawerSectionBreak.Header -> Box(
            moved
                .padding(top = DrawerTunables.sectionAbove, bottom = DrawerTunables.sectionBelow)
                .heightIn(min = max(20f, DrawerTunables.sectionHeight.value).dp)
                .padding(start = DrawerMetrics.leadingEdge, end = DrawerMetrics.rowEdge)
                .testTag(tag)
                .clearAndSetSemantics {
                    contentDescription = title
                    heading()
                },
            contentAlignment = Alignment.BottomStart,
        ) {
            DrawerSectionTitle(title)
        }
        DrawerSectionBreak.Gap, DrawerSectionBreak.Hairline -> {
            val hairline = DrawerTunables.sectionBreak == DrawerSectionBreak.Hairline
            val border = OpenCodeTheme.colors.border
            Box(
                moved
                    .height(max(DrawerTunables.sectionGap.value, 1f).dp)
                    .testTag(tag)
                    .clearAndSetSemantics {
                        contentDescription = title
                        heading()
                    }
                    .drawBehind {
                        if (!hairline) return@drawBehind
                        val line = 1f // one physical pixel
                        val start = DrawerMetrics.leadingEdge.toPx()
                        val end = size.width - DrawerMetrics.rowEdge.toPx()
                        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                        val y = ((size.height - line) / 2).roundToInt().toFloat()
                        val (x0, x1) = if (rtl) DrawerMetrics.rowEdge.toPx() to size.width - start else start to end
                        drawLine(border, Offset(x0, y + line / 2), Offset(x1, y + line / 2), strokeWidth = line)
                    },
            )
        }
    }
}

/** Which shortcut a [DrawerListShortcutRow] is. */
enum class DrawerListShortcutKind { CatchUp, WhatsNew }

/**
 * Catch Up or What’s New above the sections. Each stays in the list while search is empty and
 * folds in and out with [AnimatedVisibility]; [animates] off lands the change in place.
 */
@Composable
fun DrawerListShortcutRow(
    kind: DrawerListShortcutKind,
    motion: DrawerShortcutMotion,
    shortcuts: DrawerListShortcuts,
    animates: Boolean,
    exitDelay: Double,
    reducedMotion: Boolean,
    openCatchUp: () -> Unit,
    markAllRead: () -> Unit,
    openWhatsNew: () -> Unit,
    hideWhatsNew: () -> Unit,
    modifier: Modifier = Modifier,
    returnFocus: AccessibilityFocusRequest? = null,
) {
    when (kind) {
        DrawerListShortcutKind.CatchUp -> {
            val count = if (shortcuts.catchUpCount > 0) shortcuts.catchUpCount else max(motion.displayedCatchUpCount, 1)
            val canMarkAllRead = if (shortcuts.catchUpCount > 0) shortcuts.canMarkAllRead else motion.displayedCanMarkAllRead
            // Finished, it stays All Caught Up while it rests and folds away.
            val finished = shortcuts.catchUpFinished || (shortcuts.catchUpCount == 0 && motion.finishStart != null)
            Collapse(shortcuts.showsCatchUp, animates, 0.0, reducedMotion, modifier.padding(top = DrawerTunables.shortcutsTop)) {
                if (finished) {
                    DrawerCatchUpFinishedLabel(
                        start = motion.finishStart,
                        fromCount = count,
                        reducedMotion = reducedMotion,
                        cornerRadius = DrawerMetrics.rowCornerRadius,
                    )
                } else {
                    CatchUpButton(count, canMarkAllRead, openCatchUp, markAllRead, returnFocus)
                }
            }
        }
        DrawerListShortcutKind.WhatsNew -> {
            val topSpec: AnimationSpec<Dp> = if (animates) MotionRole.Layout.animation(reduceMotion = reducedMotion) else snap()
            val top by animateDpAsState(if (shortcuts.showsCatchUp) DrawerTunables.shortcutsGap else DrawerTunables.shortcutsTop, topSpec)
            Collapse(shortcuts.showsWhatsNew, animates, exitDelay, reducedMotion, modifier.padding(top = top, bottom = DrawerTunables.shortcutsBelow)) {
                DrawerWhatsNewButton(action = openWhatsNew, onHide = hideWhatsNew)
            }
        }
    }
}

@Composable
private fun CatchUpButton(
    count: Int,
    canMarkAllRead: Boolean,
    openCatchUp: () -> Unit,
    markAllRead: () -> Unit,
    returnFocus: AccessibilityFocusRequest?,
) {
    val shape = RoundedCornerShape(DrawerMetrics.rowCornerRadius)
    val canvas = OpenCodeTheme.colors.drawerCanvas
    val catchUpTitle = stringResource(R.string.drawer_catch_up_title)
    val sessionCount = pluralStringResource(R.plurals.drawer_catch_up_session_count, count, count)
    val openLabel = stringResource(R.string.drawer_catch_up_open_action)
    val markAllReadLabel = stringResource(R.string.drawer_mark_all_read)
    RecentRowMenu(
        enabled = canMarkAllRead,
        onClick = openCatchUp,
        modifier = Modifier
            .testTag("drawer-catch-up")
            // Ahead of the clearing below, which would drop its marker.
            .accessibilityFocusRequest(returnFocus)
            .clearAndSetSemantics {
                contentDescription = catchUpTitle
                stateDescription = sessionCount
                role = Role.Button
                onClick(label = openLabel) { openCatchUp(); true }
                if (canMarkAllRead) customActions = listOf(CustomAccessibilityAction(markAllReadLabel) { markAllRead(); true })
            },
        items = { dismiss ->
            DropdownMenuItem(
                text = { Text(markAllReadLabel) },
                leadingIcon = { OpenCodeIcon(SFSymbol.EnvelopeOpen, contentDescription = null) },
                onClick = {
                    dismiss()
                    markAllRead()
                },
            )
        },
    ) {
        DrawerShortcutLabel(
            title = catchUpTitle,
            systemImage = "rectangle.stack",
            modifier = Modifier.background(canvas, shape),
        ) {
            RollingNumber(
                value = count,
                style = DrawerListFonts.monospacedDigits(DrawerTunables.countFont.font),
                color = OpenCodeTheme.colors.secondaryText,
                alignment = RollingNumberAlignment.End,
            )
        }
    }
}

/**
 * The What’s New row: a gift, the title and an accent dot; holding it offers Hide. Drawn here
 * because the drawer owns the row.
 */
@Composable
fun DrawerWhatsNewButton(action: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(DrawerMetrics.rowCornerRadius)
    val canvas = OpenCodeTheme.colors.drawerCanvas
    val whatsNewTitle = stringResource(R.string.drawer_whats_new)
    val hideLabel = stringResource(R.string.drawer_whats_new_hide)
    RecentRowMenu(
        enabled = true,
        onClick = action,
        modifier = modifier
            .testTag("drawer-whats-new")
            .clearAndSetSemantics {
                contentDescription = whatsNewTitle
                role = Role.Button
                onClick { action(); true }
                customActions = listOf(CustomAccessibilityAction(hideLabel) { onHide(); true })
            },
        items = { dismiss ->
            DropdownMenuItem(
                text = { Text(hideLabel) },
                leadingIcon = { OpenCodeIcon(SFSymbol.EyeSlash, contentDescription = null) },
                onClick = {
                    dismiss()
                    onHide()
                },
            )
        },
    ) {
        DrawerShortcutLabel(title = whatsNewTitle, systemImage = "gift", modifier = Modifier.background(canvas, shape)) {
            OpenCodeIcon(
                SFSymbol.CircleFill,
                contentDescription = null,
                size = (11 * LocalDensity.current.fontScale).dp,
                tint = OpenCodeTheme.colors.signal,
                modifier = Modifier.height(18.dp),
            )
        }
    }
}

/**
 * The shortcut's fold: height from the top, a fade and a scale toward the top
 * ([DrawerMotionTunables.SHORTCUT_SCALE], not under Reduce Motion), on the layout spring. While it
 * leaves it takes no touches.
 */
@Composable
private fun Collapse(
    visible: Boolean,
    animates: Boolean,
    exitDelay: Double,
    reducedMotion: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val top = TransformOrigin(0.5f, 0f)
    val scale = if (reducedMotion) 1f else DrawerMotionTunables.SHORTCUT_SCALE
    val enter = if (!animates) {
        EnterTransition.None
    } else {
        expandVertically(MotionRole.Layout.animation(reduceMotion = reducedMotion, visibilityThreshold = IntSize.VisibilityThreshold), Alignment.Top) +
            fadeIn(MotionRole.Layout.animation(reduceMotion = reducedMotion)) +
            scaleIn(MotionRole.Layout.animation(reduceMotion = reducedMotion), scale, top)
    }
    val exit = if (!animates) {
        ExitTransition.None
    } else {
        val phase = MotionPhase.Exit
        shrinkVertically(MotionRole.Layout.animation(phase, reducedMotion, exitDelay, IntSize.VisibilityThreshold), Alignment.Top) +
            fadeOut(MotionRole.Layout.animation(phase, reducedMotion, exitDelay)) +
            scaleOut(MotionRole.Layout.animation(phase, reducedMotion, exitDelay), scale, top)
    }
    AnimatedVisibility(visible, Modifier.fillMaxWidth(), enter, exit) {
        Box(
            modifier
                .padding(horizontal = DrawerMetrics.rowEdge)
                .then(if (visible) Modifier else Modifier.blockingTouches()),
        ) {
            content()
        }
    }
}

/** Takes the touches so nothing in the row reacts. */
private fun Modifier.blockingTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** No sessions, or no results for a search, on the text edge. */
@Composable
fun DrawerListEmptyRow(search: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .padding(start = DrawerMetrics.leadingEdge, end = DrawerMetrics.rowEdge)
            .testTag("drawer-empty")
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val colors = OpenCodeTheme.colors
        if (search.isEmpty()) {
            Text(stringResource(R.string.drawer_empty_title), style = OpenCodeTextStyles.headline, color = colors.text)
            Text(stringResource(R.string.drawer_empty_message), style = OpenCodeTextStyles.body, color = colors.secondaryText)
        } else {
            Text(stringResource(R.string.drawer_search_no_results_title, search), style = OpenCodeTextStyles.headline, color = colors.text)
            Text(stringResource(R.string.drawer_search_no_results_message), style = OpenCodeTextStyles.body, color = colors.secondaryText)
        }
    }
}

/**
 * Under the last row: a spinner while the next page is read, or the read that failed with Try
 * Again.
 */
@Composable
fun DrawerListFooterRow(footer: DrawerListFooter, retry: () -> Unit, modifier: Modifier = Modifier) {
    val loadingDescription = stringResource(R.string.drawer_load_more_description)
    when (footer) {
        DrawerListFooter.loading -> Box(
            modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.rowHeight)
                .testTag("drawer-load-more")
                .semantics { contentDescription = loadingDescription },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = OpenCodeTheme.colors.secondaryText)
        }
        DrawerListFooter.failed -> Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.rowHeight)
                .padding(start = DrawerMetrics.leadingEdge, end = DrawerMetrics.rowEdge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.drawer_load_more_failed),
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = retry, modifier = Modifier.testTag("drawer-load-more-retry")) {
                Text(stringResource(CoreR.string.common_try_again), style = OpenCodeTextStyles.footnote, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
