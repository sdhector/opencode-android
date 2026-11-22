package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Gate
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.distantPast
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receive
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveDetail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.drawer.list.DrawerPinDrag
import ai.opencode.feature.drawer.list.DrawerPinDragHost
import ai.opencode.feature.drawer.list.DrawerPinDragItem
import ai.opencode.feature.drawer.list.DrawerPinDropTarget
import ai.opencode.feature.drawer.list.DrawerPinGroup
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.session.withPendingFiles
import ai.opencode.server.CachedTranscriptSnapshots
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ai.opencode.core.testing.TestIsolationRule
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** The drawer's part: pins, mutes, the pin drag, deletion, local sends and read state. */
class HomeFeatureTestsDrawer {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val otherServer = OpenCodeServer(url = "http://localhost:4097", username = "", password = "")

    private fun session(id: String, title: String, updatedAt: Instant, projectID: String = "p", directory: String = "/project", idle: Long? = null, viewed: Long? = null) =
        OpenCodeSession(id = id, serverTitle = title, projectID = projectID, directory = directory, updatedAt = updatedAt, idleMilliseconds = idle, viewedMilliseconds = viewed)

    private fun key(id: String) = "${server.persistenceID}|$id"

    @Test
    fun pinsArePersistentServerScopedAndReturnToRecentWhenUnpinned() = homeTest { h ->
        val recent = session("recent", "Recent", Instant.now())
        val older = session("older", "Older", distantPast, projectID = "q", directory = "/other")
        val snapshot = snapshot(listOf(recent, older))
        val store = h.store(h.state { HomeFeature.State(server, snapshot) })
        store.send(Action.TogglePin(older.id)) { it.copy(pinnedSessionKeys = listOf(key(older.id))) }
        assertEquals(listOf(older), store.state.pinnedSessions)
        assertEquals(listOf(recent), store.state.recentSessions)
        assertEquals(listOf(older), h.state { HomeFeature.State(server, snapshot) }.pinnedSessions)
        assertTrue(h.state { HomeFeature.State(otherServer, snapshot) }.pinnedSessions.isEmpty())
        store.send(Action.TogglePin(older.id)) { it.copy(pinnedSessionKeys = emptyList()) }
        assertEquals(listOf(recent, older), store.state.recentSessions)
    }

    @Test
    fun mutesArePersistentServerScopedAndForgottenWithTheSession() = homeTest { h ->
        val recent = session("recent", "Recent", Instant.now())
        val older = session("older", "Older", distantPast, projectID = "q", directory = "/other")
        val snapshot = snapshot(listOf(recent, older))
        val store = h.store(h.state { HomeFeature.State(server, snapshot) })
        store.send(Action.ToggleMute(older.id)) { it.copy(mutedSessionKeys = setOf(key(older.id))) }
        assertTrue(store.state.isMuted(older.id))
        assertFalse(store.state.isMuted(recent.id))
        assertEquals("muted sessions stay listed", listOf(recent, older), store.state.recentSessions)
        assertEquals(setOf(key(older.id)), h.shared(SharedKeys.mutedSessionKeys))
        val restored = h.state { HomeFeature.State(server, snapshot) }
        assertTrue("a relaunch reads the same file", restored.isMuted(older.id))
        assertFalse(h.state { HomeFeature.State(otherServer, snapshot) }.isMuted(older.id))
        store.send(Action.ToggleMute(older.id)) { it.copy(mutedSessionKeys = emptySet()) }
        store.send(Action.ToggleMute("missing"))

        store.send(Action.ToggleMute(recent.id)) { it.copy(mutedSessionKeys = setOf(key(recent.id))) }
        store.exhaustive = false
        h.state { restored.purgingSession(recent.id) }
        assertTrue("deleting a session forgets its mute", h.shared(SharedKeys.mutedSessionKeys).isEmpty())
    }

    @Test
    fun hiddenSessionsLeaveTheListButStayInSearch() = homeTest { h ->
        val recent = session("recent", "Recent", Instant.now())
        val older = session("older", "Older", distantPast, projectID = "q", directory = "/other")
        val snapshot = snapshot(listOf(recent, older))
        val store = h.store(h.state { HomeFeature.State(server, snapshot) })
        store.send(Action.TogglePin(older.id)) { it.copy(pinnedSessionKeys = listOf(key(older.id))) }
        store.send(Action.ToggleHidden(older.id)) { it.copy(hiddenSessionKeys = setOf(key(older.id))) }
        assertTrue(store.state.isHidden(older.id))
        assertTrue("a hidden pin leaves Pinned too", store.state.pinnedSessions.isEmpty())
        assertEquals(listOf(recent), store.state.recentSessions)
        val searching = store.state.copy(search = "Old")
        assertEquals("search still finds it", listOf(older), searching.pinnedSessions)
        assertEquals("a relaunch reads the same file", listOf(recent), h.state { HomeFeature.State(server, snapshot) }.recentSessions)
        assertEquals(listOf(recent, older), h.state { HomeFeature.State(otherServer, snapshot) }.recentSessions)
        store.send(Action.ToggleHidden(older.id)) { it.copy(hiddenSessionKeys = emptySet()) }
        assertEquals(listOf(older), store.state.pinnedSessions)
        store.send(Action.ToggleHidden("missing"))

        store.send(Action.ToggleHidden(recent.id)) { it.copy(hiddenSessionKeys = setOf(key(recent.id))) }
        store.exhaustive = false
        h.state { store.state.purgingSession(recent.id) }
        assertTrue("deleting a session forgets that it was hidden", h.shared(SharedKeys.hiddenSessionKeys).isEmpty())
    }

    @Test
    fun pinsRespectSearchAndAppendInOrder() = homeTest { h ->
        val a = session("a", "Alpha", Instant.now())
        val b = session("b", "Beta", distantPast, projectID = "q", directory = "/other")
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(a, b))) })
        store.exhaustive = false
        store.send(Action.TogglePin(a.id)) { it.copy(pinnedSessionKeys = listOf(key(a.id))) }
        store.send(Action.TogglePin(b.id)) { it.copy(pinnedSessionKeys = listOf(key(a.id), key(b.id))) }
        assertEquals(listOf(a, b), store.state.pinnedSessions)
        store.send(Action.SearchChanged("Beta")) { it.copy(search = "Beta") }
        assertEquals(listOf(b), store.state.pinnedSessions)
        store.send(Action.TogglePin("missing"))
    }

    @Test
    fun pinnedSessionsCanMoveWithAccessibleAndDragActions() = homeTest { h ->
        val sessions = listOf("a", "b", "c").map { session(it, it, Instant.now(), directory = "/p") }
        val state = h.state {
            HomeFeature.State(server, snapshot(sessions)).updatingPinnedSessionKeys { sessions.map { key(it.id) } }
        }
        val store = h.store(state)

        store.send(Action.MovePinDown("a")) { it.copy(pinnedSessionKeys = listOf(key("b"), key("a"), key("c"))) }
        store.send(Action.MovePinUp("c")) { it.copy(pinnedSessionKeys = listOf(key("b"), key("c"), key("a"))) }
        store.send(Action.MovePinUp("b"))
        store.send(Action.MovePinDown("a"))
    }

    @Test
    fun draggingAnUnpinnedSessionIntoPinnedInsertsAtIndexAndDraggingOutUnpins() = homeTest { h ->
        val sessions = listOf("a", "b", "c", "d").mapIndexed { index, id ->
            session(id, id.uppercase(), date(40 - index * 10), directory = "/p")
        }
        val otherServerKey = "other-server|x"
        val state = h.state { HomeFeature.State(server, snapshot(sessions)).updatingPinnedSessionKeys { listOf(otherServerKey) } }
        val store = h.store(state)

        // Dropping an unpinned session back in Recents is a no-op.
        store.send(Action.DrawerRowDropped(sessionID = "c", pinnedIndex = null))

        // Dropping the first pin when nothing on this server is pinned yet.
        store.send(Action.DrawerRowDropped(sessionID = "c", pinnedIndex = 0)) { it.copy(pinnedSessionKeys = listOf(otherServerKey, key("c"))) }
        assertEquals(listOf("c"), store.state.pinnedSessions.map { it.id })
        assertEquals(listOf("a", "b", "d"), store.state.recentSessions.map { it.id })

        // Dragging another unpinned session to the top of Pinned (index 0).
        store.send(Action.DrawerRowDropped(sessionID = "d", pinnedIndex = 0)) { it.copy(pinnedSessionKeys = listOf(otherServerKey, key("d"), key("c"))) }
        assertEquals(listOf("d", "c"), store.state.pinnedSessions.map { it.id })

        // Dragging an unpinned session into the middle of Pinned (index 1).
        store.send(Action.DrawerRowDropped(sessionID = "b", pinnedIndex = 1)) {
            it.copy(pinnedSessionKeys = listOf(otherServerKey, key("d"), key("b"), key("c")))
        }
        assertEquals(listOf("d", "b", "c"), store.state.pinnedSessions.map { it.id })

        // Dropping a pinned session back in its own slot is a no-op.
        store.send(Action.DrawerRowDropped(sessionID = "b", pinnedIndex = 1))

        // Reordering a pinned session to the end of Pinned.
        store.send(Action.DrawerRowDropped(sessionID = "d", pinnedIndex = 2)) {
            it.copy(pinnedSessionKeys = listOf(otherServerKey, key("b"), key("c"), key("d")))
        }
        assertEquals(listOf("b", "c", "d"), store.state.pinnedSessions.map { it.id })

        // Dragging a pinned session down into Recents unpins it and restores recency order.
        store.send(Action.DrawerRowDropped(sessionID = "b", pinnedIndex = null)) {
            it.copy(pinnedSessionKeys = listOf(otherServerKey, key("c"), key("d")))
        }
        assertEquals(listOf("c", "d"), store.state.pinnedSessions.map { it.id })
        assertEquals(listOf("a", "b"), store.state.recentSessions.map { it.id })
    }

    // region Pin drag

    /** The list a [DrawerPinDrag] moves: visible items in list coordinates. */
    private class TestDrawerPinHost(override val scope: CoroutineScope) : DrawerPinDragHost {
        val items = ArrayList<DrawerPinDragItem>()
        override fun visibleItems(): List<DrawerPinDragItem> = items.toList()
        override val width: Float = 390f
        override var viewportTop: Float = 0f
        val groupDrops = ArrayList<Pair<String, String?>>()
        override fun dropInGroup(sessionID: String, groupID: String?) {
            groupDrops += sessionID to groupID
        }
        override fun flushNow() = Unit
        override fun preserveScroll() = Unit
        override val frameDurationSeconds: Double = 1.0 / 60.0

        fun addHeader(key: DrawerPinDrag.ItemKey, minY: Float, height: Float) {
            items += DrawerPinDragItem(key, minY, height)
        }

        fun addRow(sessionID: String, minY: Float, height: Float) {
            items += DrawerPinDragItem(DrawerPinDrag.ItemKey.Row(sessionID), minY, height)
        }

        fun removeRow(sessionID: String) {
            items.removeAll { it.key == DrawerPinDrag.ItemKey.Row(sessionID) }
        }
    }

    /** Frames for the drag's spring loop, as the display link would send them. */
    private class Frames(private val test: TestScope) {
        val clock = BroadcastFrameClock()
        private var nanos = 0L
        fun scope(): CoroutineScope = CoroutineScope(test.coroutineContext + clock + Job())
        fun frame() {
            test.runCurrent()
            nanos += 16_666_667
            clock.sendFrame(nanos)
            test.runCurrent()
        }
        fun pump(pinDrag: DrawerPinDrag, limit: Int = 600) {
            test.runCurrent()
            var frames = 0
            while (pinDrag.isDisplayLinkRunning && frames < limit) {
                nanos += 16_666_667
                clock.sendFrame(nanos)
                test.runCurrent()
                frames++
            }
        }
    }

    @Test
    fun cancelledPinDragLeavesPinsUnchangedAndStopsDisplayLinkAfterDismantle() = homeTest { h ->
        val sessions = listOf("a", "b", "c").mapIndexed { index, id -> session(id, id.uppercase(), date(30 - index * 10), directory = "/p") }
        val state = h.state { HomeFeature.State(server, snapshot(sessions)).updatingPinnedSessionKeys { listOf(key("a")) } }
        val hapticsLog = LockIsolated<List<Haptic>>(emptyList())
        val store = h.store(state) { this[HapticsClient] = HapticsClient(play = { hapticsLog.append(it) }) }

        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val stride = pinDrag.rowStride
        val headerH = 56f
        host.addHeader(DrawerPinDrag.ItemKey.PinnedHeader, minY = 0f, height = headerH)
        host.addRow("a", minY = headerH, height = stride)
        host.addHeader(DrawerPinDrag.ItemKey.RecentsHeader, minY = headerH + stride, height = headerH)
        host.addRow("b", minY = headerH * 2 + stride, height = stride)
        host.addRow("c", minY = headerH * 2 + stride * 2, height = stride)

        var liftedID: String? = null
        val pendingActions = ArrayList<Action>()
        fun begin() = pinDrag.beginDrag(
            sessionID = "b",
            isPinnedSection = false,
            pinnedIDs = listOf("a"),
            recentIDs = listOf("b", "c"),
            unpinnedRecentsIndex = 0,
            showsShortcuts = false,
            reducedMotion = false,
            fingerY = headerH * 2 + stride * 1.5f,
            onZoneCrossed = { pendingActions += Action.DrawerPinZoneCrossed(entered = it) },
            onSlotChanged = { pendingActions += Action.DrawerPinSlotChanged },
            onDropReleased = { id, index -> pendingActions += Action.DrawerRowDropReleased(sessionID = id, pinnedIndex = index) },
            onDrop = { id, index -> pendingActions += Action.DrawerRowDropped(sessionID = id, pinnedIndex = index) },
            onSetLifted = { liftedID = it },
        )
        begin()
        assertTrue(pinDrag.isDisplayLinkRunning)
        assertEquals("b", liftedID)

        // Drag up into the pinned section, then cancel outside the drawer.
        pinDrag.updateDrag(fingerY = headerH + 8)
        assertEquals(DrawerPinDrag.HoverTarget.Pinned(0), pinDrag.hoverTarget)

        pinDrag.cancelDrag()
        // A late completeDrop after cancellation must be a no-op.
        pinDrag.completeDrop()

        for (action in pendingActions) store.send(action)
        pendingActions.clear()

        assertEquals("A cancelled drag must not pin the dragged session", listOf("a"), store.state.pinnedSessions.map { it.id })
        assertFalse("A cancelled drag must not play the commit snap haptic", Haptic.snap in hapticsLog.value)
        assertNull(liftedID)
        frames.pump(pinDrag)
        assertFalse("Display link stops once a cancelled drag settles", pinDrag.isDisplayLinkRunning)

        // Start a second drag and dismantle the active row mid-drag (`DrawerListView`'s `dismantled`).
        begin()
        assertTrue(pinDrag.isDisplayLinkRunning)
        host.removeRow("b")
        if (pinDrag.activeSessionID == "b") pinDrag.cancelDrag()

        assertNull(pinDrag.activeSessionID)
        assertNull(liftedID)
        assertFalse("Dismantling the active row cancels the drag and stops the display link", pinDrag.isDisplayLinkRunning)
        assertEquals(listOf("a"), store.state.pinnedSessions.map { it.id })
        pinDrag.detach(host)
    }

    @Test
    fun scrolledDrawerDerivesPinAnchorsFromVisibleRowsAndSuppressesOffscreenEmptyZone() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val stride = pinDrag.rowStride
        val headerH = 56f

        // 1. Empty pins, scrolled down so the top of the list is offscreen: dragging a visible
        // recent row upward must not open the empty-pin zone.
        host.viewportTop = 240f
        host.addRow("d", minY = headerH + stride * 3, height = stride)
        pinDrag.beginDrag(
            sessionID = "d",
            isPinnedSection = false,
            pinnedIDs = emptyList(),
            recentIDs = listOf("a", "b", "c", "d"),
            unpinnedRecentsIndex = 3,
            showsShortcuts = false,
            reducedMotion = false,
            fingerY = headerH + stride * 3.5f,
            onZoneCrossed = {},
            onSlotChanged = {},
            onDrop = { _, _ -> },
            onSetLifted = {},
        )
        pinDrag.updateDrag(fingerY = 250f)
        assertEquals("Scrolled drawer must not reveal the empty-pins drop zone mid-list", DrawerPinDrag.HoverTarget.Recent, pinDrag.hoverTarget)
        pinDrag.cancelDrag()
        frames.pump(pinDrag)

        // 2. Existing pins ["p0", "p1"] where "p0" and the Pinned header are scrolled offscreen,
        // leaving only "p1" (index 1) and recents ["r0", "r1", "r2"] with "r1" (index 1) visible.
        host.items.clear()
        host.viewportTop = headerH + stride * 0.5f
        host.addRow("p1", minY = headerH + stride, height = stride)
        host.addHeader(DrawerPinDrag.ItemKey.RecentsHeader, minY = headerH + stride * 2, height = headerH)
        host.addRow("r1", minY = headerH * 2 + stride * 3, height = stride)

        pinDrag.beginDrag(
            sessionID = "p1",
            isPinnedSection = true,
            pinnedIDs = listOf("p0", "p1"),
            recentIDs = listOf("r0", "r1", "r2"),
            unpinnedRecentsIndex = 2,
            showsShortcuts = false,
            reducedMotion = false,
            fingerY = headerH + stride * 1.5f,
            onZoneCrossed = {},
            onSlotChanged = {},
            onDrop = { _, _ -> },
            onSetLifted = {},
        )
        // Hovering in slot 1 (where p1 started) lands at headerH + stride.
        val pinnedSlot1 = pinDrag.landingSlotMinY() ?: Float.NaN
        assertTrue("pinned slot 1 lands at ${headerH + stride}, not $pinnedSlot1", abs(pinnedSlot1 - (headerH + stride)) < 0.5f)

        // Dragging p1 down into Recents (unpinnedRecentsIndex = 2) derives slot 2 even when
        // neither r2 nor r1's predecessor is the first row.
        pinDrag.updateDrag(fingerY = headerH * 2 + stride * 3.5f)
        assertEquals(DrawerPinDrag.HoverTarget.Recent, pinDrag.hoverTarget)
        val recentSlot2 = pinDrag.landingSlotMinY() ?: Float.NaN
        val expectedRecentSlot2 = (headerH * 2 + stride * 4) - stride
        assertTrue("recent slot 2 lands at $expectedRecentSlot2, not $recentSlot2", abs(recentSlot2 - expectedRecentSlot2) < 0.5f)
        pinDrag.cancelDrag()
        frames.pump(pinDrag)
        pinDrag.detach(host)
    }

    /**
     * The list as it lays out with groups: Pinned (if [pins]), then group `g1` (sessions m1, m2),
     * a closed `g2`, New Group, Recents ([recents]; with none, the drop slot a lift shows). Heights
     * are the drawer's: 56 a section header, 48 a row.
     */
    private class GroupedList(val host: TestDrawerPinHost, pins: List<String>, val recents: List<String> = listOf("r1", "r2", "r3")) {
        val stride = 48f
        val section = 56f
        val g1 = DrawerPinDrag.ItemKey.GroupHeader("g1")
        val g2 = DrawerPinDrag.ItemKey.GroupHeader("g2")
        val groups = listOf(DrawerPinGroup("g1", listOf("m1", "m2")), DrawerPinGroup("g2", emptyList()))
        val top: Float
        val bottom: Float

        init {
            var y = 0f
            fun place(key: DrawerPinDrag.ItemKey, height: Float) {
                host.items += DrawerPinDragItem(key, y, height)
                y += height
            }
            if (pins.isNotEmpty()) {
                place(DrawerPinDrag.ItemKey.PinnedHeader, section)
                pins.forEach { place(DrawerPinDrag.ItemKey.Row(it), stride) }
            }
            top = y
            place(g1, stride)
            place(DrawerPinDrag.ItemKey.Row("m1"), stride)
            place(DrawerPinDrag.ItemKey.Row("m2"), stride)
            place(g2, stride)
            place(DrawerPinDrag.ItemKey.NewGroup, stride)
            place(DrawerPinDrag.ItemKey.RecentsHeader, section)
            recents.forEach { place(DrawerPinDrag.ItemKey.Row(it), stride) }
            if (recents.isEmpty()) place(DrawerPinDrag.ItemKey.RecentsDrop, stride)
            bottom = y
        }

        fun minY(key: DrawerPinDrag.ItemKey) = host.items.first { it.key == key }.minY
        fun row(id: String) = DrawerPinDrag.ItemKey.Row(id)
    }

    private fun DrawerPinDrag.beginGrouped(
        list: GroupedList,
        id: String,
        pinned: List<String>,
        fromPinned: Boolean = false,
        actions: MutableList<Action> = ArrayList(),
    ) = beginDrag(
        sessionID = id,
        isPinnedSection = fromPinned,
        pinnedIDs = pinned,
        recentIDs = list.recents,
        unpinnedRecentsIndex = list.recents.indexOf(id).coerceAtLeast(0),
        showsShortcuts = false,
        reducedMotion = false,
        fingerY = list.minY(list.row(id)) + list.stride / 2,
        groups = list.groups,
        showsNewGroup = true,
        onZoneCrossed = { actions += Action.DrawerPinZoneCrossed(entered = it) },
        onSlotChanged = { actions += Action.DrawerPinSlotChanged },
        onDropReleased = { sessionID, index -> actions += Action.DrawerRowDropReleased(sessionID, index) },
        onDrop = { sessionID, index -> actions += Action.DrawerRowDropped(sessionID, index) },
        onSetLifted = {},
    )

    @Test
    fun emptyPinSlotOpensAboveTheGroupSectionAndEverythingBelowMakesRoom() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = emptyList())
        val room = list.section + list.stride

        pinDrag.beginGrouped(list, "r2", pinned = emptyList())
        pinDrag.updateDrag(fingerY = list.minY(list.row("r2")) - 10)
        frames.pump(pinDrag)

        assertEquals("the slot opens where Pinned would sit, above the first group", list.top, pinDrag.dropZone?.top)
        for (key in listOf(list.g1, list.row("m1"), list.row("m2"), list.g2, DrawerPinDrag.ItemKey.NewGroup, DrawerPinDrag.ItemKey.RecentsHeader, list.row("r1"))) {
            assertEquals("$key makes room", room, pinDrag.translation(key), 0.5f)
        }

        pinDrag.updateDrag(fingerY = list.top + 20)
        frames.pump(pinDrag)
        assertEquals(DrawerPinDrag.HoverTarget.Pinned(0), pinDrag.hoverTarget)
        assertNull("the slot, not a group, is the target", pinDrag.dropTarget)
        assertEquals("rows below the lifted one close its hole", room - list.stride, pinDrag.translation(list.row("r3")), 0.5f)
        assertEquals(room, pinDrag.translation(list.g1), 0.5f)

        pinDrag.cancelDrag()
        frames.pump(pinDrag)
        pinDrag.detach(host)
    }

    @Test
    fun emptyPinSlotWithNoGroupsStillOpensAboveNewGroup() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        host.addHeader(DrawerPinDrag.ItemKey.NewGroup, minY = 0f, height = 48f)
        host.addHeader(DrawerPinDrag.ItemKey.RecentsHeader, minY = 48f, height = 56f)
        host.addRow("r1", minY = 104f, height = 48f)
        host.addRow("r2", minY = 152f, height = 48f)
        pinDrag.beginDrag(
            sessionID = "r2",
            isPinnedSection = false,
            pinnedIDs = emptyList(),
            recentIDs = listOf("r1", "r2"),
            unpinnedRecentsIndex = 1,
            showsShortcuts = false,
            reducedMotion = false,
            fingerY = 176f,
            showsNewGroup = true,
            onZoneCrossed = {},
            onSlotChanged = {},
            onDrop = { _, _ -> },
            onSetLifted = {},
        )
        pinDrag.updateDrag(fingerY = 150f)
        frames.pump(pinDrag)

        assertEquals("above New Group, not above Recents", 0f, pinDrag.dropZone?.top)
        assertEquals(104f, pinDrag.translation(DrawerPinDrag.ItemKey.NewGroup), 0.5f)
        assertEquals(104f, pinDrag.translation(DrawerPinDrag.ItemKey.RecentsHeader), 0.5f)
        pinDrag.cancelDrag()
        frames.pump(pinDrag)
        pinDrag.detach(host)
    }

    @Test
    fun emptyPinSlotStaysClosedWhileTheGroupSectionIsScrolledOut() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = emptyList())
        host.items.removeAll { it.key in listOf(list.g1, list.row("m1"), list.row("m2")) }
        host.viewportTop = 120f

        pinDrag.beginGrouped(list, "r2", pinned = emptyList())
        pinDrag.updateDrag(fingerY = 130f)
        frames.pump(pinDrag)

        assertEquals(DrawerPinDrag.HoverTarget.Recent, pinDrag.hoverTarget)
        assertNull(pinDrag.dropZone)
        pinDrag.cancelDrag()
        frames.pump(pinDrag)
        pinDrag.detach(host)
    }

    @Test
    fun pinSlotWithPinsMakesRoomForGroupsAndPinsFromAGroupClosesItsHole() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = listOf("p1"))
        val pinnedSlot = list.minY(list.row("p1"))

        // From Recents: everything above r2 moves down one row.
        pinDrag.beginGrouped(list, "r2", pinned = listOf("p1"))
        pinDrag.updateDrag(fingerY = pinnedSlot + 10)
        frames.pump(pinDrag)
        assertEquals(DrawerPinDrag.HoverTarget.Pinned(0), pinDrag.hoverTarget)
        assertEquals(pinnedSlot, pinDrag.landingSlotMinY() ?: Float.NaN, 0.5f)
        for (key in listOf(list.row("p1"), list.g1, list.row("m1"), list.row("m2"), list.g2, DrawerPinDrag.ItemKey.NewGroup, DrawerPinDrag.ItemKey.RecentsHeader, list.row("r1"))) {
            assertEquals("$key", list.stride, pinDrag.translation(key), 0.5f)
        }
        assertEquals(0f, pinDrag.translation(list.row("r3")), 0.5f)
        assertEquals(0f, pinDrag.translation(DrawerPinDrag.ItemKey.PinnedHeader), 0.5f)
        pinDrag.cancelDrag()
        frames.pump(pinDrag)

        // From a group: only what is above m1 moves; below it the hole and the new slot cancel.
        val actions = ArrayList<Action>()
        pinDrag.beginGrouped(list, "m1", pinned = listOf("p1"), actions = actions)
        pinDrag.updateDrag(fingerY = pinnedSlot + 10)
        frames.pump(pinDrag)
        assertEquals(DrawerPinDrag.HoverTarget.Pinned(0), pinDrag.hoverTarget)
        assertNull(pinDrag.dropTarget)
        assertEquals(list.stride, pinDrag.translation(list.row("p1")), 0.5f)
        assertEquals(list.stride, pinDrag.translation(list.g1), 0.5f)
        for (key in listOf(list.row("m2"), list.g2, DrawerPinDrag.ItemKey.NewGroup, DrawerPinDrag.ItemKey.RecentsHeader, list.row("r1"), list.row("r3"))) {
            assertEquals("$key", 0f, pinDrag.translation(key), 0.5f)
        }

        // Released there, it pins at the first slot.
        pinDrag.endDrag(fingerX = 10f, fingerY = pinnedSlot + 10)
        frames.pump(pinDrag)
        assertEquals(listOf<Action>(Action.DrawerPinZoneCrossed(entered = true), Action.DrawerRowDropReleased("m1", 0), Action.DrawerRowDropped("m1", 0)), actions)
        assertTrue(host.groupDrops.isEmpty())
        pinDrag.detach(host)
    }

    @Test
    fun aGroupTakesADropOnItsHeaderAndEverySessionUnderIt() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = emptyList())

        pinDrag.beginGrouped(list, "r2", pinned = emptyList())
        fun over(y: Float): String? {
            pinDrag.updateDrag(fingerY = y)
            return pinDrag.dropTarget?.groupID
        }
        val g1 = list.minY(list.g1)
        assertEquals("the header", "g1", over(g1 + 24))
        assertEquals("a session under it", "g1", over(list.minY(list.row("m1")) + 24))
        assertEquals("the last one's lower edge", "g1", over(list.minY(list.row("m2")) + 47))
        assertEquals("a closed, empty group's whole header", "g2", over(list.minY(list.g2) + 2))
        assertEquals("g2", over(list.minY(list.g2) + 46))
        over(g1 + 1)
        assertEquals("the span covers the header and both sessions", 3 * list.stride, pinDrag.dropTarget?.height ?: Float.NaN, 0.5f)
        assertNull("New Group is no group", over(list.minY(DrawerPinDrag.ItemKey.NewGroup) + 24))
        assertNull("nor is Recents' header", over(list.minY(DrawerPinDrag.ItemKey.RecentsHeader) + 28))
        assertNull("nor a recent row", over(list.minY(list.row("r1")) + 24))
        assertNull("nor anything above the list", over(-5f))

        pinDrag.cancelDrag()
        frames.pump(pinDrag)
        assertNull(pinDrag.dropTarget)
        pinDrag.detach(host)
    }

    @Test
    fun groupsAreHitWhereTheyShowWhileTheEmptyPinSlotIsOpen() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = emptyList())
        val room = list.section + list.stride

        pinDrag.beginGrouped(list, "r2", pinned = emptyList())
        pinDrag.updateDrag(fingerY = list.minY(list.row("r2")) - 10)
        frames.pump(pinDrag)

        fun over(y: Float): String? {
            pinDrag.updateDrag(fingerY = y)
            return pinDrag.dropTarget?.groupID
        }
        // Where the layout puts m1, the slot's room now shows the pinned row.
        assertNull("the slot is not a group", over(list.minY(list.g1) + 10))
        assertEquals("g1", over(list.minY(list.g1) + room + 10))
        assertEquals("g1", over(list.minY(list.row("m2")) + room + 40))
        assertEquals("g2", over(list.minY(list.g2) + room + 24))
        assertNull("New Group, where g2 was laid out", over(list.minY(list.g2) + room + list.stride + 24))
        assertNull("Recents' header", over(list.minY(DrawerPinDrag.ItemKey.RecentsHeader) + room + 20))
        assertEquals("where g2 was laid out, m1 now shows", "g1", over(list.minY(list.g2) + 24))

        pinDrag.cancelDrag()
        frames.pump(pinDrag)
        pinDrag.detach(host)
    }

    @Test
    fun droppingOnAGroupMovesTheSessionThereAndLeavesPinsAlone() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = listOf("p1", "p2"))
        val actions = ArrayList<Action>()

        // A pinned row over a group: the group is the target and the rows don't slide away.
        pinDrag.beginGrouped(list, "p1", pinned = listOf("p1", "p2"), fromPinned = true, actions = actions)
        pinDrag.updateDrag(fingerY = list.minY(list.row("m1")) + 10)
        frames.pump(pinDrag)
        assertEquals("g1", pinDrag.dropTarget?.groupID)
        assertEquals(DrawerPinDrag.HoverTarget.Pinned(0), pinDrag.hoverTarget)
        assertEquals(0f, pinDrag.translation(list.g1), 0.5f)
        assertEquals(0f, pinDrag.translation(DrawerPinDrag.ItemKey.RecentsHeader), 0.5f)

        pinDrag.endDrag(fingerX = 10f, fingerY = list.minY(list.row("m1")) + 10)
        assertEquals(listOf("p1" to "g1"), host.groupDrops)
        assertTrue("no pin or reorder is sent", actions.none { it is Action.DrawerRowDropped })
        assertNull(pinDrag.activeSessionID)
        assertNull(pinDrag.dropTarget)
        assertTrue(pinDrag.translations.isEmpty())

        // A recent row released over Recents' own area changes nothing.
        pinDrag.beginGrouped(list, "r3", pinned = listOf("p1", "p2"), actions = actions)
        pinDrag.updateDrag(fingerY = list.minY(list.row("r3")) + 5)
        pinDrag.endDrag(fingerX = 10f, fingerY = list.minY(list.row("r3")) + 5)
        frames.pump(pinDrag)
        assertEquals(listOf("p1" to "g1"), host.groupDrops)
        assertTrue(actions.none { it is Action.DrawerRowDropped })
        pinDrag.detach(host)
    }

    @Test
    fun aGroupedRowDroppedOnRecentsLeavesItsGroup() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = listOf("p1"))
        val actions = ArrayList<Action>()
        val recentsTop = list.minY(DrawerPinDrag.ItemKey.RecentsHeader)

        pinDrag.beginGrouped(list, "m1", pinned = listOf("p1"), actions = actions)
        fun over(y: Float): DrawerPinDropTarget? {
            pinDrag.updateDrag(fingerY = y)
            return pinDrag.dropTarget
        }
        assertNull("its own place", over(list.minY(list.row("m1")) + 24))
        assertNull("its own group", over(list.minY(list.g1) + 10))
        assertNull("New Group", over(list.minY(DrawerPinDrag.ItemKey.NewGroup) + 24))
        assertEquals("another group", "g2", over(list.minY(list.g2) + 24)?.groupID)
        assertEquals("Recents' header", DrawerPinDropTarget(null, recentsTop, list.bottom - recentsTop), over(recentsTop + 10))
        assertEquals("a recent row", null, over(list.minY(list.row("r3")) + 24)?.groupID)
        assertTrue("the room below the last one", over(list.bottom + 40) != null)
        assertEquals("over Recents no pin slot opens", DrawerPinDrag.HoverTarget.Recent, pinDrag.hoverTarget)
        frames.pump(pinDrag)
        assertEquals(0f, pinDrag.translation(list.row("p1")), 0.5f)
        assertEquals(0f, pinDrag.translation(DrawerPinDrag.ItemKey.RecentsHeader), 0.5f)

        pinDrag.endDrag(fingerX = 10f, fingerY = recentsTop + 10)
        assertEquals(listOf<Pair<String, String?>>("m1" to null), host.groupDrops)
        assertTrue("no pin or unpin is sent", actions.none { it is Action.DrawerRowDropped })
        assertNull(pinDrag.activeSessionID)
        assertNull(pinDrag.dropTarget)
        assertTrue(pinDrag.translations.isEmpty())
        pinDrag.detach(host)
    }

    @Test
    fun aGroupedRowPutBackOrCancelledStaysInItsGroup() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = emptyList())
        val actions = ArrayList<Action>()
        val m2 = list.minY(list.row("m2"))

        // Put back where it was.
        pinDrag.beginGrouped(list, "m2", pinned = emptyList(), actions = actions)
        pinDrag.updateDrag(fingerY = m2 + 30)
        pinDrag.endDrag(fingerX = 10f, fingerY = m2 + 30)
        frames.pump(pinDrag)

        // Over Recents, then let go off the list.
        pinDrag.beginGrouped(list, "m2", pinned = emptyList(), actions = actions)
        pinDrag.updateDrag(fingerY = list.minY(list.row("r1")) + 10)
        assertEquals(null, pinDrag.dropTarget?.groupID)
        pinDrag.endDrag(fingerX = -200f, fingerY = list.minY(list.row("r1")) + 10)
        frames.pump(pinDrag)

        assertTrue(host.groupDrops.isEmpty())
        assertTrue(actions.none { it is Action.DrawerRowDropped })
        assertNull(pinDrag.dropTarget)
        assertNull(pinDrag.activeSessionID)
        assertTrue(pinDrag.translations.isEmpty())
        pinDrag.detach(host)
    }

    @Test
    fun aPinnedRowOverRecentsShowsItAndLandsThroughTheUnpin() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = listOf("p1", "p2"))
        val actions = ArrayList<Action>()
        val y = list.minY(list.row("r2")) + 10

        pinDrag.beginGrouped(list, "p1", pinned = listOf("p1", "p2"), fromPinned = true, actions = actions)
        pinDrag.updateDrag(fingerY = y)
        frames.pump(pinDrag)
        assertEquals(DrawerPinDrag.HoverTarget.Recent, pinDrag.hoverTarget)
        assertTrue("Recents is lit", pinDrag.dropTarget != null && pinDrag.dropTarget?.groupID == null)
        assertEquals("the rows above it close up behind the pin", -list.stride, pinDrag.translation(list.g1), 0.5f)

        pinDrag.endDrag(fingerX = 10f, fingerY = y)
        frames.pump(pinDrag)
        assertTrue("it lands in its slot rather than jumping", host.groupDrops.isEmpty())
        assertEquals(Action.DrawerRowDropped("p1", null), actions.last())
        pinDrag.detach(host)
    }

    @Test
    fun anEmptyRecentsTakesAGroupedRowOnItsDropSlot() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = emptyList(), recents = emptyList())
        val recentsTop = list.minY(DrawerPinDrag.ItemKey.RecentsHeader)
        val slot = list.minY(DrawerPinDrag.ItemKey.RecentsDrop)

        pinDrag.beginGrouped(list, "m1", pinned = emptyList())
        pinDrag.updateDrag(fingerY = slot + 24)
        assertEquals("the header and its slot", DrawerPinDropTarget(null, recentsTop, list.section + list.stride), pinDrag.dropTarget)
        frames.pump(pinDrag)
        assertNull("the empty Pin slot stays shut", pinDrag.dropZone)

        pinDrag.endDrag(fingerX = 10f, fingerY = slot + 24)
        assertEquals(listOf<Pair<String, String?>>("m1" to null), host.groupDrops)
        pinDrag.detach(host)
    }

    @Test
    fun anEmptyRecentsSlotReceivesTheOnlyPinnedRow() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupedList(host, pins = listOf("p1"), recents = emptyList())
        val actions = ArrayList<Action>()
        val slot = list.minY(DrawerPinDrag.ItemKey.RecentsDrop)
        val closes = -(list.section + list.stride)

        pinDrag.beginGrouped(list, "p1", pinned = listOf("p1"), fromPinned = true, actions = actions)
        pinDrag.updateDrag(fingerY = slot + 24)
        frames.pump(pinDrag)
        assertEquals(DrawerPinDrag.HoverTarget.Recent, pinDrag.hoverTarget)
        assertEquals("Pinned closes above it", closes, pinDrag.translation(DrawerPinDrag.ItemKey.RecentsHeader), 0.5f)
        assertEquals("the slot moves with its header", closes, pinDrag.translation(DrawerPinDrag.ItemKey.RecentsDrop), 0.5f)
        assertEquals("the row lands on the slot", slot + closes, pinDrag.landingSlotMinY() ?: Float.NaN, 0.5f)

        pinDrag.endDrag(fingerX = 10f, fingerY = slot + 24)
        frames.pump(pinDrag)
        assertEquals(Action.DrawerRowDropped("p1", null), actions.last())
        assertTrue(host.groupDrops.isEmpty())
        pinDrag.detach(host)
    }

    /**
     * The list a group drag moves, laid out again whenever the store or the fold changes it:
     * Pinned (p1), the groups in [order] (g1: m1, m2; g2 closed; g3: n1), New Group, Recents (r1).
     * 56 a section header, 48 a group header or row.
     */
    private class GroupOrderList(val host: TestDrawerPinHost) {
        val rows = mapOf("g1" to listOf("m1", "m2"), "g2" to emptyList(), "g3" to listOf("n1"))
        var order = listOf("g1", "g2", "g3")
        var folded: String? = null
        val stride = 48f
        val section = 56f

        init {
            layout()
        }

        fun layout() {
            host.items.clear()
            var y = 0f
            fun place(key: DrawerPinDrag.ItemKey, height: Float) {
                host.items += DrawerPinDragItem(key, y, height)
                y += height
            }
            place(DrawerPinDrag.ItemKey.PinnedHeader, section)
            place(DrawerPinDrag.ItemKey.Row("p1"), stride)
            for (id in order) {
                place(header(id), stride)
                if (id != folded) rows.getValue(id).forEach { place(DrawerPinDrag.ItemKey.Row(it), stride) }
            }
            place(DrawerPinDrag.ItemKey.NewGroup, stride)
            place(DrawerPinDrag.ItemKey.RecentsHeader, section)
            place(DrawerPinDrag.ItemKey.Row("r1"), stride)
        }

        fun shownRows(): Map<String, List<String>> = order.associateWith { if (it == folded) emptyList() else rows.getValue(it) }
        fun header(id: String) = DrawerPinDrag.ItemKey.GroupHeader(id)
        fun minY(key: DrawerPinDrag.ItemKey) = host.items.first { it.key == key }.minY
        fun shown(pinDrag: DrawerPinDrag, key: DrawerPinDrag.ItemKey) = minY(key) + pinDrag.translation(key)

        /** The store took the drop: the group moves to [index], unfolded. */
        fun drop(id: String, index: Int) {
            order = (order - id).toMutableList().apply { add(index.coerceIn(0, size), id) }
            folded = null
            layout()
        }
    }

    /** Frames until [until], laying the list out again after any frame that folds it, as the list view does. */
    private fun Frames.pumpGroup(pinDrag: DrawerPinDrag, list: GroupOrderList, limit: Int = 600, until: () -> Boolean = { !pinDrag.groupDrag.isActive }) {
        var frames = 0
        while (!until() && frames < limit) {
            frame()
            if (list.folded != pinDrag.groupDrag.foldedID && pinDrag.groupDrag.foldedID != null) {
                list.folded = pinDrag.groupDrag.foldedID
                list.layout()
                pinDrag.groupDrag.relayout()
            }
            frames++
        }
    }

    private fun DrawerPinDrag.beginGroup(list: GroupOrderList, id: String, actions: MutableList<Action>, anchors: MutableList<Float> = ArrayList()) =
        groupDrag.begin(
            groupID = id,
            order = list.order,
            rows = { list.shownRows() },
            fingerY = list.minY(list.header(id)) + 24,
            reducedMotion = false,
            onSlotChanged = { actions += Action.DrawerPinSlotChanged },
            onDropReleased = { groupID, index -> actions += Action.DrawerGroupDropReleased(groupID, index) },
            onDrop = { groupID, index ->
                actions += Action.DrawerGroupDropped(groupID, index)
                groupDrag.takeAnchor()?.let { anchors += it.top }
                list.drop(groupID, index)
                groupDrag.relayout()
            },
        )

    @Test
    fun aDraggedGroupFoldsItsRowsTheOthersMakeRoomAndItLandsInItsNewPlace() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupOrderList(host)
        val drag = pinDrag.groupDrag
        val actions = ArrayList<Action>()
        val anchors = ArrayList<Float>()
        val sectionTop = list.minY(list.header("g1"))
        val recentsBefore = list.minY(DrawerPinDrag.ItemKey.RecentsHeader)

        // Lifted, g3's row fades and folds; Recents starts where it showed and slides up.
        pinDrag.beginGroup(list, "g3", actions, anchors)
        assertEquals("g3", drag.draggingID)
        assertNull("its rows fade before they fold", drag.foldedID)
        frames.pumpGroup(pinDrag, list, until = { drag.foldedID != null })
        assertEquals("g3", drag.foldedID)
        assertFalse(host.items.any { it.key == DrawerPinDrag.ItemKey.Row("n1") })
        assertEquals("nothing jumps as the rows fold", recentsBefore, list.shown(pinDrag, DrawerPinDrag.ItemKey.RecentsHeader), 1f)
        frames.pumpGroup(pinDrag, list, limit = 60)
        assertEquals(0f, pinDrag.translation(DrawerPinDrag.ItemKey.RecentsHeader), 0.5f)

        // Over the top of g1, g3 takes the first slot: every group below makes room for its header.
        val fingerY = sectionTop + 10
        drag.update(fingerY)
        frames.pumpGroup(pinDrag, list, limit = 60)
        assertEquals(0, drag.slot)
        assertEquals(Action.DrawerPinSlotChanged, actions.single())
        assertEquals("the header rides the finger", fingerY - 24, list.shown(pinDrag, list.header("g3")), 0.5f)
        for (key in listOf(list.header("g1"), DrawerPinDrag.ItemKey.Row("m1"), DrawerPinDrag.ItemKey.Row("m2"), list.header("g2"))) {
            assertEquals("$key makes room", list.stride, pinDrag.translation(key), 0.5f)
        }
        for (key in listOf(DrawerPinDrag.ItemKey.PinnedHeader, DrawerPinDrag.ItemKey.Row("p1"), DrawerPinDrag.ItemKey.NewGroup, DrawerPinDrag.ItemKey.RecentsHeader)) {
            assertEquals("$key stays", 0f, pinDrag.translation(key), 0.5f)
        }

        // Let go: it lands at the top of the groups, the store takes the order, and its row comes back.
        drag.end(fingerX = 10f, fingerY = fingerY)
        frames.pumpGroup(pinDrag, list, until = { actions.last() is Action.DrawerGroupDropped })
        assertEquals(listOf(Action.DrawerPinSlotChanged, Action.DrawerGroupDropReleased("g3", 0), Action.DrawerGroupDropped("g3", 0)), actions)
        assertEquals(listOf("g3", "g1", "g2"), list.order)
        assertEquals("the list keeps the landed header where it showed", sectionTop, anchors.single(), 0.5f)
        assertEquals(sectionTop, list.shown(pinDrag, list.header("g3")), 0.5f)
        assertEquals("g1 starts where it showed", sectionTop + list.stride, list.shown(pinDrag, list.header("g1")), 0.5f)
        assertEquals("the row comes back from under its header", sectionTop, list.shown(pinDrag, DrawerPinDrag.ItemKey.Row("n1")), 0.5f)
        assertEquals(0f, pinDrag.alpha(DrawerPinDrag.ItemKey.Row("n1")), 0.01f)
        frames.pumpGroup(pinDrag, list)
        assertFalse(drag.isActive)
        assertNull(drag.foldedID)
        assertTrue("everything rests in its new place", host.items.all { pinDrag.translation(it.key) == 0f })
        assertEquals(1f, pinDrag.alpha(DrawerPinDrag.ItemKey.Row("n1")), 0.01f)

        // And back: dragged below g2, g3 is last again.
        actions.clear()
        pinDrag.beginGroup(list, "g3", actions)
        frames.pumpGroup(pinDrag, list, until = { drag.foldedID != null })
        val lastSlot = list.minY(DrawerPinDrag.ItemKey.NewGroup) - list.stride
        drag.update(lastSlot + 24)
        frames.pumpGroup(pinDrag, list, limit = 60)
        assertEquals(2, drag.slot)
        drag.end(fingerX = 10f, fingerY = lastSlot + 24)
        frames.pumpGroup(pinDrag, list)
        assertEquals(Action.DrawerGroupDropped("g3", 2), actions.last())
        assertEquals(listOf("g1", "g2", "g3"), list.order)
        assertTrue(host.items.all { pinDrag.translation(it.key) == 0f })
        pinDrag.detach(host)
    }

    @Test
    fun aGroupLetGoOutsideTheListOrTakenAwayGoesBackAndAnotherDragEndsIt() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val list = GroupOrderList(host)
        val drag = pinDrag.groupDrag
        val actions = ArrayList<Action>()

        pinDrag.beginGroup(list, "g1", actions)
        frames.pumpGroup(pinDrag, list, until = { drag.foldedID != null })
        drag.update(list.minY(DrawerPinDrag.ItemKey.NewGroup))
        frames.pumpGroup(pinDrag, list, limit = 60)
        assertEquals(2, drag.slot)
        drag.end(fingerX = -500f, fingerY = list.minY(DrawerPinDrag.ItemKey.NewGroup))
        frames.pumpGroup(pinDrag, list)
        assertEquals("it goes back to its own slot", Action.DrawerGroupDropped("g1", 0), actions.last())
        assertEquals(listOf("g1", "g2", "g3"), list.order)
        assertTrue(host.items.all { pinDrag.translation(it.key) == 0f })

        // A closed group has nothing to fold; a stream taken away puts it back.
        pinDrag.beginGroup(list, "g2", actions)
        assertEquals("g2", drag.foldedID)
        drag.update(list.minY(list.header("g1")))
        frames.pumpGroup(pinDrag, list, limit = 30)
        drag.cancel()
        frames.pumpGroup(pinDrag, list)
        assertEquals(Action.DrawerGroupDropped("g2", 1), actions.last())
        assertFalse(drag.isActive)

        // A row lifted mid-drag ends the group's drag at once, leaving nothing folded.
        pinDrag.beginGroup(list, "g3", actions)
        frames.pumpGroup(pinDrag, list, limit = 5)
        pinDrag.beginDrag(
            sessionID = "r1",
            isPinnedSection = false,
            pinnedIDs = listOf("p1"),
            recentIDs = listOf("r1"),
            unpinnedRecentsIndex = 0,
            showsShortcuts = false,
            reducedMotion = false,
            fingerY = list.minY(DrawerPinDrag.ItemKey.Row("r1")) + 24,
            onZoneCrossed = {},
            onSlotChanged = {},
            onDrop = { _, _ -> },
            onSetLifted = {},
        )
        assertFalse(drag.isActive)
        assertNull(drag.foldedID)
        assertTrue(drag.alphas.isEmpty())
        pinDrag.cancelDrag()
        frames.pump(pinDrag)
        pinDrag.detach(host)
    }

    // endregion

    @Test
    fun deletingSessionHidesTheRowThenPurgesLocalStateOnceConfirmed() = homeTest { h ->
        val session = session("chat", "Chat", Instant.now())
        val key = key(session.id)
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "message", text = "Cached"))
        val state = h.state {
            ServerSharedKeys.cachedTranscriptSnapshots().set(
                CachedTranscriptSnapshots(entries = listOf(CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = session.id, updatedAt = session.updatedAt, items = items))),
            )
            HomeFeature.State(server, snapshot(listOf(session), active = setOf(session.id)))
                .updatingPinnedSessionKeys { listOf(key) }
                .updatingLastViewedSessionIDs { it + (server.persistenceID to session.id) }
                .updatingSessionReadDates { it + (key to session.updatedAt) }
                .updatingManuallyUnreadSessionKeys { it + key }
                .copy(transcriptCache = mapOf(session.id to items))
        }
        val gate = Gate()
        val store = h.store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(deleteSession = { _, _ -> gate.await() })
        }
        store.exhaustive = false

        store.send(Action.DeleteSessionTapped(session.id)) { it.copy(pendingDeletion = session) }
        // Only the row goes; phone-local records wait for the server.
        store.send(Action.DeleteSessionConfirmed) {
            it.copy(pendingDeletion = null, sessions = emptyList(), activeSessionIDs = emptySet(), recentlyDeletedIDs = setOf(session.id))
        }
        assertEquals(listOf(key), store.state.pinnedSessionKeys)
        assertTrue(store.state.pinnedSessions.isEmpty())
        assertEquals(session.id, store.state.lastViewedSessionIDs[server.persistenceID])
        assertEquals(items, store.state.transcriptCache[session.id])
        assertEquals(1, h.state { ServerSharedKeys.cachedTranscriptSnapshots().value }.entries.size)

        gate.yield()
        gate.finish()
        store.receive(Action.DeleteSessionResponse::class) {
            it.copy(
                transcriptCache = emptyMap(),
                pinnedSessionKeys = emptyList(),
                lastViewedSessionIDs = it.lastViewedSessionIDs - server.persistenceID,
                sessionReadDates = it.sessionReadDates - key,
                manuallyUnreadSessionKeys = it.manuallyUnreadSessionKeys - key,
            )
        }
        assertTrue(store.state.transcriptCache.isEmpty())
        assertTrue(store.state.pinnedSessionKeys.isEmpty())
        assertNull(store.state.lastViewedSessionIDs[server.persistenceID])
        assertNull(store.state.sessionReadDates[key])
        assertFalse(key in store.state.manuallyUnreadSessionKeys)
        assertTrue(h.state { ServerSharedKeys.cachedTranscriptSnapshots().value }.entries.isEmpty())
        store.finish()
    }

    @Test
    fun failedSessionDeletionRestoresTheRowAndBookkeeping() = homeTest { h ->
        val session = session("ses_restore", "Restore me", date(20))
        val other = session("ses_other", "Other", date(10))
        val key = key(session.id)
        val openItems = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m1", text = "Open"))
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(session, other), active = setOf(session.id)))
                .copy(sessionDetail = SessionFeature.State.session(server, session, cachedItems = openItems), attentionSessionIDs = setOf(session.id))
                .updatingPinnedSessionKeys { listOf(key) }
                .updatingLastViewedSessionIDs { it + (server.persistenceID to session.id) }
                .updatingSessionReadDates { it + (key to session.updatedAt) }
        }
        val clock = TestClock()
        val gate = Gate()
        val store = h.store(state) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                deleteSession = { _, _ ->
                    gate.await()
                    throw OpenCodeClientError("Offline")
                },
            )
        }
        store.exhaustive = false

        store.send(Action.DeleteSessionTapped(session.id))
        store.send(Action.DeleteSessionConfirmed)
        assertEquals(listOf(other), store.state.sessions)
        assertEquals(true, store.state.sessionDetail?.isDraft)
        assertEquals(listOf(key), store.state.pinnedSessionKeys)
        assertEquals(session.id, store.state.lastViewedSessionIDs[server.persistenceID])

        // Disk writes made while the request is in flight must survive a failure.
        val otherItems = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m2", text = "Other"))
        store.send(Action.TranscriptPrefetched(sessionID = other.id, items = otherItems))
        assertEquals(listOf(other.id), h.state { ServerSharedKeys.cachedTranscriptSnapshots().value }.entries.map { it.sessionID })

        gate.yield()
        gate.finish()
        store.receive(Action.DeleteSessionResponse::class) {
            it.copy(
                sessions = listOf(session, other),
                activeSessionIDs = setOf(session.id),
                attentionSessionIDs = setOf(session.id),
                recentlyDeletedIDs = emptySet(),
                deletionError = DrawerFailure(DrawerFailure.Action.delete, "Restore me", "Offline"),
            )
        }
        assertEquals(listOf(session, other), store.state.sessions)
        assertEquals("Couldn’t delete “Restore me”", store.state.deletionError?.title)
        assertEquals(session, store.state.sessionDetail?.session)
        assertEquals(openItems, store.state.sessionDetail?.items)
        assertEquals(listOf(other.id), h.state { ServerSharedKeys.cachedTranscriptSnapshots().value }.entries.map { it.sessionID })
        assertEquals(listOf(key), store.state.pinnedSessionKeys)
        assertEquals(session.id, store.state.lastViewedSessionIDs[server.persistenceID])
        assertEquals(session.updatedAt, store.state.sessionReadDates[key])
        clock.advance(5.seconds)
        store.receive(Action.ClearDeletionError) { it.copy(deletionError = null) }
        store.finish()
    }

    @Test
    fun aSessionThatFinishesWhileOpenAfterTheDrawerSwappedItInStaysRead() = homeTest { h ->
        val first = session("ses_first", "First", date(20), idle = 1_000, viewed = 1_000)
        var second = session("ses_second", "Second", date(10), idle = 1_000, viewed = 1_000)
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(first, second), active = setOf(second.id)))
                .openingDetail(first, cachedItems = emptyList())
                // The session screen appeared once, for the first session.
                .updatingDetail { it.copy(isVisible = true) }
                .let { it.copy(transcriptCache = it.transcriptCache + (second.id to listOf(OpenCodeTranscriptItem.UserText(id = "m2", text = "Second")))) }
        }
        val secondAtStart = second
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                getSession = { _, id -> if (id == first.id) first else secondAtStart },
                transcript = { _, _, _ -> emptyList() },
                viewSession = { _, _, _ -> },
            )
        }
        store.exhaustive = false

        store.send(Action.SessionTapped(second.id))
        assertEquals(second.id, store.state.sessionDetail?.session?.id)
        assertEquals("the same screen is still showing", true, store.state.sessionDetail?.isVisible)

        // Its run finishes while the reader watches.
        second = second.copy(updatedAt = date(30), idleMilliseconds = 3_000)
        store.send(Action.SnapshotRefreshed(snapshot(listOf(first, second))))
        assertFalse(store.state.isUnread(store.state.sessions.first { it.id == second.id }))
        h.skipInFlightEffects()
    }

    @Test
    fun failedDeletionLeavesAReaderWhoMovedOnWhereTheyAre() = homeTest { h ->
        val session = session("ses_restore", "Restore me", date(20))
        val other = session("ses_other", "Other", date(10))
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(session, other)))
                .copy(sessionDetail = SessionFeature.State.session(server, session, cachedItems = emptyList()))
                .updatingLastViewedSessionIDs { it + (server.persistenceID to session.id) }
                .copy(transcriptCache = mapOf(other.id to listOf(OpenCodeTranscriptItem.UserText(id = "m2", text = "Other"))))
        }
        val gate = Gate()
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                deleteSession = { _, _ ->
                    gate.await()
                    throw OpenCodeClientError("Offline")
                },
            )
        }
        store.exhaustive = false

        store.send(Action.DeleteSessionTapped(session.id))
        store.send(Action.DeleteSessionConfirmed)
        store.send(Action.SessionTapped(other.id))
        gate.yield()
        gate.finish()
        store.receive(Action.DeleteSessionResponse::class)
        assertEquals(listOf(session, other), store.state.sessions)
        assertEquals(other, store.state.sessionDetail?.session)
        assertEquals(other.id, store.state.lastViewedSessionIDs[server.persistenceID])
        store.receive(Action.ClearDeletionError)
        store.finish()
    }

    @Test
    fun openAndPinnedSessionsSurviveFallingOffTheSnapshotPage() = homeTest { h ->
        val open = session("ses_open", "Open", date(10))
        val pinned = session("ses_pinned", "Pinned", date(20))
        val forgotten = session("ses_old", "Old", date(30))
        val newest = session("ses_new", "New", date(40))
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(open, pinned, forgotten)))
                .copy(sessionDetail = SessionFeature.State.session(server, open, cachedItems = emptyList()))
                .updatingPinnedSessionKeys { listOf(key(pinned.id)) }
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        // The newest-50 page now starts with sessions created since; the three we knew have all fallen off it.
        store.send(Action.SnapshotRefreshed(snapshot(listOf(newest))))
        assertEquals(open, store.state.sessionDetail?.session)
        assertEquals(listOf(newest.id, open.id, pinned.id), store.state.sessions.map { it.id })
        assertEquals(listOf(pinned), store.state.pinnedSessions)
        assertEquals(listOf(newest.id, open.id), store.state.recentSessions.map { it.id })

        // A real deletion still lands through its event.
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = pinned.id)))
        assertTrue(store.state.pinnedSessions.isEmpty())
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = open.id)))
        assertEquals(true, store.state.sessionDetail?.isDraft)
        h.skipInFlightEffects()
    }

    private fun sendClient(open: OpenCodeSession) = OpenCodeClient.testValue.copy(
        homeAttention = { _, _, _ -> emptySet() },
        message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
        sendPrompt = { _, _, _, _, _, _, _ -> },
        waitForIdle = { _, _ -> awaitCancellation() },
        getSession = { _, _ -> open },
        pendingInputs = { _, _ -> emptyList() },
        transcript = { _, _, _ -> emptyList() },
    )

    @Test
    fun sendingMovesTheSessionToTheTopOfRecentsBeforeTheServerCatchesUp() = homeTest { h ->
        val open = session("ses_open", "Open", date(10))
        val newer = session("ses_newer", "Newer", date(50))
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(newer, open)))
                .copy(sessionDetail = SessionFeature.State.session(server, open, cachedItems = emptyList()).withDraft("Ship it"))
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(100))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = sendClient(open)
        }
        store.exhaustive = false
        assertEquals(listOf(newer.id, open.id), store.state.recentSessions.map { it.id })

        store.send(detail(SessionFeature.Action.SendTapped))
        assertEquals(listOf(open.id, newer.id), store.state.recentSessions.map { it.id })
        assertFalse("The reader's own send is not unread", store.state.isUnread(store.state.sessions.first { it.id == open.id }))

        // A list fetched before the server recorded the send must not demote it.
        store.send(Action.SnapshotRefreshed(snapshot(listOf(newer, open))))
        assertEquals(listOf(open.id, newer.id), store.state.recentSessions.map { it.id })

        // Once the server reports the send, the server wins again.
        val caughtUp = open.copy(updatedAt = date(120))
        store.send(Action.SnapshotRefreshed(snapshot(listOf(newer, caughtUp))))
        assertEquals(caughtUp.updatedAt, store.state.sessions.first { it.id == open.id }.updatedAt)
        assertTrue(store.state.localActivity.isEmpty())
        h.skipInFlightEffects()
    }

    @Test
    fun everySendPathMovesTheSessionToTheTopOfRecents() {
        val paths = listOf(
            SessionFeature.Action.DeferredSendReady(OpenCodeDelivery.Steer),
            SessionFeature.Action.DeferredSendReady(OpenCodeDelivery.Queue),
            SessionFeature.Action.QueueTapped,
        )
        for (send in paths) {
            homeTest { h ->
                val open = session("ses_open", "Open", date(10))
                val newer = session("ses_newer", "Newer", date(50))
                val store = recentsSendStore(h, open, newer, SessionFeature.State.session(server, open, cachedItems = emptyList()).withDraft("Ship it"))

                store.send(detail(send))
                assertEquals("$send", listOf(open.id, newer.id), store.state.recentSessions.map { it.id })
                h.skipInFlightEffects()
            }
        }
    }

    @Test
    fun aSendWaitingOnUploadsMovesTheSessionOnceItGoes() = homeTest { h ->
        val open = session("ses_open", "Open", date(10))
        val newer = session("ses_newer", "Newer", date(50))
        val file = ComposerFileAttachment(id = UUID(0, 1), name = "notes.txt", mime = "text/plain", size = 4, localPath = "/tmp/notes.txt")
        val running = SessionFeature.State.session(server, open, cachedItems = emptyList())
            .withPendingFiles(listOf(file))
            .copy(isAwaitingReply = true)
        val store = recentsSendStore(h, open, newer, running)

        store.send(detail(SessionFeature.Action.SendTapped))
        assertTrue("A send still waiting on its uploads hasn't gone", store.state.localActivity.isEmpty())
        assertEquals(listOf(newer.id, open.id), store.state.recentSessions.map { it.id })

        store.send(detail(SessionFeature.Action.fileUploadFinished(file.id, ComposerAttachmentsFeature.Outcome.Success("/tmp/opencode/uploads/notes.txt"))))
        store.skipReceivedActions()
        assertEquals(listOf(open.id, newer.id), store.state.recentSessions.map { it.id })
        h.skipInFlightEffects()
    }

    private fun recentsSendStore(h: HomeFeatureTestSupport.Harness, open: OpenCodeSession, newer: OpenCodeSession, detail: SessionFeature.State): TestStore<HomeFeature.State, Action> {
        val state = h.state { HomeFeature.State(server, snapshot(listOf(newer, open))).copy(sessionDetail = detail) }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(100))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = sendClient(open)
        }
        store.exhaustive = false
        return store
    }

    @Test
    fun serverTimeBehindAFastPhoneClockStillSettlesTheLocalSend() = homeTest { h ->
        val open = session("ses_open", "Open", date(10))
        val newer = session("ses_newer", "Newer", date(50))
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(newer, open)))
                .copy(sessionDetail = SessionFeature.State.session(server, open, cachedItems = emptyList()).withDraft("Ship it"))
        }
        // The phone's clock runs an hour ahead of the server's.
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(3_600))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = sendClient(open)
        }
        store.exhaustive = false

        store.send(detail(SessionFeature.Action.SendTapped))
        assertEquals(listOf(open.id, newer.id), store.state.recentSessions.map { it.id })

        val caughtUp = open.copy(updatedAt = date(60))
        store.send(Action.SnapshotRefreshed(snapshot(listOf(newer, caughtUp))))
        assertEquals(caughtUp.updatedAt, store.state.sessions.first { it.id == open.id }.updatedAt)
        assertTrue(store.state.localActivity.isEmpty())
        assertEquals(listOf(open.id, newer.id), store.state.recentSessions.map { it.id })
        h.skipInFlightEffects()
    }

    @Test
    fun deletingOpenSessionReturnsToDraftAndStaleSnapshotCannotResurrectIt() = homeTest { h ->
        val session = session("ses_open", "Open", Instant.now())
        val snapshot = snapshot(listOf(session))
        val state = h.state {
            HomeFeature.State(server, snapshot)
                .copy(sessionDetail = SessionFeature.State.session(server, session, cachedItems = emptyList()))
                .updatingLastViewedSessionIDs { it + (server.persistenceID to session.id) }
        }
        val gate = Gate()
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                deleteSession = { _, _ -> gate.await() },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.exhaustive = false

        store.send(Action.DeleteSessionTapped(session.id))
        store.send(Action.DeleteSessionConfirmed)
        assertEquals(true, store.state.sessionDetail?.isDraft)
        store.send(Action.SnapshotRefreshed(snapshot))
        assertTrue(store.state.sessions.isEmpty())
        assertEquals(setOf(session.id), store.state.recentlyDeletedIDs)
        gate.yield()
        gate.finish()
        store.receive(Action.DeleteSessionResponse::class)
        assertNull(store.state.lastViewedSessionIDs[server.persistenceID])
        store.finish()
    }

    @Test
    fun idleSnapshotRevalidatesOpenTranscriptEvenWhenAlreadyViewed() = homeTest { h ->
        val session = session("ses_resume", "Resume", date(10), idle = 2_000, viewed = 2_000)
        val snapshot = snapshot(listOf(session))
        val old = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m1", text = "Hello"))
        val latest = old + OpenCodeTranscriptItem.AssistantMarkdown(id = "m2", text = "Finished while away")
        val state = h.state {
            HomeFeature.State(server, snapshot)
                .copy(sessionDetail = SessionFeature.State.session(server, session).copy(items = old, isLoading = false))
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> latest },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.exhaustive = false
        store.send(Action.SnapshotRefreshed(snapshot))
        store.receiveDetail(SessionFeature.Action.RefreshResponse::class)
        assertEquals(latest, store.state.sessionDetail?.items)
        store.finish()
    }

    @Test
    fun serverViewedWatermarkWinsOverLegacyReadDatesAndDoesNotCoverNewerIdle() = homeTest { h ->
        val session = session("ses_view", "Viewed", date(10), idle = 2_000, viewed = 1_000)
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session))) })
        assertTrue(store.state.isUnread(session))
        store.send(Action.ViewAcknowledged(sessionID = session.id, idle = 1_500)) {
            it.copy(sessions = listOf(it.sessions[0].copy(viewedMilliseconds = 1_500)))
        }
        assertTrue(store.state.isUnread(store.state.sessions[0]))
        store.send(Action.ViewAcknowledged(sessionID = session.id, idle = 2_000)) {
            it.copy(sessions = listOf(it.sessions[0].copy(viewedMilliseconds = 2_000)))
        }
        assertFalse(store.state.isUnread(store.state.sessions[0]))
        store.send(Action.MarkUnread(session.id)) {
            it.copy(
                sessionReadDates = it.sessionReadDates + (key(session.id) to session.updatedAt.minusSeconds(1)),
                manuallyUnreadSessionKeys = it.manuallyUnreadSessionKeys + key(session.id),
            )
        }
        assertTrue(store.state.isUnread(store.state.sessions[0]))
    }

    @Test
    fun staleSnapshotAndFailedReceiptDoNotResurrectOpenedUnreadSession() = homeTest { h ->
        val session = session("ses_stale", "Stale", date(10), idle = 2_000, viewed = 1_000)
        val snapshot = snapshot(listOf(session))
        val state = h.state { HomeFeature.State(server, snapshot).copy(transcriptCache = mapOf(session.id to emptyList())) }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                viewSession = { _, _, _ -> throw OpenCodeClientError("Offline") },
                homeAttention = { _, _, _ -> emptySet() },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, root -> ai.opencode.server.model.OpenCodeSessionFamily(listOf(root), emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> ai.opencode.server.model.OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> throw OpenCodeClientError("Offline") },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.SessionTapped(session.id))
        assertEquals(2_000L, store.state.sessions[0].viewedMilliseconds)
        store.send(Action.SnapshotRefreshed(snapshot))
        assertEquals(2_000L, store.state.sessions[0].viewedMilliseconds)
        assertEquals(HomeFeature.State.Indicator.none, store.state.indicator(store.state.sessions[0]))
        h.skipInFlightEffects()
    }

    @Test
    fun visibleRunningSessionStaysReadWhenItsIdleWatermarkArrives() = homeTest { h ->
        val running = session("ses_running", "Running", date(10))
        val idle = running.copy(updatedAt = date(20), idleMilliseconds = 3_000)
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(running), active = setOf(running.id))).copy(
                transcriptCache = mapOf(running.id to emptyList()),
                sessionDetail = SessionFeature.State.session(server, running, cachedItems = emptyList(), isRunning = true)
                    .copy(isVisible = true, hasRenderedFirstContentFrame = true),
            )
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = { _, _ -> idle },
                transcript = { _, _, _ -> emptyList() },
                viewSession = { _, _, watermark -> assertEquals(3_000L, watermark) },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.exhaustive = false

        store.send(Action.SnapshotRefreshed(snapshot(listOf(idle))))
        assertEquals(3_000L, store.state.sessions[0].viewedMilliseconds)
        assertEquals(HomeFeature.State.Indicator.none, store.state.indicator(store.state.sessions[0]))
        store.receiveDetail(SessionFeature.Action.ViewAcknowledged::class)
        assertEquals(3_000L, store.state.sessions[0].viewedMilliseconds)
        h.skipInFlightEffects()
    }

    @Test
    fun pinningAndReadStateAreLocalReversibleChoices() = homeTest { h ->
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val now = Instant.now()
        val first = OpenCodeSession(id = "ses_1", serverTitle = "First", projectID = "proj_1", directory = "/tmp/demo", updatedAt = now)
        val second = OpenCodeSession(id = "ses_2", serverTitle = "Second", projectID = "proj_1", directory = "/tmp/demo", updatedAt = now)
        val keyFirst = "${server.persistenceID}|${first.id}"
        val keySecond = "${server.persistenceID}|${second.id}"
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(first, second)))
                .updatingSessionReadDates { it + (keyFirst to first.updatedAt) + (keySecond to second.updatedAt) }
        }
        val store = h.store(state)

        store.send(Action.TogglePin(second.id)) { it.copy(pinnedSessionKeys = listOf(keySecond)) }
        assertEquals(listOf(second), store.state.pinnedSessions)
        store.send(Action.TogglePin(second.id)) { it.copy(pinnedSessionKeys = emptyList()) }
        assertEquals(setOf(first.id, second.id), store.state.visibleSessions.map { it.id }.toSet())
        store.send(Action.MarkUnread(first.id)) { it.copy(sessionReadDates = it.sessionReadDates + (keyFirst to first.updatedAt.minusSeconds(1))) }
        assertEquals(setOf(first.id), store.state.sessions.filter { store.state.isUnread(it) }.map { it.id }.toSet())
        store.send(Action.MarkRead(first.id)) { it.copy(sessionReadDates = it.sessionReadDates + (keyFirst to first.updatedAt)) }
    }

    @Test
    fun indicatorsPrioritizeAttentionThenRunningThenUnread() = homeTest { h ->
        h.state {
            val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
            val session = OpenCodeSession(id = "ses_1", serverTitle = "Work", projectID = "proj_1", directory = "/tmp/demo", updatedAt = date(2_000))
            var state = HomeFeature.State(server, snapshot(listOf(session)))
                .updatingSessionReadDates { it + ("${server.persistenceID}|${session.id}" to date(1_000)) }
            assertEquals(HomeFeature.State.Indicator.unread, state.indicator(session))
            state = state.copy(activeSessionIDs = setOf(session.id))
            assertEquals(HomeFeature.State.Indicator.running, state.indicator(session))
            state = state.copy(attentionSessionIDs = setOf(session.id))
            assertEquals(HomeFeature.State.Indicator.attention, state.indicator(session))
        }
    }

    @Test
    fun runningAndUnreadSessionsRemainInRecents() = homeTest { h ->
        h.state {
            val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
            val now = Instant.now()
            val running = OpenCodeSession(id = "ses_running", serverTitle = "Running", projectID = "proj", directory = "/tmp/demo", updatedAt = now)
            val recent = OpenCodeSession(id = "ses_recent", serverTitle = "Recent", projectID = "proj", directory = "/tmp/demo", updatedAt = now)
            var state = HomeFeature.State(server, snapshot(listOf(running, recent), active = setOf(running.id)))
                .updatingSessionReadDates { it + ("${server.persistenceID}|${running.id}" to running.updatedAt) + ("${server.persistenceID}|${recent.id}" to recent.updatedAt) }

            assertEquals(setOf(running.id, recent.id), state.recentSessions.map { it.id }.toSet())
            state = state.updatingManuallyUnreadSessionKeys { it + "${server.persistenceID}|${recent.id}" }
            assertEquals(setOf(running.id, recent.id), state.recentSessions.map { it.id }.toSet())
        }
    }

    @Test
    fun pinnedChatStaysPinnedWhileItNeedsInput() = homeTest { h ->
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "chat", serverTitle = "Chat", projectID = "p", directory = "/project", updatedAt = Instant.now())
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session))) })
        store.send(Action.TogglePin(session.id)) { it.copy(pinnedSessionKeys = listOf("${server.persistenceID}|${session.id}")) }
        store.send(Action.AttentionRefreshed(setOf(session.id))) { it.copy(attentionSessionIDs = setOf(session.id)) }
        assertEquals(listOf(session), store.state.pinnedSessions)
        assertTrue(store.state.recentSessions.isEmpty())
        assertTrue(store.state.isPinned(session.id))
        store.send(Action.AttentionRefreshed(emptySet())) { it.copy(attentionSessionIDs = emptySet()) }
        assertEquals(listOf(session), store.state.pinnedSessions)
        assertTrue(store.state.recentSessions.isEmpty())
    }
}
