package ai.opencode.feature.drawer.list

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.FollowUpAction
import ai.opencode.feature.drawer.FollowUpRow
import ai.opencode.feature.drawer.FollowUpUndo
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.followUpRows
import ai.opencode.feature.drawer.followup.FollowUpSnooze
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.drawer.style.drawerCanvas
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Follow Up's mark: an outlined dot, apart from unread's filled one. */
@Composable
internal fun FollowUpDot(modifier: Modifier = Modifier) {
    val size = (11 * LocalDensity.current.fontScale).dp
    Box(modifier.size(size).border(2.dp, OpenCodeTheme.colors.followUp, CircleShape))
}

/** Follow Up and its pending count, under Catch Up while this server has follow-ups; a tap opens the Follow Up page. */
@Composable
internal fun FollowUpEntryRow(count: Int, onClick: () -> Unit) {
    val colors = OpenCodeTheme.colors
    val font = DrawerTunables.rowFont.font
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerMetrics.rowEdge)
            .clip(RoundedCornerShape(DrawerMetrics.rowCornerRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = DrawerMetrics.rowHeight)
            .padding(start = DrawerMetrics.textInset, end = DrawerMetrics.trailingInset)
            .testTag("drawer-follow-up"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) { FollowUpDot() }
        Text(stringResource(R.string.drawer_follow_up), style = font, color = colors.text, modifier = Modifier.weight(1f))
        if (count > 0) Text("$count", style = font.copy(fontFeatureSettings = "tnum"), color = colors.secondaryText)
    }
}

/**
 * The Follow Up page over the drawer's list, laid out as Archived's: the pending sessions, newest
 * reply first, each with Mark Done and Snooze; then Snoozed, soonest first, each with its date,
 * Change Date and Bring Back Now. A tap opens the session; nothing here reads or archives it.
 */
@Composable
internal fun FollowUpPage(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    open: (sessionID: String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val rows by store.observe { it.followUpRows() }
    val reduceMotion = rememberReduceMotion()
    LaunchedEffect(Unit) { store.send(HomeFeature.Action.FollowUp(FollowUpAction.PageShown)) }
    var picking by remember { mutableStateOf<String?>(null) }
    // Done, Snooze and Bring Back move rows between sections: the neighbours slide.
    fun LazyItemScope.itemModifier(): Modifier = Modifier.animateItem(
        fadeInSpec = MotionRole.MicroIn.animation(reduceMotion = reduceMotion),
        placementSpec = MotionRole.Layout.animation(reduceMotion = reduceMotion, visibilityThreshold = IntOffset.VisibilityThreshold),
        fadeOutSpec = MotionRole.MicroOut.animation(MotionPhase.Exit, reduceMotion),
    )
    Column(modifier.fillMaxSize().background(colors.drawerCanvas).padding(top = contentPadding.calculateTopPadding())) {
        Box(Modifier.fillMaxWidth().padding(horizontal = DrawerMetrics.rowEdge, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                OpenCodeIconButton(SFSymbol.ChevronBackward, contentDescription = stringResource(CoreR.string.common_back), onClick = onBack, style = OpenCodeIconButtonStyle.Standard)
                Text(
                    stringResource(R.string.drawer_follow_up),
                    style = DrawerTunables.titleFont.font.copy(fontWeight = FontWeight.Bold),
                    color = colors.text,
                    modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("follow-up-list"), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
            items(rows.pending, key = { "pending-${it.id}" }) { row ->
                FollowUpPendingRow(
                    row = row,
                    open = { open(row.id) },
                    done = { store.send(HomeFeature.Action.FollowUp(FollowUpAction.DoneTapped(listOf(row.id)))) },
                    snooze = { store.send(HomeFeature.Action.FollowUp(FollowUpAction.SnoozeTapped(listOf(row.id), it))) },
                    pickDate = { picking = row.id },
                    modifier = itemModifier(),
                )
            }
            if (rows.snoozed.isNotEmpty()) {
                item(key = "snoozed-header") {
                    Text(
                        stringResource(R.string.drawer_follow_up_snoozed),
                        style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.secondaryText,
                        modifier = itemModifier()
                            .fillMaxWidth()
                            .padding(horizontal = InboxSidebarMetrics.edge)
                            .padding(top = 20.dp, bottom = 6.dp)
                            .semantics { heading() },
                    )
                }
            }
            items(rows.snoozed, key = { "snoozed-${it.id}" }) { row ->
                FollowUpSnoozedRow(
                    row = row,
                    open = { open(row.id) },
                    snooze = { store.send(HomeFeature.Action.FollowUp(FollowUpAction.SnoozeTapped(listOf(row.id), it))) },
                    pickDate = { picking = row.id },
                    bringBack = { store.send(HomeFeature.Action.FollowUp(FollowUpAction.BringBackTapped(row.id))) },
                    modifier = itemModifier(),
                )
            }
            if (rows.isEmpty) {
                item(key = "follow-up-empty") {
                    Text(
                        stringResource(R.string.drawer_follow_up_empty),
                        style = OpenCodeTextStyles.body,
                        color = colors.secondaryText,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = InboxSidebarMetrics.edge, vertical = 24.dp),
                    )
                }
            }
        }
    }
    picking?.let { id ->
        FollowUpDatePicker(
            onPick = { date ->
                picking = null
                store.send(HomeFeature.Action.FollowUp(FollowUpAction.SnoozeTapped(listOf(id), FollowUpSnooze.On(date))))
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun FollowUpPendingRow(
    row: FollowUpRow,
    open: () -> Unit,
    done: () -> Unit,
    snooze: (FollowUpSnooze) -> Unit,
    pickDate: () -> Unit,
    modifier: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    FollowUpRowLayout(row, detail = null, open = open, modifier = modifier) {
        OpenCodeIconButton(SFSymbol.Checkmark, contentDescription = stringResource(R.string.drawer_follow_up_mark_done), onClick = done, style = OpenCodeIconButtonStyle.Standard)
        Box {
            OpenCodeIconButton(SFSymbol.Clock, contentDescription = stringResource(R.string.drawer_follow_up_snooze), onClick = { menuOpen = true }, style = OpenCodeIconButtonStyle.Standard)
            FollowUpSnoozeMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, snooze = snooze, pickDate = pickDate)
        }
    }
}

@Composable
private fun FollowUpSnoozedRow(
    row: FollowUpRow,
    open: () -> Unit,
    snooze: (FollowUpSnooze) -> Unit,
    pickDate: () -> Unit,
    bringBack: () -> Unit,
    modifier: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    val until = row.entry.snoozedUntil?.let { stringResource(R.string.drawer_follow_up_until, followUpDateText(Instant.ofEpochMilli(it))) }
    FollowUpRowLayout(row, detail = until, open = open, modifier = modifier) {
        Box {
            OpenCodeIconButton(
                SFSymbol.Ellipsis,
                contentDescription = stringResource(R.string.drawer_selection_more_options_description),
                onClick = { menuOpen = true },
                style = OpenCodeIconButtonStyle.Standard,
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_follow_up_change_date)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.Calendar, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        changing = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_follow_up_bring_back)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.ArrowCounterclockwise, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        bringBack()
                    },
                )
            }
            FollowUpSnoozeMenu(expanded = changing, onDismiss = { changing = false }, snooze = snooze, pickDate = pickDate)
        }
    }
}

@Composable
private fun FollowUpRowLayout(
    row: FollowUpRow,
    detail: String?,
    open: () -> Unit,
    modifier: Modifier,
    actions: @Composable () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val session = row.session
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = session != null, role = Role.Button, onClick = open)
            .heightIn(min = 56.dp)
            .padding(start = InboxSidebarMetrics.edge, end = 4.dp)
            .testTag("follow-up-row-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                session?.title ?: stringResource(R.string.drawer_follow_up_loading_title),
                style = OpenCodeTextStyles.body,
                color = if (session == null) colors.secondaryText else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) Text(detail, style = OpenCodeTextStyles.footnote, color = colors.secondaryText, maxLines = 1)
        }
        actions()
    }
}

/** Snooze's choices, as a menu from the button or row that asked. */
@Composable
internal fun FollowUpSnoozeMenu(expanded: Boolean, onDismiss: () -> Unit, snooze: (FollowUpSnooze) -> Unit, pickDate: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.drawer_follow_up_snooze_tomorrow)) },
            onClick = {
                onDismiss()
                snooze(FollowUpSnooze.Tomorrow)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.drawer_follow_up_snooze_next_week)) },
            onClick = {
                onDismiss()
                snooze(FollowUpSnooze.NextWeek)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.drawer_follow_up_snooze_pick_date)) },
            leadingIcon = { OpenCodeIcon(SFSymbol.Calendar, contentDescription = null) },
            onClick = {
                onDismiss()
                pickDate()
            },
        )
    }
}

/** Snooze Follow Up from the selection's menu: the same choices as the page's Snooze, as a dialog. */
@Composable
internal fun FollowUpSnoozeDialog(onSnooze: (FollowUpSnooze) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        FollowUpDatePicker(onPick = { onSnooze(FollowUpSnooze.On(it)) }, onDismiss = onDismiss)
        return
    }
    val colors = OpenCodeTheme.colors
    @Composable
    fun choice(text: String, symbol: SFSymbol, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DrawerMetrics.rowCornerRadius))
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OpenCodeIcon(symbol, contentDescription = null, tint = colors.secondaryText)
            Text(text, style = OpenCodeTextStyles.body, color = colors.text)
        }
    }
    ai.opencode.core.ui.OpenCodeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_follow_up_snooze_follow_up)) },
        text = {
            Column {
                choice(stringResource(R.string.drawer_follow_up_snooze_tomorrow), SFSymbol.Clock) { onSnooze(FollowUpSnooze.Tomorrow) }
                choice(stringResource(R.string.drawer_follow_up_snooze_next_week), SFSymbol.Clock) { onSnooze(FollowUpSnooze.NextWeek) }
                choice(stringResource(R.string.drawer_follow_up_snooze_pick_date), SFSymbol.Calendar) { picking = true }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_cancel)) } },
    )
}

/** Material's date picker; days before the first one Snooze can still reach are off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FollowUpDatePicker(onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FollowUpSnooze.firstDate(Instant.now(), ZoneId.systemDefault()) }
    val firstMillis = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = firstMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= firstMillis
            override fun isSelectableYear(year: Int): Boolean = year >= first.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    val millis = state.selectedDateMillis ?: return@TextButton
                    onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                },
            ) { Text(stringResource(R.string.drawer_follow_up_snooze)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.common_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

/** A snooze's date and time, in the reader's zone and locale. */
internal fun followUpDateText(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))

/** The Undo notice's words for the last Done or Snooze. */
@Composable
internal fun followUpUndoTitle(undo: FollowUpUndo): String {
    val count = undo.changes.size
    return when (undo.kind) {
        FollowUpUndo.Kind.done ->
            if (count == 1) stringResource(R.string.drawer_follow_up_done_notice)
            else pluralStringResource(R.plurals.drawer_follow_up_done_sessions_notice, count, count)
        FollowUpUndo.Kind.snooze -> {
            val until = undo.changes.firstNotNullOfOrNull { it.after.snoozedUntil }?.let { followUpDateText(Instant.ofEpochMilli(it)) } ?: ""
            if (count == 1) stringResource(R.string.drawer_follow_up_snoozed_notice, until)
            else pluralStringResource(R.plurals.drawer_follow_up_snoozed_sessions_notice, count, count, until)
        }
    }
}
