package ai.opencode.feature.drawer

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeRadius
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import ai.opencode.feature.drawer.style.drawerCanvas
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeNotice
import ai.opencode.core.ui.OpenCodeNoticePresence
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.core.ui.rememberHighContrast
import ai.opencode.feature.drawer.list.DrawerGroupDialogs
import ai.opencode.feature.drawer.list.DrawerListActions
import ai.opencode.feature.drawer.list.DrawerListInputs
import ai.opencode.feature.drawer.list.DrawerListView
import ai.opencode.feature.drawer.list.drawerLevel
import ai.opencode.feature.drawer.list.rememberDrawerLevelTransition
import ai.opencode.feature.drawer.list.ArchivedPage
import ai.opencode.feature.drawer.list.archivedBeneath
import ai.opencode.feature.drawer.list.archivedPage
import ai.opencode.feature.drawer.list.rememberArchivedTransition
import ai.opencode.feature.drawer.list.DeleteSelectionConfirmation
import ai.opencode.feature.drawer.list.archivedSelectionActions
import ai.opencode.feature.drawer.list.DrawerPinDrag
import ai.opencode.feature.drawer.list.DrawerSelectionBar
import ai.opencode.feature.drawer.list.InboxSidebarList
import ai.opencode.feature.drawer.list.SelectionHeaderSwap
import ai.opencode.feature.drawer.list.rememberInboxSidebarOn
import ai.opencode.feature.drawer.list.selectionActions
import ai.opencode.feature.drawer.motion.DrawerSearchPill
import ai.opencode.feature.drawer.motion.DrawerSearchPillDefaults
import ai.opencode.feature.drawer.motion.drawerPanList
import ai.opencode.feature.drawer.motion.drawerSearchFocusFade
import ai.opencode.feature.drawer.motion.drawerSearchTitleFade
import ai.opencode.feature.drawer.motion.rememberDrawerShortcutMotion
import ai.opencode.feature.drawer.style.DrawerEdgeStyle
import ai.opencode.feature.drawer.style.DrawerFooterBackdrop
import ai.opencode.feature.drawer.style.DrawerHeaderBackdrop
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerSectionBreak
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.drawer.switcher.ServerFlowTitle
import ai.opencode.feature.session.ComposerTunables
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.feature.voice.FloatingCallCenter
import ai.opencode.feature.voice.floatingCallDrawerButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

/*
 * The drawer's chrome (its title, which is the server switcher, and search; New Session and
 * Settings at its bottom), the list's inputs and selection mode's bar. The container
 * (`SessionDrawerContainer`) places [HomeDrawer] in its drawer slot.
 *
 * The title and search, and New Session and Settings, lie over the list rather than beside it:
 * rows scroll on beneath them and soften into their edges, and they set the list's content
 * padding, so the first row starts below the title and the last scrolls clear of the controls.
 *
 * The drawer always draws its own footer wash. `drawerControlsRaisedTargets` is n/a: the controls
 * draw above the list, so Compose's hit testing already gives the part of New Session and Settings
 * that rises above their bar to the buttons. `scrawlScreenName` and `tuningContext` live with
 * [HomeViewState] / have no Tuning Panel.
 */

/**
 * Search's centre on the session header buttons' centre line (`TranscriptMetrics.clustered` 8 + (48
 * − 48) / 2).
 */
private val DrawerHeaderTop: Dp = 8.dp

/**
 * New Session and Settings centred on the resting composer's line (`ComposerTunables.bottomGap` 0 +
 * (bar 56 − footer 50) / 2).
 */
private val DrawerFooterBottom: Dp = ((ComposerTunables.barHeight - DrawerTunables.footerHeight.value) / 2).dp

/**
 * The session drawer's panel: everything inside the drawer, for the container's `drawer` slot.
 *
 * Size it with [modifier] (the drawer's width, full height); it pads itself by the system bars
 * and display cutout (never the keyboard: New Session and Settings stay put while it comes and
 * goes) and fills its background with the theme's canvas. It owns the drawer's
 * [DrawerPinDrag] and installs [HomeViewState.cancelPinDrag] while composed; it reads
 * [HomeViewState.shortcutMotion] and [HomeViewState.keyboardHold] when the container has set them.
 *
 * @param hostsDeleteConfirmation Shows the delete confirmation for
 *     [HomeFeature.State.pendingDeletion]. Pass `false` when the container places
 *     [HomeDeleteConfirmation] at Home's root instead.
 */
@Composable
fun HomeDrawer(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    viewState: HomeViewState,
    modifier: Modifier = Modifier,
    hostsDeleteConfirmation: Boolean = true,
) {
    val density = LocalDensity.current
    val colors = OpenCodeTheme.colors
    val reducedMotion = rememberReduceMotion()
    val highContrast = rememberHighContrast()
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val insetTop = with(density) { safe.getTop(this).toDp() }
    val insetBottom = with(density) { safe.getBottom(this).toDp() }

    val pinDrag = remember { DrawerPinDrag() }
    DisposableEffect(pinDrag, viewState) {
        viewState.cancelPinDrag = { pinDrag.cancelDrag() }
        onDispose {
            pinDrag.cancelDrag()
            viewState.cancelPinDrag = {}
        }
    }
    val localShortcutMotion = rememberDrawerShortcutMotion()
    val shortcutMotion = viewState.shortcutMotion ?: localShortcutMotion

    val isDrawerVisible = rememberIsDrawerVisible(store, viewState)
    val selectedSessionID by store.observe { viewState.drawerSelectedSessionID(it) }
    val disconnected by store.observe { it.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected }
    val search by store.observe { it.search }
    val failure by store.observe { it.deletionError ?: it.inboxSidebarStatus.archiveError ?: it.groupsError }
    val inboxSidebarOn = rememberInboxSidebarOn()
    // The group the list is inside (the Inbox Sidebar has no groups), and the one it shows: none while searching.
    val depth by store.observe { it.drawerPath.groupIDs.size }
    val shownGroup by store.observe { it.shownDrawerGroup }
    val levelDepth = if (inboxSidebarOn) 0 else depth
    val trayGroup = shownGroup.takeUnless { inboxSidebarOn }
    val current by store.observe { it.server }
    val switchingTo by store.observe { it.switchingTo }
    val serverSwapTarget = ServerSwapTarget(current, switchingTo)

    // Always draws its own bottom edge.
    val hardBottomEdge = highContrast || DrawerTunables.bottomEdge == DrawerEdgeStyle.Hard

    // Measured: the first row starts below the title, the last scrolls clear of the controls.
    var headerHeight by remember { mutableFloatStateOf(0f) }
    var controlsHeight by remember { mutableFloatStateOf(DrawerTunables.footerHeight.value) }
    val controlsReach = (controlsHeight * DrawerTunables.controlsReach.coerceIn(0f, 1f)).dp
    val footerBottom = DrawerFooterBottom

    // Selection mode: while rows are chosen a tap adds or removes one, and system back, ✕ or the
    // drawer closing ends it.
    val selection = viewState.selection
    val haptics = remember { dependency(HapticsClient) }
    val select: (String) -> Unit = remember(viewState) {
        { id ->
            if (selection.isActive) {
                haptics.play(Haptic.selection)
                selection.toggle(id)
            } else {
                viewState.selectSession(id)
            }
        }
    }
    // Inside a group: back (system back, or once the selection has ended) goes up a level, and a
    // predictive back drag moves the list with the finger. Each level keeps its own scroll.
    val levelTransition = rememberDrawerLevelTransition(levelDepth, reducedMotion)
    val rootListState = rememberLazyListState()
    val groupListState = remember(trayGroup?.id) { LazyListState() }
    PredictiveBackHandler(enabled = isDrawerVisible && trayGroup != null && !viewState.showsArchived) { events ->
        try {
            events.collect { levelTransition.back.snapTo(it.progress) }
            store.send(HomeFeature.Action.DrawerGroupBackTapped)
        } catch (cancel: CancellationException) {
            levelTransition.back.animateTo(0f, MotionRole.Layout.animation(MotionPhase.Enter, reducedMotion))
            throw cancel
        }
    }
    // The Archived page: back (system back, or once the selection has ended) returns to the list,
    // and a predictive back drag moves the page with the finger.
    val archivedTransition = rememberArchivedTransition(viewState.showsArchived, reducedMotion)
    PredictiveBackHandler(enabled = viewState.showsArchived) { events ->
        try {
            events.collect { archivedTransition.back.snapTo(it.progress) }
            viewState.showsArchived = false
        } catch (cancel: CancellationException) {
            // Let go before the system's threshold: the page settles back.
            archivedTransition.back.animateTo(0f, MotionRole.Layout.animation(MotionPhase.Enter, reducedMotion))
            throw cancel
        }
    }
    BackHandler(enabled = selection.isActive) { selection.clear() }
    // The bar keeps its last count while it leaves.
    var shownCount by remember { mutableIntStateOf(0) }
    if (selection.isActive) shownCount = selection.ids.size
    LaunchedEffect(isDrawerVisible) {
        if (!isDrawerVisible) {
            selection.clear()
            viewState.showsArchived = false
        }
    }
    // Archived's count is fetched each time the drawer shows, so another client's archiving shows too.
    val serverID by store.observe { it.server.persistenceID }
    LaunchedEffect(serverID, isDrawerVisible) {
        if (isDrawerVisible) store.send(HomeFeature.Action.GroupSharingDiscoveryRequested)
        if (isDrawerVisible) store.send(HomeFeature.Action.InboxSidebar(InboxSidebarFeature.Action.ArchivedListRequested))
    }
    val openArchived: () -> Unit = remember(viewState) {
        {
            selection.clear()
            store.send(HomeFeature.Action.InboxSidebar(InboxSidebarFeature.Action.ArchivedSearchChanged("")))
            store.send(HomeFeature.Action.InboxSidebar(InboxSidebarFeature.Action.ArchivedListRequested))
            viewState.showsArchived = true
        }
    }

    val inputs = DrawerListInputs(
        selectedSessionID = selectedSessionID,
        reducedMotion = reducedMotion,
        increasedContrast = highContrast,
        alwaysShowsHeaders = DrawerTunables.sectionBreak == DrawerSectionBreak.Header,
        serverSwap = serverSwapTarget,
        checkedIDs = selection.ids,
    )
    val actions = remember(store, viewState) {
        DrawerListActions(
            select = select,
            held = selection::toggle,
            openCatchUp = viewState::openCatchUp,
            openWhatsNew = viewState::openWhatsNew,
            hideWhatsNew = { store.send(HomeFeature.Action.WhatsNew(WhatsNewFeature.Action.HideTapped)) },
            openArchived = openArchived,
            openGroup = { id ->
                selection.clear()
                viewState.openGroup(id)
            },
            groupBack = {
                selection.clear()
                store.send(HomeFeature.Action.DrawerGroupBackTapped)
            },
            editGroups = { viewState.groupEditor.request = it },
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.drawerCanvas)
            // TalkBack reads top to bottom, list and bottom bar: the title and search, the list,
            // then New Session and Settings. The bands lie over the full-height list, so without an
            // order TalkBack read the controls first.
            .semantics { isTraversalGroup = true },
    ) {
        val listPadding = PaddingValues(
            top = headerHeight.dp + DrawerTunables.topGap,
            bottom = controlsHeight.dp + insetBottom + footerBottom + DrawerTunables.bottomGap,
        )
        // TalkBack reads only the Archived page while it covers the list and its title band.
        val beneathArchived = (if (viewState.showsArchived) Modifier.clearAndSetSemantics {} else Modifier)
            .archivedBeneath(archivedTransition, reducedMotion)
        // Inbox Sidebar: the sessions as an inbox.
        if (inboxSidebarOn) InboxSidebarList(
            store = store,
            selectedSessionID = selectedSessionID,
            headerScroll = viewState.headerScroll,
            contentPadding = listPadding,
            checkedIDs = selection.ids,
            select = select,
            held = selection::toggle,
            openCatchUp = viewState::openCatchUp,
            openArchived = openArchived,
            modifier = Modifier.fillMaxSize().then(beneathArchived),
        ) else DrawerListView(
            store = store,
            pinDrag = pinDrag,
            timeReveal = viewState.timeReveal,
            headerScroll = viewState.headerScroll,
            shortcutMotion = shortcutMotion,
            inputs = inputs,
            isDrawerVisible = isDrawerVisible,
            catchUpFocusRequests = viewState.catchUpFocusRequests,
            actions = actions,
            contentPadding = listPadding,
            modifier = Modifier.fillMaxSize().then(beneathArchived).drawerLevel(levelTransition, reducedMotion),
            listState = if (trayGroup != null) groupListState else rootListState,
        )
        // The list as the drawer's pan sees it: between the title band and the controls, which take
        // their own touches.
        Spacer(
            Modifier
                .fillMaxSize()
                .padding(top = headerHeight.dp, bottom = controlsHeight.dp + insetBottom + footerBottom)
                .drawerPanList(viewState.panRegions),
        )

        // The title band: the server's name (the switcher) and search, over the list.
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHeight = it.height / density.density }
                // The band takes its own touches: a row scrolled beneath it is never tapped through it.
                .swallowingTouches()
                .semantics {
                    isTraversalGroup = true
                    traversalIndex = -1f
                }
                .then(beneathArchived),
        ) {
            // Revealed by the list's scroll, so the list at rest reads clear.
            DrawerHeaderBackdrop(
                opaque = highContrast,
                safeTop = insetTop,
                scrollState = viewState.headerScroll,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DrawerMetrics.rowEdge)
                    .padding(top = insetTop + DrawerHeaderTop, bottom = DrawerTunables.headerBottom),
                contentAlignment = Alignment.CenterEnd,
            ) {
                SelectionHeaderSwap(
                    active = selection.isActive,
                    bar = {
                        val (main, overflow) = selectionActions(
                            store, selection, pins = !inboxSidebarOn,
                            groupEditor = viewState.groupEditor.takeIf { !inboxSidebarOn },
                        )
                        DrawerSelectionBar(count = shownCount, onClose = selection::clear, actions = main, overflow = overflow)
                    },
                ) {
                    val titleModifier = Modifier
                        .fillMaxWidth()
                        .padding(start = DrawerMetrics.textInset, end = DrawerSearchPillDefaults.size + DrawerTunables.searchGap)
                        .drawerSearchTitleFade(viewState.search)
                    ServerFlowTitle(store = store, onManage = viewState::manageServers, modifier = titleModifier)
                    DrawerSearchPill(
                        state = viewState.search,
                        text = search,
                        onTextChange = { store.send(HomeFeature.Action.SearchChanged(it)) },
                        reducedMotion = reducedMotion,
                        onEnd = viewState::endSearch,
                        keyboardHold = viewState.keyboardHold,
                    )
                }
            }
        }

        if (viewState.showsArchived || archivedTransition.isVisible) ArchivedPage(
            store = store,
            checkedIDs = selection.ids,
            select = { id ->
                if (selection.isActive) {
                    select(id)
                } else {
                    store.send(HomeFeature.Action.InboxSidebar(InboxSidebarFeature.Action.ArchivedRowTapped(id)))
                    viewState.selectSession(id)
                }
            },
            held = selection::toggle,
            onBack = { viewState.showsArchived = false },
            contentPadding = PaddingValues(top = insetTop + DrawerHeaderTop, bottom = listPadding.calculateBottomPadding()),
            modifier = Modifier.archivedPage(archivedTransition, reducedMotion).swallowingTouches(),
            header = if (selection.isActive) {
                { DrawerSelectionBar(count = selection.ids.size, onClose = selection::clear, actions = archivedSelectionActions(store, selection), overflow = emptyList()) }
            } else {
                null
            },
        )

        OpenCodeNoticePresence(
            failure,
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = DrawerMetrics.rowEdge)
                .padding(bottom = controlsHeight.dp + insetBottom + footerBottom + 12.dp),
        ) {
            OpenCodeNotice(it.title, detail = it.detail, modifier = Modifier.testTag("drawer-failure-notice"))
        }

        // New Session and Settings, pinned to the drawer's bottom; they never move with the keyboard.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .drawerSearchFocusFade(viewState.search, reducedMotion)
                .semantics {
                    isTraversalGroup = true
                    traversalIndex = 1f
                },
        ) {
            // From the buttons' top edge to the screen's bottom: rows run on behind their upper
            // part (`controlsReach`), then fade into the drawer's colour.
            DrawerFooterBackdrop(
                opaque = hardBottomEdge,
                reach = controlsReach,
                insetBottom = insetBottom,
                modifier = Modifier.matchParentSize(),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DrawerMetrics.rowEdge)
                    .padding(bottom = insetBottom + footerBottom)
                    // Their band takes its own touches, the disabled pill's included.
                    .swallowingTouches(),
            ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { controlsHeight = it.height / density.density },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrawerNewSessionButton(
                    enabled = !disconnected,
                    groupName = trayGroup?.name,
                    onClick = { viewState.startNewSessionFromDrawer(showsGroups = !inboxSidebarOn) },
                )
                Spacer(Modifier.weight(1f))
                DrawerFooterTrailing(store, disconnected, reducedMotion, onOpenSettings = viewState::openSettings)
            }
            }
        }
    }

    if (hostsDeleteConfirmation) HomeDeleteConfirmation(store)
    DeleteSelectionConfirmation(store, selection)
    DrawerGroupDialogs(store, viewState.groupEditor)
}

/**
 * Whether the drawer can be seen: in the foreground, not catching up, and open (or, beside a
 * sidebar, the sidebar showing).
 */
@Composable
private fun rememberIsDrawerVisible(store: StoreOf<HomeFeature.State, HomeFeature.Action>, viewState: HomeViewState): Boolean {
    val catchingUp by store.observe { it.catchUp != null }
    val showsDrawerLabel by store.observe { it.showsDrawerLabel }
    val isDrawerOpen by store.observe { it.isDrawerOpen }
    val sidebarShown by store.observe { it.isSidebarShown }
    return viewState.isSceneActive && !catchingUp &&
        (if (showsDrawerLabel) viewState.isSessionDrawerPresented || isDrawerOpen else sidebarShown)
}

/**
 * The delete confirmation: “Delete “<title>”?”, This removes the session from the server for all
 * clients., Delete (destructive) and Cancel.
 */
@Composable
fun HomeDeleteConfirmation(store: StoreOf<HomeFeature.State, HomeFeature.Action>) {
    val pending by store.observe { it.pendingDeletion }
    val session = pending ?: return
    OpenCodeAlertDialog(
        onDismissRequest = { store.send(HomeFeature.Action.DeleteSessionCancelled) },
        title = { Text(stringResource(R.string.drawer_delete_session_title, session.title)) },
        text = { Text(stringResource(R.string.drawer_delete_session_message)) },
        confirmButton = {
            TextButton(onClick = { store.send(HomeFeature.Action.DeleteSessionConfirmed) }) {
                Text(stringResource(CoreR.string.common_delete), color = OpenCodeTheme.colors.destructive)
            }
        },
        dismissButton = {
            TextButton(onClick = { store.send(HomeFeature.Action.DeleteSessionCancelled) }) { Text(stringResource(CoreR.string.common_cancel)) }
        },
        modifier = Modifier.testTag("drawer-delete-confirmation"),
    )
}

/**
 * New Session's pill. The symbol's ink sits as far from the pill's leading end as from its top at
 * every text size, so its leading padding shrinks as the symbol grows; the trailing stays
 * `footerPadding`. Disconnected, the label dims (not the capsule) and it takes no taps. Inside a
 * group ([groupName]) it starts the session there, and TalkBack says so.
 */
@Composable
private fun DrawerNewSessionButton(enabled: Boolean, groupName: String?, onClick: () -> Unit) {
    val colors = OpenCodeTheme.colors
    // The label caps at accessibility1 (28 sp over 17 sp body).
    val fontScale = min(LocalDensity.current.fontScale, 28f / 17f)
    val glyph = 17f * fontScale
    val height = DrawerTunables.footerHeight.value
    val bearing = DrawerTunables.footerGlyphBearing.value * glyph / 17f
    val rise = DrawerTunables.footerGlyphRise.value * glyph / 17f
    val tint = if (enabled) colors.text else colors.tertiaryText
    val newSession = stringResource(R.string.drawer_new_session)
    val description = if (groupName == null) newSession else stringResource(R.string.drawer_group_new_session, groupName)
    Surface(
        // The desktop's floating control: the popover fill, hairline ring and a soft shadow, at the
        // floating panels' radius so the tall footer buttons don't read as keys.
        shape = RoundedCornerShape(OpenCodeRadius.panel),
        color = OpenCodeSurfaces.color,
        border = OpenCodeSurfaces.border,
        shadowElevation = OpenCodeSurfaces.raisedShadow,
        contentColor = tint,
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .testTag("drawer-new-session")
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled()
                onClick { onClick(); true }
            },
    ) {
        Row(
            Modifier
                .heightIn(min = DrawerTunables.footerHeight)
                .padding(start = max(4f, (height - glyph) / 2 - bearing).dp, end = DrawerTunables.footerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpenCodeIcon(
                SFSymbol.SquareAndPencil,
                contentDescription = null,
                size = glyph.dp,
                tint = tint,
                modifier = Modifier.offset(y = -rise.dp),
            )
            Text(
                newSession,
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** Takes the touches that land on this band, so nothing beneath it reacts. */
private fun Modifier.swallowingTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/**
 * The footer's trailing controls: the call button and Settings in one tonal capsule, the New
 * Session pill's colour and height, each a `footerHeight` square. Without the call button (during a
 * call, or Voice not set up) the capsule is Settings' circle alone: Settings keeps its place, and
 * the call's half folds into it.
 */
@Composable
private fun DrawerFooterTrailing(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    disconnected: Boolean,
    reducedMotion: Boolean,
    onOpenSettings: () -> Unit,
) {
    val center = remember { FloatingCallCenter.shared }
    val isDrawerOpen by store.observe { it.isDrawerOpen }
    val offersCall by store.observe { it.drawerOffersCall }
    val showsCall by store.observe { it.drawerShowsCallButton }
    SideEffect { center.isDrawerOpen = isDrawerOpen }
    DisposableEffect(center) {
        onDispose {
            center.isDrawerOpen = false
            center.drawerCallButton = null
        }
    }
    val square = DrawerTunables.footerHeight
    Box(contentAlignment = Alignment.CenterEnd) {
        if (offersCall) {
            // The call's half stands left of Settings, shown or not, so a call ending with the
            // drawer open lands back there. The circle a floating call breaks out of and lands in,
            // as the composer's call button's.
            Box(
                Modifier.align(Alignment.CenterEnd).offset(x = -square).size(square),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(ComposerTunables.buttonDiameter.dp).floatingCallDrawerButton())
            }
        }
        Surface(
            shape = RoundedCornerShape(OpenCodeRadius.panel),
            color = OpenCodeSurfaces.color,
            border = OpenCodeSurfaces.border,
            shadowElevation = OpenCodeSurfaces.raisedShadow,
            contentColor = OpenCodeTheme.colors.text,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = offersCall && showsCall,
                    // The call button comes on layout and goes on collapse.
                    enter = if (reducedMotion) {
                        fadeIn(MotionRole.Layout.animation(reduceMotion = true))
                    } else {
                        expandHorizontally(MotionRole.Layout.animation(visibilityThreshold = IntSize.VisibilityThreshold), expandFrom = Alignment.End) +
                            fadeIn(MotionRole.Layout.animation())
                    },
                    exit = if (reducedMotion) {
                        fadeOut(MotionRole.Layout.animation(MotionPhase.Exit, reduceMotion = true))
                    } else {
                        shrinkHorizontally(MotionRole.Layout.animation(MotionPhase.Exit, visibilityThreshold = IntSize.VisibilityThreshold), shrinkTowards = Alignment.End) +
                            fadeOut(MotionRole.Layout.animation(MotionPhase.Exit))
                    },
                ) {
                    OpenCodeIconButton(
                        symbol = SFSymbol.PhoneFill,
                        contentDescription = stringResource(R.string.drawer_start_voice_call_description),
                        onClick = {
                            // The orb breaks out of this button, not the composer's under the drawer.
                            center.breakoutFrom = center.drawerCallButton
                            store.send(HomeFeature.Action.DrawerCallTapped)
                        },
                        style = OpenCodeIconButtonStyle.Standard,
                        size = square,
                        enabled = !disconnected,
                        modifier = Modifier.testTag("drawer-call"),
                    )
                }
                OpenCodeIconButton(
                    symbol = SFSymbol.GearshapeFill,
                    contentDescription = stringResource(CoreR.string.common_settings),
                    onClick = onOpenSettings,
                    style = OpenCodeIconButtonStyle.Standard,
                    size = square,
                    modifier = Modifier.testTag("drawer-settings"),
                )
            }
        }
    }
}
