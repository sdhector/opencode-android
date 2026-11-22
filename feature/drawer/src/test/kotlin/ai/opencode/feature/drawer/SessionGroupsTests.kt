package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.strings.localized
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.unsentDrafts
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.feature.drawer.groups.DraftGroupTarget
import ai.opencode.feature.drawer.groups.DrawerPath
import ai.opencode.feature.drawer.groups.DrawerSharedKeys
import ai.opencode.feature.drawer.groups.GroupEdit
import ai.opencode.feature.drawer.groups.GroupSharingPrompt
import ai.opencode.feature.drawer.groups.GroupSync
import ai.opencode.feature.drawer.groups.GroupWrite
import ai.opencode.feature.drawer.groups.PendingImport
import ai.opencode.feature.drawer.groups.SessionGroup
import ai.opencode.feature.drawer.groups.SessionGroups
import ai.opencode.feature.drawer.groups.SharedGroupsServer
import ai.opencode.feature.drawer.groups.SharedSessionGroups
import ai.opencode.server.model.OpenCodeRpcError
import ai.opencode.server.model.OpenCodeSessionGroup
import ai.opencode.server.model.OpenCodeSessionGroupsCapabilities
import ai.opencode.server.model.OpenCodeSessionGroupsSnapshot
import ai.opencode.server.model.OpenCodeSessionGroupsWrite
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import ai.opencode.feature.drawer.list.DrawerListGroupHeader
import ai.opencode.feature.drawer.list.DrawerListInputs
import ai.opencode.feature.drawer.list.DrawerListItem
import ai.opencode.feature.drawer.list.DrawerShownPins
import ai.opencode.feature.drawer.list.DrawerListModel
import ai.opencode.feature.drawer.list.DrawerListSection
import ai.opencode.feature.drawer.style.DrawerSectionHeaderKind
import ai.opencode.feature.session.BindingAction
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Local Session Groups: the model, the drawer's projection of it, and the draft that starts in a group. */
class SessionGroupsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val otherServer = OpenCodeServer(url = "http://localhost:4097", username = "", password = "")
    private val serverID = server.persistenceID

    private fun session(id: String, updatedAt: Long, archived: Boolean = false) = OpenCodeSession(
        id = id, serverTitle = "Title $id", projectID = "p", directory = "/project",
        updatedAt = Instant.ofEpochSecond(updatedAt), archivedMilliseconds = if (archived) 1L else null,
    )

    private fun groups(vararg groups: SessionGroup, on: OpenCodeServer = server) =
        SessionGroups(mapOf(on.persistenceID to groups.toList()))

    private fun group(id: String, vararg members: String, collapsed: Boolean = false) =
        SessionGroup(id = id, name = "Group $id", isCollapsed = collapsed, sessionIDs = members.toList())

    private fun HomeFeature.State.model(liftedID: String? = null, foldedGroupID: String? = null): DrawerListModel = DrawerListModel.build(
        state = this,
        inputs = DrawerListInputs(selectedSessionID = null, reducedMotion = true, increasedContrast = false),
        liftedID = liftedID,
        now = Instant.EPOCH,
        timestamp = { _, _ -> "" },
        project = { _, _ -> null },
        foldedGroupID = foldedGroupID,
    )

    private fun DrawerListModel.sessionIDs(): List<String> = items.filterIsInstance<DrawerListItem.Session>().map { it.id }

    // region Model

    @Test
    fun aSessionBelongsToAtMostOneGroupAndLeavingTakesItOutOfAll() {
        var store = SessionGroups()
            .creating(serverID, "a", "Alpha")
            .creating(serverID, "b", "Beta")
            .assigning(serverID, listOf("s1", "s2"), "a")
        assertEquals(listOf("s1", "s2"), store.group(serverID, "a")?.sessionIDs)
        store = store.assigning(serverID, listOf("s2"), "b")
        assertEquals(listOf("s1"), store.group(serverID, "a")?.sessionIDs)
        assertEquals(listOf("s2"), store.group(serverID, "b")?.sessionIDs)
        assertEquals("b", store.groupID(serverID, "s2"))
        store = store.assigning(serverID, listOf("s1", "s2"), null)
        assertNull(store.groupID(serverID, "s1"))
        assertNull(store.groupID(serverID, "s2"))
        assertEquals("both groups survive", 2, store.groups(serverID).size)
        assertEquals("a group that doesn't exist takes no one", store, store.assigning(serverID, listOf("s1"), "missing"))
    }

    @Test
    fun removingAGroupOnlyRemovesItsMemberships() {
        val store = SessionGroups()
            .creating(serverID, "a", "Alpha")
            .creating(serverID, "b", "Beta")
            .assigning(serverID, listOf("s1"), "a")
            .assigning(serverID, listOf("s2"), "b")
            .removing(serverID, "a")
        assertEquals(listOf("b"), store.groups(serverID).map { it.id })
        assertNull(store.groupID(serverID, "s1"))
        assertEquals("b", store.groupID(serverID, "s2"))
        assertTrue("the last group takes the server's entry with it", store.removing(serverID, "b").byServer.isEmpty())
    }

    @Test
    fun groupsAreScopedToTheirServer() {
        val store = SessionGroups()
            .creating(serverID, "a", "Alpha")
            .assigning(serverID, listOf("s1"), "a")
        assertTrue(store.groups(otherServer.persistenceID).isEmpty())
        assertNull(store.groupID(otherServer.persistenceID, "s1"))
        assertEquals("another server's group of the same ID stays apart", store, store.removing(otherServer.persistenceID, "a"))
        assertEquals(store, store.assigning(otherServer.persistenceID, listOf("s1"), "a"))
    }

    @Test
    fun namesAreTrimmedBoundedAndNeverBlank() {
        assertNull(SessionGroups.normalizedName("   "))
        assertEquals("Work", SessionGroups.normalizedName("  Work "))
        assertEquals(SessionGroups.MAX_NAME_LENGTH, SessionGroups.normalizedName("x".repeat(100))?.length)
        val store = SessionGroups().creating(serverID, "a", "Alpha")
        assertEquals(store, store.creating(serverID, "b", " "))
        assertEquals(store, store.renaming(serverID, "a", ""))
        assertEquals("Renamed", store.renaming(serverID, "a", " Renamed ").group(serverID, "a")?.name)
    }

    @Test
    fun movingAGroupPutsItInItsNewPlaceAndKeepsTheRestInOrder() {
        val store = SessionGroups(mapOf(serverID to listOf(group("one"), group("two", "s1", collapsed = true), group("three"))))
        fun SessionGroups.ids() = groups(serverID).map { it.id }
        val twoFirst = store.moving(serverID, "two", 0)
        assertEquals(listOf("two", "one", "three"), twoFirst.ids())
        assertEquals("a moved group keeps its name, members and collapse", store.group(serverID, "two"), twoFirst.group(serverID, "two"))
        assertEquals("and it moves back", store, twoFirst.moving(serverID, "two", 1))
        assertEquals("past the end is last", listOf("one", "three", "two"), store.moving(serverID, "two", 99).ids())
        assertEquals("before the start is first", listOf("two", "one", "three"), store.moving(serverID, "two", -1).ids())
        assertEquals("its own place changes nothing", store, store.moving(serverID, "one", 0))
        assertEquals(store, store.moving(serverID, "missing", 0))
        assertEquals("another server's order is its own", store, store.moving(otherServer.persistenceID, "two", 0))
    }

    // endregion

    // region Projection

    @Test
    fun groupedSessionsLeaveRecentsAndShowInsideTheirGroupNewestFirst() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30), session("c", 20), session("d", 10))
        val state = h.state { HomeFeature.State(server, snapshot(sessions)) }
            .copy(sessionGroups = groups(group("g", "c", "b"), group("empty")))
        val drawer = state.drawerSessions
        assertEquals(listOf("a", "d"), drawer.recents.map { it.id })
        assertEquals(listOf("g", "empty"), drawer.groups.map { it.group.id })
        assertEquals(listOf("b", "c"), drawer.groups[0].sessions.map { it.id })
        assertTrue(drawer.groups[1].sessions.isEmpty())

        val model = state.model()
        assertEquals(
            "the root shows each group as one row",
            listOf(DrawerListItem.GroupHeader("g"), DrawerListItem.GroupHeader("empty"), DrawerListItem.NewGroup),
            model.sections[DrawerListSection.groups],
        )
        assertEquals(listOf("a", "d"), model.sections[DrawerListSection.recents]!!.filterIsInstance<DrawerListItem.Session>().map { it.id })
        assertEquals(listOf("g", "empty"), model.groupHeaders.keys.toList())
        assertEquals(2, model.groupHeaders["g"]?.count)
        assertNull(model.rows["b"])
        assertNull(model.rows["a"]?.groupID)

        val inside = state.copy(drawerPath = DrawerPath(listOf("g"))).model()
        assertEquals(listOf("b", "c"), inside.sessionIDs())
        assertEquals("g", inside.rows["b"]?.groupID)
        assertTrue(inside.rows["b"]?.inGroup == true)
    }

    @Test
    fun aPinnedMemberShowsOnlyInPinned() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30), session("c", 20))
        val base = h.state { HomeFeature.State(server, snapshot(sessions)) }
        val state = base.copy(
            sessionGroups = groups(group("g", "b", "c")),
            pinnedSessionKeys = listOf(base.sessionKey("b")),
        )
        val drawer = state.drawerSessions
        assertEquals(listOf("b"), drawer.pinned.map { it.id })
        assertEquals(listOf("c"), drawer.groups[0].sessions.map { it.id })
        val ids = state.model().sessionIDs()
        assertEquals("no row shows twice", ids.distinct(), ids)
        assertEquals(setOf("a", "b"), ids.toSet())
        assertEquals("inside the group only the unpinned member shows", listOf("c"), state.copy(drawerPath = DrawerPath(listOf("g"))).model().sessionIDs())
        assertEquals("the pinned row still knows its group, for Remove from Group", "g", state.model().rows["b"]?.groupID)
        assertFalse(state.model().rows["b"]?.inGroup == true)
    }

    @Test
    fun theListRunsPinnedThenGroupsThenRecents() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30), session("c", 20))
        val base = h.state { HomeFeature.State(server, snapshot(sessions)) }
        fun DrawerListModel.shown() = items.filter {
            it is DrawerListItem.Header || it is DrawerListItem.Session || it is DrawerListItem.GroupHeader || it == DrawerListItem.NewGroup
        }
        val pinned = base.copy(pinnedSessionKeys = listOf(base.sessionKey("b")))

        assertEquals(
            listOf(
                DrawerListItem.Header(DrawerSectionHeaderKind.Pinned), DrawerListItem.Session("b"),
                DrawerListItem.GroupHeader("g"), DrawerListItem.NewGroup,
                DrawerListItem.Header(DrawerSectionHeaderKind.Recents), DrawerListItem.Session("a"),
            ),
            pinned.copy(sessionGroups = groups(group("g", "c"))).model().shown(),
        )
        assertEquals(
            "without groups New Group still follows Pinned",
            listOf(
                DrawerListItem.Header(DrawerSectionHeaderKind.Pinned), DrawerListItem.Session("b"), DrawerListItem.NewGroup,
                DrawerListItem.Header(DrawerSectionHeaderKind.Recents), DrawerListItem.Session("a"), DrawerListItem.Session("c"),
            ),
            pinned.model().shown(),
        )
        assertEquals(
            "with nothing pinned the groups lead",
            listOf(
                DrawerListItem.GroupHeader("g"), DrawerListItem.NewGroup,
                DrawerListItem.Header(DrawerSectionHeaderKind.Recents), DrawerListItem.Session("a"), DrawerListItem.Session("b"),
            ),
            base.copy(sessionGroups = groups(group("g", "c"))).model().shown(),
        )
        assertEquals("the pin drag is told no group shows rows at the root", emptyList<String>(), pinned.copy(sessionGroups = groups(group("g", "c"))).model().groupHeaders["g"]?.sessionIDs)
    }

    @Test
    fun aGroupSavedCollapsedShowsAsAnyOtherAndSearchListsItsMatchesUnderIt() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30))
        val state = h.state { HomeFeature.State(server, snapshot(sessions)) }
            .copy(sessionGroups = groups(group("g", "b", collapsed = true)))
        val root = state.model()
        assertEquals("the count shows on its row", 1, root.groupHeaders["g"]!!.count)
        assertNull(root.rows["b"])
        assertEquals("it opens like any group", listOf("b"), state.copy(drawerPath = DrawerPath(listOf("g"))).model().sessionIDs())

        val searching = state.copy(search = "Title b").model()
        assertEquals("search lists the group's matches under its row", listOf(DrawerListItem.GroupHeader("g"), DrawerListItem.Session("b")), searching.sections[DrawerListSection.groups])
        assertEquals("only matches show", listOf("b"), searching.sessionIDs())
        assertFalse("the empty group isn't offered while searching", state.copy(search = "Title a").model().groupHeaders.containsKey("g"))
        assertFalse("nor New Group", DrawerListItem.NewGroup in searching.items)
        assertTrue("the saved flag is untouched", state.copy(search = "Title b").sessionGroups.group(serverID, "g")!!.isCollapsed)
    }

    @Test
    fun archivingAndRestoringKeepsTheAssignment() = homeTest { h ->
        val a = session("a", 40)
        val b = session("b", 30)
        val state = h.state { HomeFeature.State(server, snapshot(listOf(a, b))) }.copy(sessionGroups = groups(group("g", "b")))
        assertEquals(listOf("b"), state.drawerSessions.groups[0].sessions.map { it.id })
        val archived = state.copy(sessions = listOf(a, session("b", 30, archived = true)))
        assertTrue("an archived session leaves its group's list", archived.drawerSessions.groups[0].sessions.isEmpty())
        assertEquals("but not the group", "g", archived.groupID("b"))
        val restored = archived.copy(sessions = listOf(a, b))
        assertEquals(listOf("b"), restored.drawerSessions.groups[0].sessions.map { it.id })
    }

    @Test
    fun aMemberOutsideTheLoadedPagesKeepsItsPlaceUntilItLoads() = homeTest { h ->
        val state = h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40)))) }
            .copy(sessionGroups = groups(group("g", "later")))
        assertTrue(state.drawerSessions.groups[0].sessions.isEmpty())
        assertEquals("g", state.groupID("later"))
        assertEquals(0, state.model().groupHeaders["g"]!!.count)
        val loaded = state.copy(sessions = state.sessions + session("later", 5))
        assertEquals(listOf("later"), loaded.drawerSessions.groups[0].sessions.map { it.id })
        assertEquals(listOf("a"), loaded.drawerSessions.recents.map { it.id })
    }

    @Test
    fun anotherServersHomeShowsNoneOfTheseGroups() = homeTest { h ->
        h.setShared(DrawerSharedKeys.sessionGroups, groups(group("g", "a")))
        val snapshot = snapshot(listOf(session("a", 40)))
        val same = h.state { HomeFeature.State(server, snapshot) }
        assertEquals("a relaunch reads the same file", listOf("a"), same.drawerSessions.groups[0].sessions.map { it.id })
        val other = h.state { HomeFeature.State(otherServer, snapshot) }
        assertTrue(other.drawerSessions.groups.isEmpty())
        assertEquals(listOf("a"), other.drawerSessions.recents.map { it.id })
    }

    // endregion

    // region Actions

    @Test
    fun creatingMovingAndRemovingTouchNoSession() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30))
        val initial = h.state { HomeFeature.State(server, snapshot(sessions)) }
        val store = h.store(initial) { this[UuidClient] = UuidClient.incrementing() }
        store.exhaustive = false
        val id = "grp_00000000-0000-0000-0000-000000000000"
        store.send(Action.CreateGroup("  Work  ", listOf("a")))
        assertEquals(listOf(id), store.state.serverGroups.map { it.id })
        assertEquals("Work", store.state.serverGroups.single().name)
        assertEquals(id, store.state.groupID("a"))
        assertEquals(id, h.shared(DrawerSharedKeys.sessionGroups).groupID(serverID, "a"))

        store.send(Action.MoveSessionsToGroup(listOf("b", "a", "missing", "b"), id))
        assertEquals(listOf("a", "b"), store.state.serverGroups.single().sessionIDs.sorted())
        store.send(Action.MoveSessionsToGroup(listOf("a"), null))
        assertNull(store.state.groupID("a"))
        assertEquals(id, store.state.groupID("b"))
        store.send(Action.RenameGroup(id, "Home"))
        assertEquals("Home", store.state.serverGroups.single().name)
        store.send(Action.RemoveGroup(id))
        assertTrue(store.state.serverGroups.isEmpty())
        assertTrue(h.shared(DrawerSharedKeys.sessionGroups).byServer.isEmpty())

        assertEquals("no session was renamed, moved, archived or dropped", sessions, store.state.sessions)
    }

    @Test
    fun aBlankNameMakesNoGroupAndAnUnknownGroupTakesNoSession() = homeTest { h ->
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40)))) }) { this[UuidClient] = UuidClient.incrementing() }
        store.send(Action.CreateGroup("   "))
        store.send(Action.MoveSessionsToGroup(listOf("a"), "nope"))
        store.send(Action.DrawerGroupOpened("nope"))
        store.send(Action.RemoveGroup("nope"))
        assertTrue(store.state.serverGroups.isEmpty())
    }

    @Test
    fun aDropPutsTheSessionWhereItWasDroppedAndAPinKeepsItsGroup() = homeTest { h ->
        val sessions = listOf("a", "b", "c", "d", "e").mapIndexed { index, id -> session(id, 50L - index * 10) }
        val base = h.state { HomeFeature.State(server, snapshot(sessions)) }
        h.setShared(DrawerSharedKeys.sessionGroups, groups(group("g", "a", "b", "c"), group("h", "d")))
        h.setShared(SharedKeys.pinnedSessionKeys, listOf(base.sessionKey("b"), base.sessionKey("c")))
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) })
        store.exhaustive = false
        fun place(id: String): String {
            val drawer = store.state.drawerSessions
            return when {
                drawer.pinned.any { it.id == id } -> "pinned"
                drawer.recents.any { it.id == id } -> "recents"
                else -> drawer.groups.first { entry -> entry.sessions.any { it.id == id } }.group.id
            }
        }
        assertEquals(listOf("pinned", "pinned", "g", "h", "recents"), listOf("b", "c", "a", "d", "e").map(::place))

        store.send(Action.DrawerRowDroppedInGroup("a", null))
        assertEquals("group → Recents", "recents", place("a"))
        assertNull(store.state.groupID("a"))

        store.send(Action.DrawerRowDropped("b", pinnedIndex = null))
        assertEquals("pinned with a group → Recents: neither pin nor group", "recents", place("b"))
        assertNull(store.state.groupID("b"))

        store.send(Action.DrawerRowDroppedInGroup("c", "h"))
        assertEquals("pinned with a group → another group", "h", place("c"))
        assertFalse(store.state.isPinned("c"))

        store.send(Action.DrawerRowDropped("d", pinnedIndex = 0))
        assertEquals("group → Pinned", "pinned", place("d"))
        assertEquals("the pin keeps its group", "h", store.state.groupID("d"))
        store.send(Action.DrawerRowDroppedInGroup("d", "nope"))
        assertEquals("an unknown group takes nothing, nor unpins", "pinned", place("d"))
        store.send(Action.DrawerRowDroppedInGroup("d", "h"))
        assertEquals("pinned → its own group", "h", place("d"))

        store.send(Action.DrawerRowDroppedInGroup("e", "g"))
        assertEquals("Recents → group", "g", place("e"))
        store.send(Action.DrawerRowDroppedInGroup("e", "h"))
        assertEquals("group → group", "h", place("e"))
        assertEquals(listOf<String>(), store.state.serverGroups.first { it.id == "g" }.sessionIDs)

        store.send(Action.DrawerRowDropped("e", pinnedIndex = 0))
        store.send(Action.DrawerRowDropped("c", pinnedIndex = 0))
        store.send(Action.DrawerRowDropped("e", pinnedIndex = 0))
        assertEquals("a pin reorder keeps both groups", listOf("e", "c"), store.state.pinnedSessions.map { it.id })
        assertEquals("h", store.state.groupID("c"))
        store.send(Action.TogglePin("e"))
        assertEquals("Unpin from the menu takes it back to its group", "h", place("e"))

        val saved = h.shared(DrawerSharedKeys.sessionGroups)
        assertEquals(listOf("c", "d", "e"), saved.group(serverID, "h")?.sessionIDs)
        assertNull(saved.groupID(serverID, "a"))
        assertNull(saved.groupID(serverID, "b"))
        assertEquals("both groups survive", listOf("g", "h"), saved.groups(serverID).map { it.id })
        assertEquals(listOf(base.sessionKey("c")), h.shared(SharedKeys.pinnedSessionKeys))
        assertEquals("no session was renamed, moved, archived or dropped", sessions, store.state.sessions)
    }

    @Test
    fun aLiftedRowSeesAnEmptyRecentsAsAPlaceToDrop() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30))
        val base = h.state { HomeFeature.State(server, snapshot(sessions)) }
        val state = base.copy(sessionGroups = groups(group("g", "a")), pinnedSessionKeys = listOf(base.sessionKey("b")))
        val dropSlot = listOf(DrawerListItem.Header(DrawerSectionHeaderKind.Recents), DrawerListItem.RecentsDrop)

        assertEquals("at rest an empty Recents doesn't show", emptyList<DrawerListItem>(), state.model().sections[DrawerListSection.recents])
        assertEquals("lifting a grouped row", dropSlot, state.model(liftedID = "a").sections[DrawerListSection.recents])
        assertEquals("lifting a pinned row", dropSlot, state.model(liftedID = "b").sections[DrawerListSection.recents])
        assertEquals("while searching too", dropSlot, state.copy(search = "Title").model(liftedID = "a").sections[DrawerListSection.recents])

        val withRecent = state.copy(sessions = sessions + session("c", 20))
        assertEquals(
            "Recents with rows needs no slot",
            listOf(DrawerListItem.Header(DrawerSectionHeaderKind.Recents), DrawerListItem.Session("c")),
            withRecent.model(liftedID = "a").sections[DrawerListSection.recents],
        )
    }

    @Test
    fun aLiftedGroupFoldsItsRowsAndOnlyAnUnsearchedListOffersMoves() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30), session("c", 20))
        val state = h.state { HomeFeature.State(server, snapshot(sessions)) }
            .copy(sessionGroups = groups(group("one", "a", "b"), group("two", "c")))

        val searching = state.copy(search = "Title")
        val folded = searching.model(foldedGroupID = "one")
        assertEquals(
            listOf(DrawerListItem.GroupHeader("one"), DrawerListItem.GroupHeader("two"), DrawerListItem.Session("c")),
            folded.sections[DrawerListSection.groups],
        )
        assertEquals("the lifted row keeps its count", 2, folded.groupHeaders["one"]!!.count)
        assertFalse("its rows don't fall into Recents", "a" in folded.sessionIDs())

        val rest = state.model()
        assertEquals(false to true, rest.groupHeaders["one"]!!.let { it.canMoveUp to it.canMoveDown })
        assertEquals(true to false, rest.groupHeaders["two"]!!.let { it.canMoveUp to it.canMoveDown })
        assertFalse(searching.model().groupHeaders.values.any { it.canMoveUp || it.canMoveDown })

        assertEquals(
            "the list follows the saved order",
            listOf(DrawerListItem.GroupHeader("two"), DrawerListItem.GroupHeader("one"), DrawerListItem.NewGroup),
            state.copy(sessionGroups = state.sessionGroups.moving(serverID, "two", 0)).model().sections[DrawerListSection.groups],
        )
    }

    @Test
    fun moveUpAndDownTradePlacesAndADropSnapsOnlyWhenTheOrderChanges() = homeTest { h ->
        h.setShared(DrawerSharedKeys.sessionGroups, groups(group("one", "a"), group("two"), group("three")))
        val played = LockIsolated<List<Haptic>>(emptyList())
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40)))) }) {
            this[HapticsClient] = HapticsClient(play = { played.append(it) })
        }
        store.exhaustive = false
        fun order() = store.state.serverGroups.map { it.id }

        store.send(Action.MoveGroupUp("one"))
        store.send(Action.MoveGroupDown("three"))
        store.send(Action.MoveGroupUp("missing"))
        assertEquals("the ends stay put", listOf("one", "two", "three"), order())
        assertTrue(played.value.isEmpty())
        store.send(Action.MoveGroupDown("one"))
        assertEquals(listOf("two", "one", "three"), order())
        store.send(Action.MoveGroupUp("one"))
        assertEquals(listOf("one", "two", "three"), order())
        assertEquals(listOf(Haptic.selection, Haptic.selection), played.value)

        store.send(Action.DrawerGroupDropReleased("two", 1))
        store.send(Action.DrawerGroupDropped("two", 1))
        assertEquals("let go in its own slot: no snap, no change", listOf("one", "two", "three"), order())
        assertEquals(2, played.value.size)
        store.send(Action.DrawerGroupDropReleased("three", 0))
        store.send(Action.DrawerGroupDropped("three", 0))
        assertEquals(listOf("three", "one", "two"), order())
        assertEquals(Haptic.snap, played.value.last())
        store.send(Action.DrawerGroupDropped("missing", 0))
        assertEquals(listOf("three", "one", "two"), order())
        assertEquals("a session stays in its group", "one", store.state.groupID("a"))
    }

    @Test
    fun theOrderIsSavedPerServerAndAFileFromBeforeReorderingReadsUnchanged() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30), session("c", 20), session("d", 10))
        val base = h.state { HomeFeature.State(server, snapshot(sessions)) }
        // session-groups.json as the build before reordering wrote it.
        val saved = """{"byServer":{"$serverID":[""" +
            """{"id":"one","name":"One","isCollapsed":true,"sessionIDs":["a"]},""" +
            """{"id":"two","name":"Two","isCollapsed":false,"sessionIDs":["b"]}]}}"""
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val earlier = json.decodeFromString<SessionGroups>(saved)
        assertEquals(
            listOf(SessionGroup("one", "One", isCollapsed = true, sessionIDs = listOf("a")), SessionGroup("two", "Two", sessionIDs = listOf("b"))),
            earlier.groups(serverID),
        )
        h.setShared(DrawerSharedKeys.sessionGroups, earlier)
        h.setShared(SharedKeys.pinnedSessionKeys, listOf(base.sessionKey("c")))
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) })
        store.exhaustive = false

        store.send(Action.DrawerGroupDropped("two", 0))
        val relaunched = h.state { HomeFeature.State(server, snapshot(sessions)) }
        assertEquals("a relaunch reads the new order", listOf("two", "one"), relaunched.serverGroups.map { it.id })
        assertEquals("with names, members and collapse as they were", earlier.groups(serverID).toSet(), relaunched.serverGroups.toSet())
        assertEquals(listOf("c"), relaunched.pinnedSessions.map { it.id })
        assertEquals(h.shared(DrawerSharedKeys.sessionGroups), json.decodeFromString<SessionGroups>(json.encodeToString(h.shared(DrawerSharedKeys.sessionGroups))))
        assertEquals(
            "Pinned stays above the groups and Recents below them",
            listOf(
                DrawerListItem.Header(DrawerSectionHeaderKind.Pinned), DrawerListItem.Session("c"),
                DrawerListItem.GroupHeader("two"), DrawerListItem.GroupHeader("one"), DrawerListItem.NewGroup,
                DrawerListItem.Header(DrawerSectionHeaderKind.Recents), DrawerListItem.Session("d"),
            ),
            relaunched.model().items.filter {
                it is DrawerListItem.Header || it is DrawerListItem.Session || it is DrawerListItem.GroupHeader || it == DrawerListItem.NewGroup
            },
        )
        assertTrue("another server has none of it", h.state { HomeFeature.State(otherServer, snapshot(sessions)) }.serverGroups.isEmpty())

        store.send(Action.DrawerGroupDropped("two", 1))
        assertEquals("the move reverses to the file it started from", earlier, h.shared(DrawerSharedKeys.sessionGroups))
    }

    @Test
    fun deletingASessionForgetsItsGroupMembership() = homeTest { h ->
        val state = h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40), session("b", 30)))) }
            .copy(sessionGroups = groups(group("g", "a", "b")))
        h.setShared(DrawerSharedKeys.sessionGroups, state.sessionGroups)
        val purged = h.state { state.purgingSession("a") }
        assertEquals(listOf("b"), purged.serverGroups.single().sessionIDs)
        assertEquals(listOf("b"), h.shared(DrawerSharedKeys.sessionGroups).group(serverID, "g")?.sessionIDs)
    }

    // endregion

    // region Draft in a group

    private fun homeWithGroup(h: HomeFeatureTestSupport.Harness): HomeFeature.State {
        val project = OpenCodeProject(id = "p", name = "project", directory = "/project")
        val state = h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40)), projects = listOf(project))) }
        h.setShared(DrawerSharedKeys.sessionGroups, groups(group("g")))
        return state.copy(sessionGroups = groups(group("g")))
    }

    @Test
    fun aGroupHeaderStartsADraftThatNamesItsGroupAndGlobalNewSessionDoesNot() = homeTest { h ->
        val store = h.store(homeWithGroup(h))
        store.exhaustive = false
        store.send(Action.NewSessionInGroupTapped("g"))
        val draft = store.state.sessionDetail!!
        assertTrue(draft.isDraft)
        assertEquals(DraftGroupTarget(serverID, visit = draft.visit, groupID = "g"), store.state.draftGroup)
        assertEquals("a fresh draft, not the one that was open", 2, draft.visit)

        store.send(Action.NewSessionTapped)
        assertNull("global New Session is ungrouped", store.state.draftGroup)
        assertTrue(store.state.sessionDetail!!.isDraft)
        assertEquals(3, store.state.sessionDetail!!.visit)
    }

    @Test
    fun anUnknownGroupOpensNoGroupDraft() = homeTest { h ->
        val store = h.store(homeWithGroup(h))
        store.send(Action.NewSessionInGroupTapped("missing"))
        assertNull(store.state.draftGroup)
        assertEquals(1, store.state.sessionDetail!!.visit)
    }

    /** Sends create [created] on the server and the screen then reads its (empty) transcript. */
    private fun DependencyValues.Builder.creating(created: OpenCodeSession, outbox: PromptOutbox) {
        this[PromptOutbox] = outbox
        this[DateClient] = DateClient.constant(Instant.ofEpochSecond(2_000))
        this[UuidClient] = UuidClient.incrementing()
        this[Clock] = ImmediateClock()
        this[OpenCodeClient] = OpenCodeClient.testValue.copy(
            createSession = { _, _, _ -> created },
            getSession = { _, _ -> created },
            message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
            sendPrompt = { _, _, _, _, _, _, _ -> },
            waitForIdle = { _, _ -> throw OpenCodeClientError(message = "Idle wait not under test") },
            transcript = { _, _, _ -> emptyList() },
            sessionFamily = { _, root -> OpenCodeSessionFamily(listOf(root), emptySet()) },
            modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
            familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            pendingInputs = { _, _ -> emptyList() },
            runningShells = { _, _ -> emptyList() },
            viewSession = { _, _, _ -> },
        )
    }

    @Test
    fun theFirstSuccessfulSendOfAGroupDraftPutsItsSessionInThatGroup() = homeTest { h ->
        val created = session("ses_new", 50)
        val store = h.store(homeWithGroup(h)) { creating(created, PromptOutbox(scope = backgroundScope)) }
        store.exhaustive = false
        store.send(Action.NewSessionInGroupTapped("g"))
        store.send(detail(SessionFeature.Action.Binding(BindingAction.Draft("First prompt"))))
        store.send(detail(SessionFeature.Action.SendTapped))
        store.receiveBounded({ it.detailAction == SessionFeature.Action.SessionCreated(created) }, "sessionCreated")
        assertEquals(created.id, store.state.sessionDetail?.session?.id)
        assertEquals("g", store.state.groupID(created.id))
        assertEquals("g", h.shared(DrawerSharedKeys.sessionGroups).groupID(serverID, created.id))
        assertNull("the target is spent", store.state.draftGroup)
        assertEquals(listOf("ses_new"), store.state.drawerSessions.groups.single().sessions.map { it.id })
        assertTrue(store.state.drawerSessions.recents.none { it.id == created.id })
    }

    @Test
    fun aGlobalNewSessionSentAfterAGroupDraftStaysUngrouped() = homeTest { h ->
        val created = session("ses_global", 50)
        val store = h.store(homeWithGroup(h)) { creating(created, PromptOutbox(scope = backgroundScope)) }
        store.exhaustive = false
        store.send(Action.NewSessionInGroupTapped("g"))
        store.send(Action.NewSessionTapped)
        store.send(detail(SessionFeature.Action.Binding(BindingAction.Draft("Plain prompt"))))
        store.send(detail(SessionFeature.Action.SendTapped))
        store.receiveBounded({ it.detailAction == SessionFeature.Action.SessionCreated(created) }, "sessionCreated")
        assertNull(store.state.groupID(created.id))
        assertEquals(listOf("ses_global"), store.state.drawerSessions.recents.filter { it.id == created.id }.map { it.id })
    }

    @Test
    fun aDraftThatMovedOnNeverClaimsALaterCreate() = homeTest { h ->
        val created = session("ses_late", 50)
        val draft = homeWithGroup(h).let { it.copy(draftGroup = DraftGroupTarget(serverID, visit = 2, groupID = "g")) }
        val becameSession = draft.sessionDetail!!.copy(
            visit = 5, draftBecameSessionID = created.id, session = created, familySessions = listOf(created), familyRootID = created.id,
        )
        val resolved = h.state { draft.copy(sessionDetail = becameSession).resolvingDraftGroup() }
        assertNull("a screen of another visit is not the draft", resolved.groupID(created.id))
        assertNull(resolved.draftGroup)
    }

    @Test
    fun aTargetFromAnotherServerOrAGoneGroupAssignsNothing() = homeTest { h ->
        val created = session("ses_new", 50)
        val home = homeWithGroup(h)
        val visit = home.sessionDetail!!.visit
        val became = home.sessionDetail!!.copy(draftBecameSessionID = created.id, session = created, familySessions = listOf(created), familyRootID = created.id)
        val wrongServer = h.state {
            home.copy(draftGroup = DraftGroupTarget(otherServer.persistenceID, visit, "g"), sessionDetail = became).resolvingDraftGroup()
        }
        assertNull(wrongServer.groupID(created.id))
        assertNull(wrongServer.draftGroup)
        val goneGroup = h.state {
            home.copy(draftGroup = DraftGroupTarget(serverID, visit, "removed"), sessionDetail = became).resolvingDraftGroup()
        }
        assertNull(goneGroup.groupID(created.id))
        assertNull(goneGroup.draftGroup)
        val matching = h.state {
            home.copy(draftGroup = DraftGroupTarget(serverID, visit, "g"), sessionDetail = became).resolvingDraftGroup()
        }
        assertEquals("g", matching.groupID(created.id))
    }

    @Test
    fun removingTheGroupDropsItsDraftTarget() = homeTest { h ->
        val store = h.store(homeWithGroup(h))
        store.exhaustive = false
        store.send(Action.NewSessionInGroupTapped("g"))
        store.send(Action.RemoveGroup("g"))
        assertNull(store.state.draftGroup)
        assertTrue(store.state.serverGroups.isEmpty())
    }

    @Test
    fun aQueuedFirstSendKeepsItsGroupAcrossSwitchingAndShowsUnsentInIt() = homeTest { h ->
        val pending = PendingPrompt(messageID = "msg_1", text = "Hello", attachments = emptyList(), requestEpoch = 1)
        h.composerPersistence.recordOutbox(serverID, null, "msg_1", "/project", "ses_queued", null, pending, true, "$serverID|draft:msg_1")
        val home = homeWithGroup(h)
        val visit = home.sessionDetail!!.visit
        val sending = home.copy(
            draftGroup = DraftGroupTarget(serverID, visit, "g"),
            sessionDetail = home.sessionDetail!!.copy(outboxDraftID = "msg_1"),
        )
        val queued = h.state { sending.resolvingDraftGroup() }
        assertEquals("the session the lane will create is already in the group", "g", queued.groupID("ses_queued"))
        assertEquals("msg_1", queued.draftGroup?.draftID)
        assertEquals("ses_queued", queued.draftGroup?.sessionID)

        val switched = h.state { queued.copy(sessionDetail = queued.sessionDetail!!.copy(visit = visit + 1, outboxDraftID = null)).resolvingDraftGroup() }
        assertNull("nothing further to wait for", switched.draftGroup)
        assertEquals("g", switched.groupID("ses_queued"))

        val listed = switched.copy(unsentDrafts = h.composerPersistence.unsentDrafts(serverID))
        assertEquals("the group counts it", 1, listed.model().groupHeaders["g"]?.count)
        assertNull("not in Recents", listed.model().rows["ses_queued"])
        val model = listed.copy(drawerPath = DrawerPath(listOf("g"))).model()
        assertEquals("the unsent row shows in its group", listOf("ses_queued"), model.sessionIDs())
        assertTrue(model.rows["ses_queued"]?.inGroup == true)
        assertNotNull(model.rows["ses_queued"]?.unsent)
    }

    // endregion

    // region Shared groups

    /** The session-groups plugin as the contract describes it: revision checks, operation receipts, one import. */
    private class FakeGroupsPlugin(var sessions: Set<String> = emptySet()) {
        var revision = 0L
        var groups = listOf<OpenCodeSessionGroup>()
        var initialized = false
        val calls = mutableListOf<String>()
        val sent = mutableListOf<Triple<OpenCodeSessionGroupsWrite, Long, String?>>()
        private val receipts = HashMap<String, Pair<OpenCodeSessionGroupsWrite, Long>>()
        private var ids = 0
        var capabilities: OpenCodeSessionGroupsCapabilities? = OpenCodeSessionGroupsCapabilities(
            2, setOf("create", "assign", "reorder", "rename", "remove", "import"), operationIDs = true,
        )
        /** Thrown (once) instead of answering the next write. */
        var failNextWrite: OpenCodeClientError? = null
        var failReads: OpenCodeClientError? = null

        fun snapshot() = OpenCodeSessionGroupsSnapshot(revision, groups)

        /** Another client's write. */
        fun elsewhere(change: (List<OpenCodeSessionGroup>) -> List<OpenCodeSessionGroup>) {
            groups = change(groups)
            revision += 1
            initialized = true
        }

        fun rpc(type: String, revision: Long? = null) = OpenCodeClientError("rpc", statusCode = 400, rpcError = OpenCodeRpcError(type, revision))

        fun write(write: OpenCodeSessionGroupsWrite, expected: Long, operationID: String?): OpenCodeSessionGroupsSnapshot {
            calls += write.method
            sent += Triple(write, expected, operationID)
            failNextWrite?.let { failNextWrite = null; throw it }
            if (operationID != null) {
                receipts[operationID]?.let { (w, e) -> if (w == write && e == expected) return snapshot() else throw rpc(OpenCodeRpcError.OPERATION_CONFLICT) }
            }
            if (write is OpenCodeSessionGroupsWrite.Import && initialized) throw rpc(OpenCodeRpcError.IMPORT_REFUSED, revision)
            if (expected != revision) throw rpc(OpenCodeRpcError.REVISION_CONFLICT, revision)
            groups = when (write) {
                is OpenCodeSessionGroupsWrite.Create -> groups + OpenCodeSessionGroup("srv_${++ids}", write.name.trim())
                is OpenCodeSessionGroupsWrite.Assign -> {
                    if (write.sessionID !in sessions) throw rpc(OpenCodeRpcError.SESSION_NOT_FOUND)
                    if (write.groupID != null && groups.none { it.id == write.groupID }) throw rpc(OpenCodeRpcError.GROUP_NOT_FOUND)
                    groups.map { group ->
                        val kept = group.sessionIDs - write.sessionID
                        if (group.id == write.groupID) group.copy(sessionIDs = if (write.sessionID in group.sessionIDs) group.sessionIDs else kept + write.sessionID)
                        else group.copy(sessionIDs = kept)
                    }
                }
                is OpenCodeSessionGroupsWrite.Reorder -> write.groupIDs.map { id -> groups.first { it.id == id } }
                is OpenCodeSessionGroupsWrite.Rename -> groups.map { if (it.id == write.groupID) it.copy(name = write.name) else it }
                is OpenCodeSessionGroupsWrite.Remove -> {
                    if (groups.none { it.id == write.groupID }) throw rpc(OpenCodeRpcError.GROUP_NOT_FOUND)
                    groups.filterNot { it.id == write.groupID }
                }
                is OpenCodeSessionGroupsWrite.Import -> {
                    if (write.groups.any { group -> group.sessionIDs.any { it !in sessions } }) throw rpc(OpenCodeRpcError.SESSION_NOT_FOUND)
                    write.groups
                }
            }
            revision += 1
            initialized = true
            if (operationID != null) receipts[operationID] = write to expected
            return snapshot()
        }

        fun client(): OpenCodeClient = OpenCodeClient.testValue.copy(
            sessionGroupsCapabilities = {
                calls += "capabilities"
                capabilities ?: throw rpc(OpenCodeRpcError.METHOD_NOT_FOUND)
            },
            sessionGroups = {
                calls += "list"
                failReads?.let { throw it }
                snapshot()
            },
            writeSessionGroups = { _, write, expected, operationID -> write(write, expected, operationID) },
            getSession = { _, id ->
                if (id !in sessions) throw OpenCodeClientError("gone", statusCode = 404)
                OpenCodeSession(id = id, serverTitle = id, projectID = "p", directory = "/project", updatedAt = Instant.EPOCH)
            },
        )
    }

    private fun HomeFeature.State.connected(): HomeFeature.State =
        copy(connection = connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.connected))

    private fun sharedGroups(revision: Long, vararg groups: SessionGroup, collapsed: Set<String> = emptySet()) =
        SharedSessionGroups(mapOf(serverID to SharedGroupsServer(revision, groups.toList(), collapsed)))

    private suspend fun TestStore<HomeFeature.State, Action>.settled() {
        repeat(20) { skipReceivedActions() }
    }

    private suspend fun TestStore<HomeFeature.State, Action>.receiveWrite() =
        receive({ it is Action.SharedGroupsWritten }, "a shared groups write's answer")

    private suspend fun TestStore<HomeFeature.State, Action>.receiveRead() =
        receive({ it is Action.SharedGroupsRead }, "a shared groups read")

    @Test
    fun aServerWithoutThePluginKeepsItsGroupsOnThePhoneAndOffersNoSharing() = homeTest { h ->
        h.setShared(DrawerSharedKeys.sessionGroups, groups(group("g", "a")))
        val requests = mutableListOf<String>()
        val missing = OpenCodeClientError("missing", statusCode = 404)
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40), session("b", 30)))) }.connected()) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                sessionGroupsCapabilities = { requests += "capabilities"; throw missing },
                sessionGroups = { requests += "list"; throw missing },
            )
        }
        store.exhaustive = false
        store.send(Action.GroupSharingDiscoveryRequested)
        store.receiveRead()
        assertEquals(GroupSync.Support.Unavailable, store.state.groupSync.support)
        assertNull("the drawer looks as it did", store.state.groupSharingStatus)
        assertNull(store.state.model().groupSharing)

        store.send(Action.GroupSharingDiscoveryRequested)
        store.send(Action.MoveSessionsToGroup(listOf("b"), "g"))
        assertEquals("discovery runs once, and edits stay on the phone", listOf("capabilities", "list"), requests)
        assertEquals(listOf("a", "b"), h.shared(DrawerSharedKeys.sessionGroups).group(serverID, "g")?.sessionIDs)
        assertTrue(h.shared(DrawerSharedKeys.sharedSessionGroups).byServer.isEmpty())
    }

    @Test
    fun sharingAFreshStoreImportsOnlyThisServersGroupsOnceAndKeepsThePhonesCopy() = homeTest { h ->
        val local = SessionGroups(
            mapOf(
                serverID to listOf(group("g1", "a", "b", collapsed = true), group("g2", "c")),
                otherServer.persistenceID to listOf(group("other", "z")),
            ),
        )
        h.setShared(DrawerSharedKeys.sessionGroups, local)
        val plugin = FakeGroupsPlugin(sessions = setOf("a", "b", "c", "old"))
        val sessions = listOf(session("a", 40), session("b", 30), session("c", 20))
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) }.connected()) {
            this[OpenCodeClient] = plugin.client()
            this[UuidClient] = UuidClient.incrementing()
        }
        store.exhaustive = false
        store.send(Action.GroupSharingDiscoveryRequested)
        store.receiveRead()
        assertEquals(GroupSharingStatus(isShared = false, isOffline = false, canImport = false), store.state.groupSharingStatus)

        store.send(Action.ShareGroupsTapped)
        store.receiveRead()
        val prompt = store.state.groupSync.prompt as GroupSharingPrompt.Share
        assertTrue("a never-written store with phone groups offers the import", prompt.canImport)
        assertEquals(2, prompt.localGroupCount)
        assertTrue("nothing is written before the answer", plugin.sent.isEmpty())

        store.send(Action.ShareGroupsConfirmed(importsLocal = true))
        store.receive({ it is Action.SharedGroupsImportPrepared })
        assertEquals(listOf(true, false), store.state.serverGroups.map { it.isCollapsed })
        store.receiveWrite()
        val (write, expected, operationID) = plugin.sent.single()
        assertEquals(0L, expected)
        assertNotNull(operationID)
        assertEquals(
            "only this server's groups, with IDs, names and ordered sessions",
            OpenCodeSessionGroupsWrite.Import(listOf(OpenCodeSessionGroup("g1", "Group g1", listOf("a", "b")), OpenCodeSessionGroup("g2", "Group g2", listOf("c")))),
            write,
        )
        assertEquals(1L, store.state.sharedGroups?.revision)
        assertEquals(listOf("g1", "g2"), store.state.serverGroups.map { it.id })
        assertNull(store.state.sharedGroups?.pendingImport)
        assertEquals("the phone's own groups are kept untouched", local, h.shared(DrawerSharedKeys.sessionGroups))
        assertFalse("an initialized store offers no import", store.state.groupSharingStatus!!.canImport)
        assertEquals("the shared drawer keeps the phone's collapse state", listOf(true, false), store.state.serverGroups.map { it.isCollapsed })
        assertEquals(setOf("g1"), h.shared(DrawerSharedKeys.sharedSessionGroups)[serverID]?.collapsedIDs)

        store.send(Action.StopSharingGroupsTapped)
        store.send(Action.StopSharingGroupsConfirmed)
        assertNull(store.state.sharedGroups)
        assertEquals("stopping returns the phone's groups", local.groups(serverID), store.state.serverGroups)
        assertEquals(1, plugin.calls.count { it == "import" })
    }

    @Test
    fun sharingWhenBothHaveGroupsNeitherMergesNorOverwrites() = homeTest { h ->
        val local = groups(group("mine", "a"))
        h.setShared(DrawerSharedKeys.sessionGroups, local)
        val plugin = FakeGroupsPlugin(sessions = setOf("a", "b"))
        plugin.elsewhere { listOf(OpenCodeSessionGroup("theirs", "Theirs", listOf("b"))) }
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40), session("b", 30)))) }.connected()) {
            this[OpenCodeClient] = plugin.client()
        }
        store.exhaustive = false
        store.send(Action.ShareGroupsTapped)
        store.receiveRead()
        val prompt = store.state.groupSync.prompt as GroupSharingPrompt.Share
        assertFalse(prompt.canImport)
        assertEquals(1, prompt.serverGroupCount)
        assertEquals("no switch until the answer", listOf("mine"), store.state.serverGroups.map { it.id })

        store.send(Action.ShareGroupsConfirmed(importsLocal = true))
        store.settled()
        assertEquals(listOf("theirs"), store.state.serverGroups.map { it.id })
        assertTrue("nothing written", plugin.sent.isEmpty())
        assertEquals(local, h.shared(DrawerSharedKeys.sessionGroups))
        store.send(Action.ImportLocalGroupsTapped)
        assertNull("no import into an initialized store", store.state.groupSync.prompt)
    }

    @Test
    fun aSharedGroupIsCreatedOnTheServerAndItsSessionsAssignedUnderTheServersID() = homeTest { h ->
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(0))
        val plugin = FakeGroupsPlugin(sessions = setOf("a", "b"))
        val sessions = listOf(session("a", 40), session("b", 30))
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) }.connected()) {
            this[OpenCodeClient] = plugin.client()
            this[UuidClient] = UuidClient.incrementing()
        }
        store.exhaustive = false
        store.send(Action.CreateGroup("  Work  ", listOf("a", "b")))
        assertEquals("it shows at once", listOf("Work"), store.state.serverGroups.map { it.name })
        assertEquals(listOf("a", "b"), store.state.serverGroups.single().sessionIDs)
        store.receiveRead()
        repeat(3) { store.receiveWrite() }

        assertEquals(listOf("capabilities", "list", "create", "assign", "assign"), plugin.calls)
        assertEquals(listOf(0L, 1L, 2L), plugin.sent.map { it.second })
        assertEquals("every logical write has its own ID", 3, plugin.sent.map { it.third }.toSet().size)
        assertEquals(OpenCodeSessionGroupsWrite.Assign("srv_1", "a"), plugin.sent[1].first)
        assertEquals(listOf(SessionGroup("srv_1", "Work", sessionIDs = listOf("a", "b"))), store.state.serverGroups)
        assertEquals(3L, store.state.sharedGroups?.revision)
        assertTrue(store.state.groupSync.queue.isEmpty())
        assertTrue("the phone's own store isn't written", h.shared(DrawerSharedKeys.sessionGroups).byServer.isEmpty())
        assertEquals("no session was renamed, moved or archived", sessions, store.state.sessions)
    }

    @Test
    fun aStaleWriteIsRejectedVisiblyRefreshedAndTheNextEditStillGoes() = homeTest { h ->
        val g = SessionGroup("g", "G")
        val other = SessionGroup("o", "Other")
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(1, g, other, collapsed = setOf("o")))
        val plugin = FakeGroupsPlugin(sessions = setOf("a", "b"))
        plugin.groups = listOf(OpenCodeSessionGroup("g", "G"), OpenCodeSessionGroup("o", "Other"))
        plugin.revision = 1
        plugin.initialized = true
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40), session("b", 30)))) }.connected().copy(isDrawerOpen = true)) {
            this[OpenCodeClient] = plugin.client()
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = TestClock()
        }
        store.exhaustive = false
        store.send(Action.SharedGroupsPollTick)
        store.receiveRead()
        // An agent renames a group between this phone's read and its write.
        plugin.elsewhere { list -> list.map { if (it.id == "g") it.copy(name = "Agent") else it } }

        store.send(Action.RenameGroup("g", "Mine"))
        assertEquals("it shows at once", "Mine", store.state.serverGroups.first().name)
        store.send(Action.MoveSessionsToGroup(listOf("a"), "o"))
        store.settled()
        assertNotNull("the rejection shows", store.state.groupsError)
        assertEquals(DrawerFailure.Action.groups, store.state.groupsError?.action)
        assertEquals(localized(R.string.drawer_groups_error_conflict), store.state.groupsError?.detail)

        assertEquals("the other writer's name stands", "Agent", store.state.serverGroups.first().name)
        assertEquals("the unrelated edit went through after the refresh", listOf("a"), store.state.serverGroups.first { it.id == "o" }.sessionIDs)
        assertEquals(listOf("rename", "assign"), plugin.sent.map { it.first.method })
        assertEquals(listOf(1L, 2L), plugin.sent.map { it.second })
        assertTrue("collapse is kept", store.state.serverGroups.first { it.id == "o" }.isCollapsed)
        assertEquals(listOf("capabilities", "list", "rename", "list", "assign"), plugin.calls)
        store.skipInFlightEffects()
    }

    @Test
    fun aSharedServerThatCantBeReachedKeepsItsLastGroupsAndWritesNothingOnThePhone() = homeTest { h ->
        val g = SessionGroup("g", "G", sessionIDs = listOf("a"))
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(4, g))
        h.setShared(DrawerSharedKeys.sessionGroups, groups(group("mine")))
        val plugin = FakeGroupsPlugin(sessions = setOf("a", "b"))
        plugin.failReads = OpenCodeClientError.unreachable(ai.opencode.server.retry.TransportFailure.NotSent)
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40), session("b", 30)))) }.connected()) {
            this[OpenCodeClient] = plugin.client()
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = TestClock()
        }
        store.exhaustive = false
        assertEquals("shared and not yet known offline", GroupSharingStatus(isShared = true, isOffline = false, canImport = false), store.state.groupSharingStatus)
        store.send(Action.MoveSessionsToGroup(listOf("b"), "g"))
        assertEquals(listOf("a", "b"), store.state.serverGroups.single().sessionIDs)
        store.settled()

        assertTrue(store.state.groupSharingStatus!!.isOffline)
        assertEquals("the edit is dropped, the last snapshot stays", listOf(g), store.state.serverGroups)
        assertNotNull(store.state.groupsError)
        assertTrue("nothing reached the server", plugin.sent.isEmpty())
        assertEquals("the phone's own groups are untouched", groups(group("mine")), h.shared(DrawerSharedKeys.sessionGroups))
        assertEquals(4L, h.shared(DrawerSharedKeys.sharedSessionGroups)[serverID]?.revision)
    }

    @Test
    fun answersForAnotherServerOrAnOlderRevisionAreIgnored() = homeTest { h ->
        val g = SessionGroup("g", "G")
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(5, g))
        fun HomeFeature.State.reading() = copy(groupSync = groupSync.copy(isReading = true))
        val store = h.store(h.state { HomeFeature.State(server, snapshot(emptyList())) }.reading())
        store.exhaustive = false
        val capabilities = GroupSync.Support.Available(OpenCodeSessionGroupsCapabilities.original)
        val stale = OpenCodeSessionGroupsSnapshot(3, listOf(OpenCodeSessionGroup("x", "Old")))
        store.send(Action.SharedGroupsRead(otherServer.persistenceID, SharedGroupsReadResult(capabilities, stale.copy(revision = 9))))
        assertTrue("another server's answer leaves this read waiting", store.state.groupSync.isReading)
        store.send(Action.SharedGroupsRead(serverID, SharedGroupsReadResult(capabilities, stale)))
        assertEquals(listOf(g), store.state.serverGroups)
        assertEquals(5L, store.state.sharedGroups?.revision)
        val next = h.store(store.state.reading())
        next.exhaustive = false
        next.send(Action.SharedGroupsRead(serverID, SharedGroupsReadResult(capabilities, stale.copy(revision = 6))))
        assertEquals(listOf("x"), next.state.serverGroups.map { it.id })
        assertFalse("the original contract offers no reorder", next.state.canEditGroups("reorder"))
        assertTrue(next.state.canEditGroups("assign"))
    }

    @Test
    fun anImportWhoseAnswerWasLostIsResentUnderTheSameOperation() = homeTest { h ->
        h.setShared(DrawerSharedKeys.sessionGroups, groups(group("g1", "a")))
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(0))
        val plugin = FakeGroupsPlugin(sessions = setOf("a"))
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40)))) }.connected().copy(isDrawerOpen = true)) {
            this[OpenCodeClient] = plugin.client()
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = TestClock()
        }
        store.exhaustive = false
        store.send(Action.SharedGroupsPollTick)
        store.receiveRead()
        plugin.failNextWrite = OpenCodeClientError.unreachable(ai.opencode.server.retry.TransportFailure.TimedOut)
        store.send(Action.ImportLocalGroupsTapped)
        store.send(Action.ImportLocalGroupsConfirmed)
        store.settled()
        val pending = store.state.sharedGroups?.pendingImport
        assertNotNull("the unanswered import is kept", pending)
        assertNotNull(store.state.groupsError)

        store.send(Action.ImportLocalGroupsTapped)
        store.send(Action.ImportLocalGroupsConfirmed)
        store.settled()
        assertEquals(2, plugin.sent.size)
        assertEquals("the same write under the same ID", plugin.sent[0], plugin.sent[1])
        assertEquals(pending?.operationID, plugin.sent[1].third)
        assertEquals(listOf("g1"), store.state.serverGroups.map { it.id })
        assertNull(store.state.sharedGroups?.pendingImport)
    }

    @Test
    fun droppingAPinnedRowOnASharedGroupUnpinsItHereAndAssignsItThere() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30))
        val base = h.state { HomeFeature.State(server, snapshot(sessions)) }
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(2, SessionGroup("g", "G")))
        h.setShared(SharedKeys.pinnedSessionKeys, listOf(base.sessionKey("a")))
        val plugin = FakeGroupsPlugin(sessions = setOf("a", "b"))
        plugin.groups = listOf(OpenCodeSessionGroup("g", "G"))
        plugin.revision = 2
        plugin.initialized = true
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) }.connected()) {
            this[OpenCodeClient] = plugin.client()
            this[UuidClient] = UuidClient.incrementing()
        }
        store.exhaustive = false
        store.send(Action.DrawerRowDroppedInGroup("a", "g"))
        assertFalse(store.state.isPinned("a"))
        store.receiveRead()
        store.receiveWrite()
        assertEquals(listOf(OpenCodeSessionGroupsWrite.Assign("g", "a")), plugin.sent.map { it.first })
        assertEquals("g", store.state.groupID("a"))
        assertEquals(sessions, store.state.sessions)
    }

    @Test
    fun answersSharingNoLongerWaitsForChangeNothing() = homeTest { h ->
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(2, SessionGroup("g", "G")))
        val write = GroupWrite("op-now", GroupEdit.Rename("g", "Now"))
        val base = h.state { HomeFeature.State(server, snapshot(emptyList())) }
        val writing = base.copy(groupSync = GroupSync(support = GroupSync.Support.Available(OpenCodeSessionGroupsCapabilities.original), queue = listOf(write), isWriting = true))
        val store = h.store(writing)
        store.exhaustive = false
        val later = OpenCodeSessionGroupsSnapshot(9, listOf(OpenCodeSessionGroup("x", "Stale")))
        store.send(Action.SharedGroupsWritten(serverID, "op-before-stop", snapshot = later))
        assertEquals("the write in flight stays the only one", writing.groupSync, store.state.groupSync)
        assertEquals(2L, store.state.sharedGroups?.revision)
        store.send(Action.SharedGroupsRead(serverID, SharedGroupsReadResult(GroupSync.Support.Unavailable, later)))
        assertEquals("a read no one waits for is dropped", writing.groupSync, store.state.groupSync)
        assertEquals(listOf("g"), store.state.serverGroups.map { it.id })

        val other = store.state.withServer(otherServer)
        assertEquals("another server starts with nothing queued", GroupSync(), other.groupSync)
        assertEquals(writing.groupSync, store.state.withServer(server.copy(name = "Renamed")).groupSync)
    }

    @Test
    fun anImportKeepsThePhonesCollapseEvenWhenAReadLandedFirst() = homeTest { h ->
        h.setShared(DrawerSharedKeys.sessionGroups, groups(group("g1", "a", collapsed = true), group("g2")))
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(0))
        val pending = PendingImport("op-import", listOf(SessionGroup("g1", "Group g1", sessionIDs = listOf("a")), SessionGroup("g2", "Group g2")))
        val base = h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40)))) }
        val store = h.store(
            base.copy(
                groupSync = GroupSync(
                    support = GroupSync.Support.Available(OpenCodeSessionGroupsCapabilities(2, setOf("import"), true)),
                    queue = listOf(GroupWrite("op-import", GroupEdit.Import(pending))),
                    isWriting = true,
                ),
            ),
        )
        store.exhaustive = false
        val imported = OpenCodeSessionGroupsSnapshot(1, listOf(OpenCodeSessionGroup("g1", "Group g1", listOf("a")), OpenCodeSessionGroup("g2", "Group g2")))
        store.send(Action.SharedGroupsWritten(serverID, "op-import", snapshot = imported))
        assertEquals(setOf("g1"), store.state.sharedGroups?.collapsedIDs)
        assertEquals(listOf(true, false), store.state.serverGroups.map { it.isCollapsed })
        assertNull(store.state.sharedGroups?.pendingImport)
    }

    @Test
    fun droppingASharedGroupsPinnedRowOnRecentsUngroupsItThere() = homeTest { h ->
        val sessions = listOf(session("a", 40))
        val base = h.state { HomeFeature.State(server, snapshot(sessions)) }
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(2, SessionGroup("g", "G", sessionIDs = listOf("a"))))
        h.setShared(SharedKeys.pinnedSessionKeys, listOf(base.sessionKey("a")))
        val plugin = FakeGroupsPlugin(sessions = setOf("a"))
        plugin.groups = listOf(OpenCodeSessionGroup("g", "G", listOf("a")))
        plugin.revision = 2
        plugin.initialized = true
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) }.connected()) {
            this[OpenCodeClient] = plugin.client()
            this[UuidClient] = UuidClient.incrementing()
        }
        store.exhaustive = false
        store.send(Action.DrawerRowDropped("a", pinnedIndex = null))
        assertFalse(store.state.isPinned("a"))
        assertNull("it shows in Recents at once", store.state.groupID("a"))
        store.settled()
        assertEquals(listOf(OpenCodeSessionGroupsWrite.Assign(null, "a")), plugin.sent.map { it.first })
        assertNull(store.state.groupID("a"))
    }

    @Test
    fun theDrawerReadsSharedGroupsWhileItIsOpenAndStopsWhenItCloses() = homeTest { h ->
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(1, SessionGroup("g", "G")))
        val plugin = FakeGroupsPlugin()
        plugin.groups = listOf(OpenCodeSessionGroup("g", "G"))
        plugin.revision = 1
        val clock = TestClock()
        val store = h.store(h.state { HomeFeature.State(server, snapshot(emptyList())) }.connected()) {
            this[OpenCodeClient] = plugin.client()
            this[Clock] = clock
        }
        store.exhaustive = false
        store.send(Action.SessionDrawerArrived(isOpen = true))
        store.receiveRead()
        plugin.elsewhere { it + OpenCodeSessionGroup("new", "New") }
        clock.advance(30.seconds)
        store.receive(Action.SharedGroupsPollTick)
        store.receiveRead()
        assertEquals("another client's group shows while the drawer stays open", listOf("g", "new"), store.state.serverGroups.map { it.id })

        store.send(Action.SessionDrawerArrived(isOpen = false))
        val reads = plugin.calls.count { it == "list" }
        clock.advance(120.seconds)
        store.settled()
        assertEquals("a closed drawer reads nothing", reads, plugin.calls.count { it == "list" })
    }

    @Test
    fun readsWaitOutAPluginThatIsStillLoadingAFewTimes() = runTest {
        var attempts = 0
        val loading = OpenCodeClientError("loading", statusCode = 400, rpcError = OpenCodeRpcError(OpenCodeRpcError.UNAVAILABLE))
        val client = OpenCodeClient.testValue.copy(
            sessionGroupsCapabilities = { attempts += 1; if (attempts < 3) throw loading else OpenCodeSessionGroupsCapabilities.original },
            sessionGroups = { OpenCodeSessionGroupsSnapshot(0) },
        )
        val read = readSharedGroups(client, ImmediateClock(), server, GroupSync.Support.Unknown)
        assertEquals(3, attempts)
        assertEquals(OpenCodeSessionGroupsSnapshot(0), read.snapshot)

        attempts = 0
        val stuck = OpenCodeClient.testValue.copy(sessionGroupsCapabilities = { attempts += 1; throw loading }, sessionGroups = { throw loading })
        val failed = readSharedGroups(stuck, ImmediateClock(), server, GroupSync.Support.Unknown)
        assertEquals("bounded", 3, attempts)
        assertEquals(GroupSync.Support.Unknown, failed.support)
        assertNotNull(failed.error)
    }

    // endregion

    // region Navigation

    @Test
    fun aPathOpensGoesBackOneLevelAndEndsAtAGoneGroup() {
        val root = DrawerPath()
        assertTrue(root.isRoot)
        assertEquals(root, root.back())
        val inside = root.opening("g")
        assertEquals("g", inside.groupID)
        assertEquals("opening the open group again changes nothing", inside, inside.opening("g"))
        val deeper = inside.opening("h")
        assertEquals(listOf("g", "h"), deeper.groupIDs)
        assertEquals(inside, deeper.back())
        assertEquals(root, deeper.back().back())
        assertEquals("a gone level ends the path there", inside, deeper.resolved { it == "g" })
        assertEquals(root, deeper.resolved { it == "h" })
        assertEquals(DrawerPath(listOf("srv", "h")), deeper.renamingGroup("g", "srv"))
        assertEquals(deeper, deeper.renamingGroup("other", "srv"))
    }

    @Test
    fun openingAGroupShowsOnlyItsSessionsAndBackReturnsToTheRoot() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30), session("c", 20), session("d", 10))
        val base = h.state { HomeFeature.State(server, snapshot(sessions)) }
        val saved = groups(group("g", "c", "b"), group("h", "d"))
        h.setShared(DrawerSharedKeys.sessionGroups, saved)
        h.setShared(SharedKeys.pinnedSessionKeys, listOf(base.sessionKey("a")))
        val played = LockIsolated<List<Haptic>>(emptyList())
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) }) {
            this[HapticsClient] = HapticsClient(play = { played.append(it) })
        }
        store.exhaustive = false

        store.send(Action.DrawerGroupOpened("g"))
        assertEquals(DrawerPath(listOf("g")), store.state.drawerPath)
        assertEquals("g", store.state.shownDrawerGroup?.id)
        val inside = store.state.model()
        assertEquals(
            "its title, then only its sessions, newest first: no shortcuts, Pinned, other groups or Recents",
            listOf(DrawerListItem.GroupTitle("g"), DrawerListItem.Session("b"), DrawerListItem.Session("c")),
            inside.items,
        )
        assertEquals(DrawerListGroupHeader("g", "Group g", count = 2), inside.tray)
        assertTrue("nothing else to drag between", inside.groupHeaders.isEmpty())

        store.send(Action.DrawerGroupBackTapped)
        assertTrue(store.state.drawerPath.isRoot)
        val root = store.state.model()
        assertNull(root.tray)
        assertEquals("Pinned keeps its row; the groups hold the rest", listOf("a"), root.sessionIDs())
        assertEquals(listOf("g", "h"), root.groupHeaders.keys.toList())
        assertTrue(DrawerListItem.CatchUp in root.items)
        store.send(Action.DrawerGroupBackTapped)
        assertTrue("back at the root stays there", store.state.drawerPath.isRoot)
        store.send(Action.DrawerGroupOpened("missing"))
        assertTrue("an unknown group opens nothing", store.state.drawerPath.isRoot)

        assertTrue("going in and out is quiet", played.value.isEmpty())
        assertEquals("and never writes the groups or their order", saved, h.shared(DrawerSharedKeys.sessionGroups))
    }

    @Test
    fun searchingInsideAGroupListsEveryMatchAndClearingItReturnsToTheGroup() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30))
        val state = h.state { HomeFeature.State(server, snapshot(sessions)) }
            .copy(sessionGroups = groups(group("g", "b")), drawerPath = DrawerPath(listOf("g")))
        val searching = state.copy(search = "Title")
        assertNull(searching.shownDrawerGroup)
        assertNull("search leaves the group's page", searching.model().tray)
        assertEquals(setOf("a", "b"), searching.model().sessionIDs().toSet())
        assertEquals("the place is kept for after", "g", searching.openGroup?.id)
        assertEquals("g", searching.copy(search = "").model().tray?.id)
    }

    @Test
    fun theDrawersNewSessionStartsInTheOpenGroupWithoutChangingTheFolder() = homeTest { h ->
        val store = h.store(homeWithGroup(h))
        store.exhaustive = false
        store.send(Action.DrawerNewSessionTapped)
        assertNull("at the root it's a plain draft", store.state.draftGroup)
        val plain = store.state.sessionDetail!!
        assertTrue(plain.isDraft)

        store.send(Action.DrawerGroupOpened("g"))
        store.send(Action.DrawerNewSessionTapped)
        val draft = store.state.sessionDetail!!
        assertTrue(draft.isDraft)
        assertEquals(DraftGroupTarget(serverID, visit = draft.visit, groupID = "g"), store.state.draftGroup)
        assertEquals("the same folder a plain New Session uses", plain.draftProject, draft.draftProject)
        assertEquals("the drawer stays in the group", DrawerPath(listOf("g")), store.state.drawerPath)
        assertEquals("and the groups are as they were", groups(group("g")), h.shared(DrawerSharedKeys.sessionGroups))
    }

    @Test
    fun removingTheOpenGroupReturnsToTheRoot() = homeTest { h ->
        val store = h.store(homeWithGroup(h))
        store.exhaustive = false
        store.send(Action.DrawerGroupOpened("g"))
        store.send(Action.RemoveGroup("g"))
        assertTrue(store.state.drawerPath.isRoot)
        assertNull(store.state.model().tray)
    }

    @Test
    fun aServerSwitchStartsTheDrawerAtTheRoot() = homeTest { h ->
        h.setShared(DrawerSharedKeys.sessionGroups, SessionGroups(mapOf(serverID to listOf(group("g", "a")), otherServer.persistenceID to listOf(group("g", "a")))))
        val inside = h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40)))) }.copy(drawerPath = DrawerPath(listOf("g")))
        assertEquals("g", inside.openGroup?.id)
        assertEquals("the same server under a new name keeps its place", inside.drawerPath, inside.withServer(server.copy(name = "Renamed")).drawerPath)
        assertTrue("another server's Home starts at its root, even with a group of the same ID", inside.withServer(otherServer).drawerPath.isRoot)
        val other = h.state { HomeFeature.State(otherServer, snapshot(listOf(session("a", 40)))) }
        assertTrue(other.drawerPath.isRoot)
        assertNull(other.model().tray)
    }

    @Test
    fun aGroupThatGoesAwayOnASharedServerTakesTheDrawerBackToTheRoot() = homeTest { h ->
        val sessions = listOf(session("a", 40))
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(1, SessionGroup("g", "G", sessionIDs = listOf("a"))))
        val shared = h.state { HomeFeature.State(server, snapshot(sessions)) }
        val store = h.store(shared.connected()) { this[OpenCodeClient] = OpenCodeClient.testValue }
        store.exhaustive = false
        store.send(Action.DrawerGroupOpened("g"))
        assertEquals("a shared group opens as a phone one does", listOf(DrawerListItem.GroupTitle("g"), DrawerListItem.Session("a")), store.state.model().items)

        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(2))
        val gone = h.state { store.state.copy(sharedSessionGroups = h.shared(DrawerSharedKeys.sharedSessionGroups)).resolvingDrawerPath() }
        assertTrue(gone.drawerPath.isRoot)
    }

    @Test
    fun aSharedGroupOpenedBeforeTheServerNamedItFollowsItsNewID() = homeTest { h ->
        h.setShared(DrawerSharedKeys.sharedSessionGroups, sharedGroups(0))
        val plugin = FakeGroupsPlugin(sessions = setOf("a"))
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session("a", 40)))) }.connected()) {
            this[OpenCodeClient] = plugin.client()
            this[UuidClient] = UuidClient.incrementing()
        }
        store.exhaustive = false
        store.send(Action.CreateGroup("Work", listOf("a")))
        val phoneID = store.state.serverGroups.single().id
        assertTrue(store.state.groupSync.queue.isNotEmpty())
        store.send(Action.DrawerGroupOpened(phoneID))
        assertEquals(DrawerPath(listOf(phoneID)), store.state.drawerPath)
        store.settled()
        assertEquals(listOf("create", "assign"), plugin.calls.filter { it == "create" || it == "assign" })
        assertEquals(DrawerPath(listOf("srv_1")), store.state.drawerPath)
        assertEquals("srv_1", store.state.model().tray?.id)
    }

    @Test
    fun aGroupReadsOlderPagesOnlyWhileItHasMembersTheyHaventReached() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30))
        val state = h.state { HomeFeature.State(server, snapshot(sessions)) }.copy(
            sessionGroups = groups(group("partial", "a", "older"), group("whole", "b"), group("empty")),
            sessionPages = SessionPages(older = SessionPageCursor(next = "cursor")),
        )
        fun inside(id: String) = state.copy(drawerPath = DrawerPath(listOf(id))).model()
        assertEquals(listOf(DrawerListItem.Session("a"), DrawerListItem.Footer), inside("partial").items.drop(1))
        assertEquals(DrawerListFooter.loading, inside("partial").footer)
        assertEquals("every member is listed: nothing to read", listOf(DrawerListItem.Session("b")), inside("whole").items.drop(1))
        assertNull(inside("whole").footer)
        assertEquals(listOf(DrawerListItem.Empty), inside("empty").items.drop(1))
        assertEquals(
            "at the end of the list the footer goes",
            listOf(DrawerListItem.Session("a")),
            state.copy(sessionPages = SessionPages(), drawerPath = DrawerPath(listOf("partial"))).model().items.drop(1),
        )
    }

    @Test
    fun rowsInsideAGroupAreChosenByAHoldButNotLiftedAndLevelsNeverMovePins() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30))
        val base = h.state { HomeFeature.State(server, snapshot(sessions)) }
        val state = base.copy(sessionGroups = groups(group("g", "b")), pinnedSessionKeys = listOf(base.sessionKey("a")))
        val root = state.model()
        assertTrue("a pinned row still lifts at the root", root.rows["a"]!!.canLift)
        val inside = state.copy(drawerPath = DrawerPath(listOf("g"))).model()
        assertFalse(inside.rows["b"]!!.canLift)
        assertNull(inside.rows["b"]!!.unsent)

        fun shown(model: DrawerListModel) = DrawerShownPins(
            model.serverID, model.search, model.sections[DrawerListSection.pinned].orEmpty(), model.groupHeaders.keys.toList(), model.tray?.id,
        )
        assertFalse("going in plays no pin move", shown(root).movesPins(shown(inside)))
        assertFalse("nor coming back", shown(inside).movesPins(shown(root)))
        val unpinned = state.copy(pinnedSessionKeys = emptyList()).model()
        assertTrue("an Unpin at the root still does", shown(root).movesPins(shown(unpinned)))
    }

    // endregion
}
