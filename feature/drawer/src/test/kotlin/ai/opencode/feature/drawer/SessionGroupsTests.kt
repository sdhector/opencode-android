package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
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
import ai.opencode.feature.drawer.groups.DraftGroupTarget
import ai.opencode.feature.drawer.groups.DrawerSharedKeys
import ai.opencode.feature.drawer.groups.SessionGroup
import ai.opencode.feature.drawer.groups.SessionGroups
import ai.opencode.feature.drawer.list.DrawerListInputs
import ai.opencode.feature.drawer.list.DrawerListItem
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
        val store = SessionGroups()
            .creating(serverID, "one", "One")
            .creating(serverID, "two", "Two")
            .creating(serverID, "three", "Three")
            .assigning(serverID, listOf("s1"), "two")
            .settingCollapsed(serverID, "two", true)
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
    fun groupedSessionsLeaveRecentsAndShowUnderTheirGroupNewestFirst() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30), session("c", 20), session("d", 10))
        val state = h.state { HomeFeature.State(server, snapshot(sessions)) }
            .copy(sessionGroups = groups(group("g", "c", "b"), group("empty")))
        val drawer = state.drawerSessions
        assertEquals(listOf("a", "d"), drawer.recents.map { it.id })
        assertEquals(listOf("g", "empty"), drawer.groups.map { it.group.id })
        assertEquals(listOf("b", "c"), drawer.groups[0].sessions.map { it.id })
        assertTrue(drawer.groups[1].sessions.isEmpty())

        val model = state.model()
        assertEquals(listOf("b", "c"), model.sections[DrawerListSection.groups]!!.filterIsInstance<DrawerListItem.Session>().map { it.id })
        assertEquals(listOf("a", "d"), model.sections[DrawerListSection.recents]!!.filterIsInstance<DrawerListItem.Session>().map { it.id })
        assertEquals(listOf("g", "empty"), model.groupHeaders.keys.toList())
        assertEquals(2, model.groupHeaders["g"]?.count)
        assertEquals("g", model.rows["b"]?.groupID)
        assertTrue(model.rows["b"]?.inGroup == true)
        assertNull(model.rows["a"]?.groupID)
        assertTrue("the list ends the group section with New Group", model.sections[DrawerListSection.groups]!!.last() == DrawerListItem.NewGroup)
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
        assertEquals(setOf("a", "b", "c"), ids.toSet())
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
                DrawerListItem.GroupHeader("g"), DrawerListItem.Session("c"), DrawerListItem.NewGroup,
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
                DrawerListItem.GroupHeader("g"), DrawerListItem.Session("c"), DrawerListItem.NewGroup,
                DrawerListItem.Header(DrawerSectionHeaderKind.Recents), DrawerListItem.Session("a"), DrawerListItem.Session("b"),
            ),
            base.copy(sessionGroups = groups(group("g", "c"))).model().shown(),
        )
        assertEquals("the pin drag is told which sessions each group shows", listOf("c"), pinned.copy(sessionGroups = groups(group("g", "c"))).model().groupHeaders["g"]?.sessionIDs)
    }

    @Test
    fun aCollapsedGroupHidesItsRowsUntilSearchFindsThem() = homeTest { h ->
        val sessions = listOf(session("a", 40), session("b", 30))
        val state = h.state { HomeFeature.State(server, snapshot(sessions)) }
            .copy(sessionGroups = groups(group("g", "b", collapsed = true)))
        val collapsed = state.model()
        assertTrue(collapsed.groupHeaders["g"]!!.isCollapsed)
        assertEquals("the count stays while it's closed", 1, collapsed.groupHeaders["g"]!!.count)
        assertFalse("b" in collapsed.sessionIDs())
        assertNull(collapsed.rows["b"])

        val searching = state.copy(search = "Title b").model()
        assertFalse("searching opens it", searching.groupHeaders["g"]!!.isCollapsed)
        assertTrue("b" in searching.sessionIDs())
        assertEquals("only matches show", listOf("b"), searching.sessionIDs())
        assertFalse("the empty group isn't offered while searching", state.copy(search = "Title a").model().groupHeaders.containsKey("g"))
        assertFalse("nor New Group", DrawerListItem.NewGroup in searching.items)
        assertTrue("the setting itself is untouched", state.copy(search = "Title b").sessionGroups.group(serverID, "g")!!.isCollapsed)
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
        store.send(Action.ToggleGroupCollapsed(id))
        assertTrue(store.state.serverGroups.single().isCollapsed)
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
        store.send(Action.ToggleGroupCollapsed("nope"))
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

        val folded = state.model(foldedGroupID = "one")
        assertEquals(
            listOf(DrawerListItem.GroupHeader("one"), DrawerListItem.GroupHeader("two"), DrawerListItem.Session("c"), DrawerListItem.NewGroup),
            folded.sections[DrawerListSection.groups],
        )
        assertEquals("the lifted header keeps its count", 2, folded.groupHeaders["one"]!!.count)
        assertFalse("it isn't closed", folded.groupHeaders["one"]!!.isCollapsed)
        assertFalse("its rows don't fall into Recents", "a" in folded.sessionIDs())

        val rest = state.model()
        assertEquals(false to true, rest.groupHeaders["one"]!!.let { it.canMoveUp to it.canMoveDown })
        assertEquals(true to false, rest.groupHeaders["two"]!!.let { it.canMoveUp to it.canMoveDown })
        assertFalse(state.copy(search = "Title").model().groupHeaders.values.any { it.canMoveUp || it.canMoveDown })

        assertEquals(
            "the list follows the saved order, rows and all",
            listOf(
                DrawerListItem.GroupHeader("two"), DrawerListItem.Session("c"),
                DrawerListItem.GroupHeader("one"), DrawerListItem.Session("a"), DrawerListItem.Session("b"), DrawerListItem.NewGroup,
            ),
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
                DrawerListItem.GroupHeader("two"), DrawerListItem.Session("b"), DrawerListItem.GroupHeader("one"), DrawerListItem.NewGroup,
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
        val model = listed.model()
        assertEquals("the unsent row shows under its group", listOf("ses_queued"), model.sections[DrawerListSection.groups]!!.filterIsInstance<DrawerListItem.Session>().map { it.id })
        assertTrue(model.rows["ses_queued"]?.inGroup == true)
        assertNotNull(model.rows["ses_queued"]?.unsent)
        assertEquals("the group counts it", 1, model.groupHeaders["g"]?.count)
    }

    // endregion
}
