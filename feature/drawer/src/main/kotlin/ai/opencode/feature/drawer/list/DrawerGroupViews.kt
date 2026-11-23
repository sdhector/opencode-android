package ai.opencode.feature.drawer.list

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeAlertDialog
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.feature.drawer.GroupSharingStatus
import ai.opencode.feature.drawer.GroupStatus
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.groupMoveTargets
import ai.opencode.feature.drawer.groupPickerEntries
import ai.opencode.feature.drawer.groups.GroupActivityCoverage
import ai.opencode.feature.drawer.groups.GroupSharingPrompt
import ai.opencode.feature.drawer.groups.SessionGroups
import ai.opencode.feature.drawer.sharedGroups
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.selected
import ai.opencode.feature.drawer.style.DrawerDropSlotRow
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * Session Groups' list rows and dialogs. Every action here lands in Home as a plain group action;
 * Home decides whether it stays on the phone or goes to a shared server.
 */

/** What the group dialogs are asking about; the drawer shows one at a time. */
@Stable
class DrawerGroupEditor {
    sealed interface Request {
        /** A new group; with a [parentID], a subgroup of that root. */
        data class Create(val sessionIDs: List<String> = emptyList(), val parentID: String? = null) : Request
        data class Rename(val groupID: String, val name: String) : Request
        /** With [hasSubgroups] it only says what has to happen first. */
        data class Remove(val groupID: String, val name: String, val hasSubgroups: Boolean = false) : Request
        data class Move(val sessionIDs: List<String>) : Request
        /** Choose where a group goes: another root, or the top level. */
        data class MoveGroup(val groupID: String, val name: String) : Request
    }

    var request: Request? by mutableStateOf(null)
}

/** What a group's row does, as closures: the row never holds a store. */
class DrawerGroupHeaderActions(
    val open: () -> Unit,
    val rename: () -> Unit,
    val remove: () -> Unit,
    val moveUp: () -> Unit = {},
    val moveDown: () -> Unit = {},
    val newSubgroup: () -> Unit = {},
    val moveToParent: () -> Unit = {},
    /** The list is scrolling or flinging. */
    val isListMoving: () -> Boolean = { false },
    /** The held finger moved: the group lifts at this list `y` (dp) to be reordered ([DrawerGroupDrag]). */
    val beginDrag: (fingerY: Float) -> Unit = {},
    val moveDrag: (fingerY: Float, uptimeMillis: Long) -> Unit = { _, _ -> },
    /** The finger lifted at this list point (dp); [cancelled] when its stream was taken away. */
    val endDrag: (fingerX: Float, fingerY: Float, cancelled: Boolean) -> Unit = { _, _, _ -> },
    /** The header left the list mid-drag. */
    val dismantled: () -> Unit = {},
)

/**
 * A group's row, laid out as Archived's: its name, its rolled-up status in a slot that keeps its room,
 * and its size. A tap opens it. A hold opens its menu (Move Up, Move Down, New Subgroup, Move Group,
 * Rename, Remove), which TalkBack also offers as actions; moving the held finger lifts the row to
 * reorder it among its siblings.
 */
@Composable
fun DrawerListGroupHeaderCell(
    header: DrawerListGroupHeader,
    actions: DrawerGroupHeaderActions,
    pinDrag: DrawerPinDrag,
    modifier: Modifier = Modifier,
    listCoordinates: () -> LayoutCoordinates? = { null },
    reducedMotion: Boolean = false,
    increasedContrast: Boolean = false,
) {
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    val key = DrawerPinDrag.ItemKey.GroupHeader(header.id)
    val count = pluralStringResource(R.plurals.drawer_group_session_count, header.count, header.count)
    val followUpText = stringResource(R.string.drawer_follow_up_state)
    val statusText = groupStatusText(header.status).let { status ->
        when {
            !header.hasFollowUp || header.status == GroupStatus.followUp -> status
            status == null -> followUpText
            else -> "$status, $followUpText"
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.drawer_selection_more_options_description)
    val currentActions by rememberUpdatedState(actions)
    val hold = remember(header.id) { DrawerRowHoldState() }
    val rowCoordinates = remember(header.id) { arrayOfNulls<LayoutCoordinates>(1) }
    val groupDrag = pinDrag.groupDrag
    val lifted = groupDrag.draggingID == header.id
    val reorderable = header.canMoveUp || header.canMoveDown
    val hasMenu = reorderable || header.canRename || header.canRemove || header.canCreateSubgroup || header.canMoveToParent
    val raisedSurface = if (colors.isDark) MaterialTheme.colorScheme.surfaceContainerHigh else colors.canvas
    val customActions = groupCustomActions(header) { currentActions }

    DisposableEffect(header.id) {
        onDispose { currentActions.dismantled() }
    }

    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = pinDrag.translation(key) * density.density
                alpha = pinDrag.alpha(key)
                // The lifted row grows as a lifted session row does.
                val grow = if (lifted) DrawerLiftedRowView.apply(groupDrag.liftProgress).scale else 1f
                scaleX = grow
                scaleY = grow
            }
            .padding(horizontal = DrawerMetrics.rowEdge)
            .drawBehind {
                if (!lifted) return@drawBehind
                val look = DrawerLiftedRowView.apply(groupDrag.liftProgress)
                val surface = raisedSurface.copy(alpha = raisedSurface.alpha * groupDrag.liftProgress.coerceIn(0f, 1f))
                drawLiftedRowSurface(surface, DrawerMetrics.rowCornerRadius.toPx(), look)
            }
            .testTag("drawer-group-${header.id}"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.rowHeight)
                .clip(RoundedCornerShape(DrawerMetrics.rowCornerRadius))
                .onGloballyPositioned { rowCoordinates[0] = it }
                .drawerRowInteraction(
                    interaction = DrawerRowInteraction(
                        sessionID = header.id,
                        reorderable = reorderable,
                        holdable = hasMenu,
                        select = { currentActions.open() },
                        pressed = {},
                        // The hold opens the menu; a drag growing out of it closes it again.
                        held = { menuOpen = !menuOpen },
                        isListMoving = { currentActions.isListMoving() },
                        beginDrag = { currentActions.beginDrag(it.y) },
                        moveDrag = { point, time -> currentActions.moveDrag(point.y, time) },
                        endDrag = { point, cancelled -> currentActions.endDrag(point.x, point.y, cancelled) },
                    ),
                    hold = hold,
                    list = listCoordinates,
                    row = { rowCoordinates[0] },
                )
                .padding(start = DrawerMetrics.textInset, end = DrawerMetrics.trailingInset)
                .clearAndSetSemantics {
                    contentDescription = "${header.name}, $count"
                    if (statusText != null) stateDescription = statusText
                    role = Role.Button
                    onClick { currentActions.open(); true }
                    if (hasMenu) onLongClick(menuLabel) { menuOpen = true; true }
                    this.customActions = customActions
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) {
                OpenCodeIcon(SFSymbol.Folder, contentDescription = null, tint = colors.secondaryText)
            }
            Text(
                header.name,
                style = DrawerTunables.rowFont.font,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            GroupStatusIndicator(header.id, header.status, reducedMotion, increasedContrast)
            Text(
                "${header.count}",
                style = DrawerTunables.rowFont.font.copy(fontFeatureSettings = "tnum"),
                color = colors.secondaryText,
            )
        }
        GroupMenu(expanded = menuOpen, header = header, onDismiss = { menuOpen = false }, actions = { currentActions })
    }
}

/**
 * Inside a group: back, its name and its size, above its sessions. A hold on the name opens the
 * group's Rename and Remove, which TalkBack also offers as actions.
 */
@Composable
fun DrawerListGroupTitleRow(
    header: DrawerListGroupHeader,
    onBack: () -> Unit,
    actions: DrawerGroupHeaderActions,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val count = pluralStringResource(R.plurals.drawer_group_session_count, header.count, header.count)
    var menuOpen by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.drawer_selection_more_options_description)
    val currentActions by rememberUpdatedState(actions)
    val titleHeader = header.copy(canMoveUp = false, canMoveDown = false)
    val hasMenu = titleHeader.canRename || titleHeader.canRemove || titleHeader.canCreateSubgroup || titleHeader.canMoveToParent
    val customActions = groupCustomActions(titleHeader) { currentActions }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerMetrics.rowEdge)
            .heightIn(min = DrawerMetrics.rowHeight)
            .testTag("drawer-group-title-${header.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeIconButton(
            SFSymbol.ChevronBackward,
            contentDescription = stringResource(CoreR.string.common_back),
            onClick = onBack,
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier.testTag("drawer-group-back"),
        )
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DrawerMetrics.rowHeight)
                    .then(if (hasMenu) Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { menuOpen = true }) } else Modifier)
                    .padding(start = 4.dp, end = DrawerMetrics.trailingInset)
                    .clearAndSetSemantics {
                        contentDescription = "${header.name}, $count"
                        heading()
                        if (hasMenu) onLongClick(menuLabel) { menuOpen = true; true }
                        this.customActions = customActions
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    header.name,
                    style = DrawerTunables.rowFont.font.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${header.count}",
                    style = DrawerTunables.rowFont.font.copy(fontFeatureSettings = "tnum"),
                    color = colors.secondaryText,
                )
            }
            GroupMenu(expanded = menuOpen, header = titleHeader, onDismiss = { menuOpen = false }, actions = { currentActions })
        }
    }
}

/** A group's management as TalkBack actions: the same entries as its menu. */
@Composable
private fun groupCustomActions(header: DrawerListGroupHeader, actions: () -> DrawerGroupHeaderActions): List<CustomAccessibilityAction> {
    val moveUp = stringResource(R.string.drawer_move_up)
    val moveDown = stringResource(R.string.drawer_move_down)
    val newSubgroup = stringResource(R.string.drawer_group_new_subgroup)
    val moveGroup = stringResource(R.string.drawer_group_move_to)
    val rename = stringResource(R.string.drawer_group_rename)
    val remove = stringResource(R.string.drawer_group_remove)
    return buildList {
        if (header.canMoveUp) add(CustomAccessibilityAction(moveUp) { actions().moveUp(); true })
        if (header.canMoveDown) add(CustomAccessibilityAction(moveDown) { actions().moveDown(); true })
        if (header.canCreateSubgroup) add(CustomAccessibilityAction(newSubgroup) { actions().newSubgroup(); true })
        if (header.canMoveToParent) add(CustomAccessibilityAction(moveGroup) { actions().moveToParent(); true })
        if (header.canRename) add(CustomAccessibilityAction(rename) { actions().rename(); true })
        if (header.canRemove) add(CustomAccessibilityAction(remove) { actions().remove(); true })
    }
}

/** A group's menu: Move Up and Move Down while it can trade places, New Subgroup and Move Group where groups nest, then Rename and Remove. */
@Composable
private fun GroupMenu(expanded: Boolean, header: DrawerListGroupHeader, onDismiss: () -> Unit, actions: () -> DrawerGroupHeaderActions) {
    val colors = OpenCodeTheme.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.testTag("drawer-group-menu")) {
        GroupMenuItems(header, onDismiss, actions)
        if (header.canRename) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_group_rename)) },
                leadingIcon = { OpenCodeIcon(SFSymbol.Pencil, contentDescription = null) },
                onClick = { onDismiss(); actions().rename() },
            )
        }
        if (header.canRemove) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_group_remove), color = colors.destructive) },
                leadingIcon = { OpenCodeIcon(SFSymbol.Trash, contentDescription = null, tint = colors.destructive) },
                onClick = { onDismiss(); actions().remove() },
            )
        }
    }
}

@Composable
private fun GroupMenuItems(header: DrawerListGroupHeader, onDismiss: () -> Unit, actions: () -> DrawerGroupHeaderActions) {
    if (header.canMoveUp) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.drawer_move_up)) },
            leadingIcon = { OpenCodeIcon(SFSymbol.ArrowUp, contentDescription = null) },
            onClick = { onDismiss(); actions().moveUp() },
        )
    }
    if (header.canMoveDown) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.drawer_move_down)) },
            leadingIcon = { OpenCodeIcon(SFSymbol.ArrowDown, contentDescription = null) },
            onClick = { onDismiss(); actions().moveDown() },
        )
    }
    if (header.canCreateSubgroup) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.drawer_group_new_subgroup)) },
            leadingIcon = { OpenCodeIcon(SFSymbol.FolderBadgePlus, contentDescription = null) },
            onClick = { onDismiss(); actions().newSubgroup() },
        )
    }
    if (header.canMoveToParent) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.drawer_group_move_to)) },
            leadingIcon = { OpenCodeIcon(SFSymbol.ArrowTurnDownRight, contentDescription = null) },
            onClick = { onDismiss(); actions().moveToParent() },
        )
    }
}

/** The rolled-up status's words for TalkBack, the session rows' own; null with nothing to say. */
@Composable
private fun groupStatusText(status: GroupStatus): String? = when (status) {
    GroupStatus.attention -> stringResource(R.string.drawer_row_needs_input_state)
    GroupStatus.running -> stringResource(R.string.drawer_row_working_state)
    GroupStatus.failed -> stringResource(R.string.drawer_row_unsent_failed_state)
    GroupStatus.unread -> stringResource(R.string.drawer_row_unread_state)
    GroupStatus.followUp -> stringResource(R.string.drawer_follow_up_state)
    GroupStatus.none -> null
}

/**
 * A group's status in the session rows' marks (the Working ring, the attention or unread dot, the
 * failed send's mark), in a slot that always keeps its room so the count never moves. A change for
 * the same group fades on `MotionRole.MicroIn`, as a row's does; Reduce Motion keeps the fade and stops the ring.
 */
@Composable
private fun GroupStatusIndicator(groupID: String, status: GroupStatus, reducedMotion: Boolean, increasedContrast: Boolean) {
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    val previousID = remember { arrayOf(groupID) }
    val animates = previousID[0] == groupID
    previousID[0] = groupID
    val phase = if (status != GroupStatus.none) MotionPhase.Enter else MotionPhase.Exit
    val spec = if (animates) MotionRole.MicroIn.animation<Float>(phase, reducedMotion) else snap()
    val ringAlpha by animateFloatAsState(if (status == GroupStatus.running) 1f else 0f, spec, label = "group-ring")
    val dotAlpha by animateFloatAsState(if (status == GroupStatus.attention || status == GroupStatus.unread) 1f else 0f, spec, label = "group-dot")
    val failedAlpha by animateFloatAsState(if (status == GroupStatus.failed) 1f else 0f, spec, label = "group-failed")
    val followUpAlpha by animateFloatAsState(if (status == GroupStatus.followUp) 1f else 0f, spec, label = "group-follow-up")
    Box(Modifier.size(DrawerListSessionCell.indicatorSize).testTag("drawer-group-status-$groupID"), contentAlignment = Alignment.Center) {
        DrawerListActivityRing(
            isSpinning = status == GroupStatus.running && !reducedMotion,
            opaqueTail = increasedContrast,
            modifier = Modifier.size(14.dp).graphicsLayer { alpha = ringAlpha },
        )
        OpenCodeIcon(
            SFSymbol.CircleFill,
            contentDescription = null,
            size = (11 * density.fontScale).dp,
            tint = if (status == GroupStatus.attention) colors.warning else colors.signal,
            modifier = Modifier.graphicsLayer { alpha = dotAlpha },
        )
        OpenCodeIcon(
            "exclamationmark.circle.fill",
            contentDescription = null,
            size = (15 * density.fontScale).dp,
            tint = colors.danger,
            modifier = Modifier.graphicsLayer { alpha = failedAlpha },
        )
        FollowUpDot(Modifier.graphicsLayer { alpha = followUpAlpha })
    }
}

/**
 * The list's move between levels, the Archived page's shared axis X: going into a group the new
 * level arrives from the right, coming back from the left. [back] follows a predictive back drag
 * from 0 to 1.
 */
@Stable
internal class DrawerLevelTransition {
    val progress = Animatable(1f)
    val back = Animatable(0f)
    var direction: Float by mutableFloatStateOf(1f)
}

/** The transition for the list's [depth] (0: the root). The level shown first arrives at rest. */
@Composable
internal fun rememberDrawerLevelTransition(depth: Int, reduceMotion: Boolean): DrawerLevelTransition {
    val transition = remember { DrawerLevelTransition() }
    val shown = remember { intArrayOf(depth) }
    LaunchedEffect(depth) {
        val previous = shown[0]
        if (previous == depth) return@LaunchedEffect
        shown[0] = depth
        transition.direction = if (depth > previous) 1f else -1f
        transition.progress.snapTo(0f)
        transition.back.snapTo(0f)
        transition.progress.animateTo(1f, MotionRole.Layout.animation(MotionPhase.Enter, reduceMotion))
    }
    return transition
}

private val LevelSlide = 30.dp

/** The list as its level arrives: a short slide and fade (only the fade under Reduce Motion); a back drag eases it toward the edge. */
internal fun Modifier.drawerLevel(transition: DrawerLevelTransition, reduceMotion: Boolean): Modifier = graphicsLayer {
    val p = transition.progress.value
    val back = (transition.back.value / 0.5f).coerceIn(0f, 1f)
    if (p >= 1f && back == 0f) return@graphicsLayer
    alpha = p * (1f - 0.3f * back)
    if (reduceMotion) return@graphicsLayer
    translationX = transition.direction * LevelSlide.toPx() * (1f - p) + 24.dp.toPx() * back
}

/** The New Group row's sharing menu, as closures. */
class DrawerGroupSharingActions(val share: () -> Unit, val import: () -> Unit, val stop: () -> Unit)

/**
 * The row that ends the group section: New Group. While there are groups it ends in a sort button
 * (Manual or Last Activity, this device only); on a server that can share groups, in a sharing
 * button whose menu turns sharing on or off and imports this phone's groups.
 */
@Composable
fun DrawerListNewGroupRow(
    pinDrag: DrawerPinDrag,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharing: GroupSharingStatus? = null,
    sharingActions: DrawerGroupSharingActions? = null,
    sort: DrawerGroupSort? = null,
    onSort: (byActivity: Boolean) -> Unit = {},
) {
    val shares = sharing != null && sharingActions != null
    if (!shares && sort == null) {
        NewGroupRow(pinDrag, onClick, modifier)
        return
    }
    val density = LocalDensity.current
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = pinDrag.translation(DrawerPinDrag.ItemKey.NewGroup) * density.density
                alpha = pinDrag.alpha(DrawerPinDrag.ItemKey.NewGroup)
            }
            .padding(end = DrawerMetrics.rowEdge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NewGroupRow(pinDrag = null, onClick = onClick, modifier = Modifier.weight(1f))
        if (sort != null) GroupSortButton(sort, onSort)
        if (sharing != null && sharingActions != null) GroupSharingButton(sharing, sharingActions)
    }
}

/** Manual (the saved order) or Last Activity; under Last Activity the menu says how much activity it can see. */
@Composable
private fun GroupSortButton(sort: DrawerGroupSort, onSort: (Boolean) -> Unit) {
    val colors = OpenCodeTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val manual = stringResource(R.string.drawer_groups_sort_manual)
    val activity = stringResource(R.string.drawer_groups_sort_activity)
    val note = when (sort.coverage) {
        GroupActivityCoverage.Complete -> null
        GroupActivityCoverage.Partial -> stringResource(R.string.drawer_groups_activity_partial)
        GroupActivityCoverage.Stale -> stringResource(R.string.drawer_groups_activity_stale)
        GroupActivityCoverage.LoadedOnly -> stringResource(R.string.drawer_groups_activity_loaded_only)
    }
    Box {
        OpenCodeIconButton(
            if (sort.byActivity) SFSymbol.Clock else SFSymbol.ListBullet,
            contentDescription = stringResource(R.string.drawer_groups_sort),
            onClick = { menuOpen = true },
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier
                .testTag("drawer-group-sort")
                .semantics { stateDescription = if (sort.byActivity) activity else manual },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.testTag("drawer-group-sort-menu")) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_groups_sort), color = colors.secondaryText) },
                onClick = {},
                enabled = false,
            )
            for ((byActivity, title) in listOf(false to manual, true to activity)) {
                DropdownMenuItem(
                    text = { Text(title) },
                    leadingIcon = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (sort.byActivity == byActivity) OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null)
                        }
                    },
                    onClick = { menuOpen = false; onSort(byActivity) },
                    modifier = Modifier.semantics { selected = sort.byActivity == byActivity },
                )
            }
            if (sort.byActivity && note != null) {
                DropdownMenuItem(
                    text = { Text(note, style = OpenCodeTextStyles.footnote, color = colors.secondaryText) },
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag("drawer-group-activity-note"),
                )
            }
        }
    }
}

@Composable
private fun GroupSharingButton(sharing: GroupSharingStatus, actions: DrawerGroupSharingActions) {
    val colors = OpenCodeTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val state = when {
        sharing.isOffline -> stringResource(R.string.drawer_groups_offline_state)
        sharing.isShared -> stringResource(R.string.drawer_groups_shared_state)
        else -> null
    }
    val label = stringResource(R.string.drawer_groups_sharing_options)
    Box {
        OpenCodeIconButton(
            when {
                sharing.isOffline -> SFSymbol.NetworkSlash
                sharing.isShared -> SFSymbol.LaptopcomputerAndIphone
                else -> SFSymbol.Ellipsis
            },
            contentDescription = label,
            onClick = { menuOpen = true },
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier
                .testTag("drawer-group-sharing")
                .then(if (state == null) Modifier else Modifier.semantics { stateDescription = state }),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (state != null) {
                DropdownMenuItem(text = { Text(state, color = colors.secondaryText) }, onClick = {}, enabled = false)
            }
            if (!sharing.isShared) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_groups_share)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.LaptopcomputerAndIphone, contentDescription = null) },
                    onClick = { menuOpen = false; actions.share() },
                )
            }
            if (sharing.canImport) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_groups_import)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.SquareAndArrowUp, contentDescription = null) },
                    onClick = { menuOpen = false; actions.import() },
                )
            }
            if (sharing.isShared) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_groups_stop_sharing)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.XmarkCircle, contentDescription = null) },
                    onClick = { menuOpen = false; actions.stop() },
                )
            }
        }
    }
}

@Composable
private fun NewGroupRow(pinDrag: DrawerPinDrag?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    val label = stringResource(R.string.drawer_group_new)
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (pinDrag == null) Modifier else Modifier.graphicsLayer {
                    translationY = pinDrag.translation(DrawerPinDrag.ItemKey.NewGroup) * density.density
                    alpha = pinDrag.alpha(DrawerPinDrag.ItemKey.NewGroup)
                },
            )
            .padding(start = DrawerMetrics.rowEdge, end = if (pinDrag == null) 0.dp else DrawerMetrics.rowEdge)
            .clip(RoundedCornerShape(DrawerMetrics.rowCornerRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .heightIn(min = DrawerMetrics.rowHeight)
            .padding(start = DrawerMetrics.textInset, end = DrawerMetrics.trailingInset)
            .testTag("drawer-new-group")
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { onClick(); true }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpenCodeIcon(SFSymbol.FolderBadgePlus, contentDescription = null, tint = colors.secondaryText)
        Text(label, style = DrawerTunables.rowFont.font, color = colors.secondaryText)
    }
}

/** The section a dragged row would land in, tinted across its header and sessions; beneath the rows, which stay clear of it. */
@Composable
internal fun DrawerSectionDropOverlay(pinDrag: DrawerPinDrag) {
    val target = pinDrag.dropTarget ?: return
    val density = LocalDensity.current.density
    Box(
        Modifier
            .offset { IntOffset(0, (target.top * density).roundToInt()) }
            .fillMaxWidth()
            .padding(horizontal = DrawerMetrics.rowEdge)
            .height(target.height.dp)
            .background(OpenCodeTheme.colors.signal.copy(alpha = 0.16f), RoundedCornerShape(DrawerMetrics.rowCornerRadius))
            .testTag(if (target.groupID == null) "drawer-recents-drop-target" else "drawer-group-drop-target"),
    )
}

/** Recents' only row while a pinned or grouped row is lifted and Recents has none. Takes no touches. */
@Composable
fun DrawerRecentsDropSlot(pinDrag: DrawerPinDrag, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    val label = stringResource(R.string.drawer_drop_to_recents)
    val target = pinDrag.dropTarget
    val hovered = target != null && target.groupID == null
    DrawerDropSlotRow(
        label = label,
        openProgress = 1f,
        hoverProgress = if (hovered) 1f else 0f,
        modifier = modifier
            .graphicsLayer {
                translationY = pinDrag.translation(DrawerPinDrag.ItemKey.RecentsDrop) * density.density
                alpha = pinDrag.alpha(DrawerPinDrag.ItemKey.RecentsDrop)
            }
            .testTag("drawer-recents-drop-slot")
            .clearAndSetSemantics { contentDescription = label },
    ) { isHovered ->
        OpenCodeIcon(SFSymbol.Clock, contentDescription = null, tint = if (isHovered) colors.accent else colors.secondaryText)
    }
}

/** Shows the dialog [editor] is asking for: name a group, confirm its removal, or choose a group for sessions. */
@Composable
internal fun DrawerGroupDialogs(store: StoreOf<HomeFeature.State, HomeFeature.Action>, editor: DrawerGroupEditor) {
    GroupSharingDialogs(store)
    val shared by store.observe { it.sharedGroups != null }
    val request = editor.request ?: return
    val dismiss = { editor.request = null }
    when (request) {
        is DrawerGroupEditor.Request.Create -> GroupNameDialog(
            title = stringResource(if (request.parentID == null) R.string.drawer_group_new else R.string.drawer_group_new_subgroup),
            initialName = "",
            confirm = stringResource(CoreR.string.common_add),
            onDismiss = dismiss,
            onConfirm = { name ->
                store.send(HomeFeature.Action.CreateGroup(name, request.sessionIDs, request.parentID))
                dismiss()
            },
        )
        is DrawerGroupEditor.Request.Remove -> if (request.hasSubgroups) {
            OpenCodeAlertDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(R.string.drawer_group_remove_title, request.name)) },
                text = { Text(stringResource(R.string.drawer_group_remove_parent_message)) },
                confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(CoreR.string.common_ok)) } },
                modifier = Modifier.testTag("drawer-group-remove-blocked"),
            )
        } else {
            RemoveGroupDialog(store, request, shared, dismiss)
        }
        is DrawerGroupEditor.Request.MoveGroup -> GroupParentPickerDialog(store, request, onDismiss = dismiss)
        is DrawerGroupEditor.Request.Rename -> GroupNameDialog(
            title = stringResource(R.string.drawer_group_rename),
            initialName = request.name,
            confirm = stringResource(CoreR.string.common_save),
            onDismiss = dismiss,
            onConfirm = { name ->
                store.send(HomeFeature.Action.RenameGroup(request.groupID, name))
                dismiss()
            },
        )
        is DrawerGroupEditor.Request.Move -> GroupPickerDialog(store, request.sessionIDs, editor, onDismiss = dismiss)
    }
}

@Composable
private fun RemoveGroupDialog(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    request: DrawerGroupEditor.Request.Remove,
    shared: Boolean,
    dismiss: () -> Unit,
) {
    OpenCodeAlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.drawer_group_remove_title, request.name)) },
        text = { Text(stringResource(if (shared) R.string.drawer_group_remove_shared_message else R.string.drawer_group_remove_message)) },
        confirmButton = {
            TextButton(onClick = {
                store.send(HomeFeature.Action.RemoveGroup(request.groupID))
                dismiss()
            }) {
                Text(stringResource(CoreR.string.common_remove), color = OpenCodeTheme.colors.destructive)
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(CoreR.string.common_cancel)) } },
        modifier = Modifier.testTag("drawer-group-remove-confirmation"),
    )
}

/** Where a group can go: the top level while it's a subgroup, and each root that can hold it. */
@Composable
private fun GroupParentPickerDialog(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    request: DrawerGroupEditor.Request.MoveGroup,
    onDismiss: () -> Unit,
) {
    val targets by store.observe { state -> state.groupMoveTargets(request.groupID) }
    val colors = OpenCodeTheme.colors
    val topLevel = stringResource(R.string.drawer_group_top_level)
    OpenCodeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_group_move_to_title, request.name)) },
        text = {
            Column(Modifier.testTag("drawer-group-parent-picker")) {
                for ((parentID, name) in targets) {
                    PickerRow(name ?: topLevel, if (parentID == null) SFSymbol.ListBullet else SFSymbol.Folder, colors.text) {
                        store.send(HomeFeature.Action.MoveGroupToParent(request.groupID, parentID))
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_cancel)) } },
    )
}

/** The sharing dialog Home is asking: turn sharing on (and maybe import), import, or stop. */
@Composable
private fun GroupSharingDialogs(store: StoreOf<HomeFeature.State, HomeFeature.Action>) {
    val prompt by store.observe { it.groupSync.prompt }
    val dismiss = { store.send(HomeFeature.Action.GroupSharingPromptDismissed) }
    val cancel = @Composable { TextButton(onClick = dismiss) { Text(stringResource(CoreR.string.common_cancel)) } }
    when (val current = prompt) {
        null, GroupSharingPrompt.Checking -> Unit
        is GroupSharingPrompt.Share -> OpenCodeAlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.drawer_groups_share_title)) },
            text = {
                val local = current.localGroupCount
                Text(
                    when {
                        current.canImport -> pluralStringResource(R.plurals.drawer_groups_share_import_message, local, local)
                        local > 0 -> pluralStringResource(R.plurals.drawer_groups_share_replace_message, local, local)
                        else -> stringResource(R.string.drawer_groups_share_message)
                    },
                )
            },
            confirmButton = {
                if (current.canImport) {
                    TextButton(onClick = { store.send(HomeFeature.Action.ShareGroupsConfirmed(importsLocal = true)) }) {
                        Text(stringResource(R.string.drawer_groups_share_and_import))
                    }
                } else {
                    TextButton(onClick = { store.send(HomeFeature.Action.ShareGroupsConfirmed(importsLocal = false)) }) {
                        Text(stringResource(R.string.drawer_groups_share_confirm))
                    }
                }
            },
            dismissButton = {
                Row {
                    cancel()
                    if (current.canImport) {
                        TextButton(onClick = { store.send(HomeFeature.Action.ShareGroupsConfirmed(importsLocal = false)) }) {
                            Text(stringResource(R.string.drawer_groups_share_only))
                        }
                    }
                }
            },
            modifier = Modifier.testTag("drawer-groups-share-confirmation"),
        )
        is GroupSharingPrompt.Import -> OpenCodeAlertDialog(
            onDismissRequest = dismiss,
            title = { Text(pluralStringResource(R.plurals.drawer_groups_import_title, current.localGroupCount, current.localGroupCount)) },
            text = { Text(stringResource(R.string.drawer_groups_import_message)) },
            confirmButton = {
                TextButton(onClick = { store.send(HomeFeature.Action.ImportLocalGroupsConfirmed) }) {
                    Text(stringResource(R.string.drawer_groups_import_confirm))
                }
            },
            dismissButton = cancel,
            modifier = Modifier.testTag("drawer-groups-import-confirmation"),
        )
        GroupSharingPrompt.Stop -> OpenCodeAlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.drawer_groups_stop_title)) },
            text = { Text(stringResource(R.string.drawer_groups_stop_message)) },
            confirmButton = {
                TextButton(onClick = { store.send(HomeFeature.Action.StopSharingGroupsConfirmed) }) {
                    Text(stringResource(R.string.drawer_groups_stop_confirm))
                }
            },
            dismissButton = cancel,
            modifier = Modifier.testTag("drawer-groups-stop-confirmation"),
        )
    }
}

@Composable
private fun GroupNameDialog(
    title: String,
    initialName: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val valid = SessionGroups.normalizedName(name) != null
    OpenCodeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OpenCodeTextField(
                value = name,
                onValueChange = { name = it.take(SessionGroups.MAX_NAME_LENGTH) },
                placeholder = stringResource(R.string.drawer_group_name_placeholder),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("drawer-group-name"),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_cancel)) } },
    )
}

@Composable
private fun GroupPickerDialog(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    sessionIDs: List<String>,
    editor: DrawerGroupEditor,
    onDismiss: () -> Unit,
) {
    val path = stringResource(R.string.drawer_group_path)
    val groups by store.observe { state -> state.groupPickerEntries { parent, name -> String.format(path, parent, name) } }
    val colors = OpenCodeTheme.colors
    OpenCodeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_group_move)) },
        text = {
            Column(Modifier.testTag("drawer-group-picker")) {
                for (group in groups) {
                    PickerRow(group.name, SFSymbol.Folder, colors.text, indented = group.isSubgroup) {
                        store.send(HomeFeature.Action.MoveSessionsToGroup(sessionIDs, group.id))
                        onDismiss()
                    }
                }
                PickerRow(stringResource(R.string.drawer_group_new), SFSymbol.FolderBadgePlus, colors.secondaryText) {
                    editor.request = DrawerGroupEditor.Request.Create(sessionIDs)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_cancel)) } },
    )
}

@Composable
private fun PickerRow(
    title: String,
    symbol: SFSymbol,
    tint: androidx.compose.ui.graphics.Color,
    indented: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DrawerMetrics.rowCornerRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .padding(start = if (indented) 28.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OpenCodeIcon(symbol, contentDescription = null, tint = tint)
        Text(title, style = DrawerTunables.rowFont.font, color = tint, maxLines = 1)
    }
}
