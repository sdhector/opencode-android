package ai.opencode.feature.drawer.list

import ai.opencode.core.strings.localized
import ai.opencode.feature.drawer.DrawerGroupSessions
import ai.opencode.feature.drawer.DrawerListFooter
import ai.opencode.feature.drawer.GroupSharingStatus
import ai.opencode.feature.drawer.GroupStatus
import ai.opencode.feature.drawer.canReorderGroups
import ai.opencode.feature.drawer.groupActivityCoverage
import ai.opencode.feature.drawer.groupRollups
import ai.opencode.feature.drawer.groups.GroupActivityCoverage
import ai.opencode.feature.drawer.groups.SessionGroup
import ai.opencode.feature.drawer.serverGroups
import ai.opencode.feature.drawer.shownSiblings
import ai.opencode.feature.drawer.supportsSubgroups
import ai.opencode.feature.drawer.groupSharingStatus
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeFeature.State.Indicator
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.ServerSwapTarget
import ai.opencode.feature.drawer.ServerSwitchNote
import ai.opencode.feature.drawer.canEditGroups
import ai.opencode.feature.drawer.drawerListFooter
import ai.opencode.feature.drawer.drawerSessions
import ai.opencode.feature.drawer.hiddenSessionIDs
import ai.opencode.feature.drawer.openFollowUps
import ai.opencode.feature.drawer.followup.FollowUp
import ai.opencode.feature.drawer.placeholderSession
import ai.opencode.feature.drawer.shownDrawerGroup
import ai.opencode.feature.drawer.shownGroups
import ai.opencode.feature.drawer.statusSnapshot
import ai.opencode.feature.drawer.style.DrawerSectionHeaderKind
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant

/*
 * What the drawer's list shows, built from Home's state in one pass. Rows get these values, never a
 * store. Pure: the list view keys a `LazyColumn` by [DrawerListItem.key] and compares
 * [DrawerListRow]s to decide what recomposes.
 *
 * `DrawerTunables` are constants here, so rows read them directly.
 */

enum class DrawerListSection { top, pinned, groups, recents }

sealed interface DrawerListItem {
    data object CatchUp : DrawerListItem
    data object WhatsNew : DrawerListItem
    /** Under Catch Up while the server has archived sessions. */
    data object Archived : DrawerListItem
    /** Under Catch Up while this server has follow-ups, pending or snoozed: a tap opens the Follow Up page. */
    data object FollowUp : DrawerListItem
    data class Header(val kind: DrawerSectionHeaderKind) : DrawerListItem
    data class Session(val id: String) : DrawerListItem
    /** A group's row ([DrawerListGroupHeader]): it opens the group; while searching, its matches follow it. */
    data class GroupHeader(val groupID: String) : DrawerListItem
    /** Inside a group: its name, count and the way back ([DrawerListModel.tray]), above its sessions. */
    data class GroupTitle(val groupID: String) : DrawerListItem
    /** Ends the group section: makes a group. */
    data object NewGroup : DrawerListItem
    /** Under Recents' header while a pinned or grouped row is lifted and Recents has none: where it drops. */
    data object RecentsDrop : DrawerListItem
    data object Empty : DrawerListItem
    /** Under the last row while more sessions can be read ([DrawerListFooter]). */
    data object Footer : DrawerListItem
    /** Server Flow: a row's place while the server picked answers. */
    data class Ghost(val index: Int) : DrawerListItem
    /** Server Flow: a switch went back on its own. */
    data object ServerNote : DrawerListItem

    /** A stable `LazyColumn` key. */
    val key: String
        get() = when (this) {
            CatchUp -> "catch-up"
            WhatsNew -> "whats-new"
            Archived -> "archived"
            FollowUp -> "follow-up"
            is Header -> "header-${kind.name}"
            is Session -> "session-$id"
            is GroupHeader -> "group-$groupID"
            is GroupTitle -> "group-title-$groupID"
            NewGroup -> "new-group"
            RecentsDrop -> "recents-drop"
            Empty -> "empty"
            Footer -> "footer"
            is Ghost -> "ghost-$index"
            ServerNote -> "server-note"
        }
}

/** One session row, compared to decide which rows to recompose. */
data class DrawerListRow(
    val session: OpenCodeSession,
    val isPinnedSection: Boolean,
    val selected: Boolean,
    val indicator: Indicator,
    val muted: Boolean,
    /** Hidden from the list; it shows only while searching. */
    val hidden: Boolean = false,
    val unread: Boolean,
    val isLifted: Boolean,
    val time: String,
    val project: OpenCodeProject?,
    val server: OpenCodeServer,
    val reducedMotion: Boolean,
    val increasedContrast: Boolean,
    /** Whether the row is chosen while the drawer selects rows ([DrawerSelection]); null when it isn't. */
    val checked: Boolean? = null,
    /** A new session whose first message only this phone has; null for a session the server has. */
    val unsent: Unsent? = null,
    /** The local group the session belongs to, whichever section shows it. */
    val groupID: String? = null,
    /** The row shows under its group's header. */
    val inGroup: Boolean = false,
    /** A reply read on this phone and not handled yet: the outlined Follow Up dot, when no native status shows. */
    val followUp: Boolean = false,
    /** A held row can be lifted to pin, unpin or move it; inside a group a hold only chooses it. */
    val canLift: Boolean = true,
) {
    enum class Unsent { Sending, Failed }

    /** TalkBack's state for the row. */
    val accessibilityValue: String
        get() {
            val parts = mutableListOf<String>()
            if (isPinnedSection) parts += localized(R.string.drawer_row_pinned_state)
            if (muted) parts += localized(R.string.drawer_row_muted_state)
            if (hidden) parts += localized(R.string.drawer_row_hidden_state)
            if (unsent == Unsent.Failed) parts += localized(R.string.drawer_row_unsent_failed_state)
            if (unsent == Unsent.Sending) parts += localized(R.string.drawer_row_unsent_sending_state)
            val status = when (indicator) {
                Indicator.attention -> localized(R.string.drawer_row_needs_input_state)
                Indicator.running -> if (unsent == null) localized(R.string.drawer_row_working_state) else ""
                Indicator.unread -> localized(R.string.drawer_row_unread_state)
                Indicator.none -> ""
            }
            if (status.isNotEmpty()) parts += status
            if (followUp) parts += localized(R.string.drawer_follow_up_state)
            if (time.isNotEmpty()) parts += time
            return parts.joinToString(", ")
        }
}

/** A group's row (or, inside it, its title) as the list shows it. */
data class DrawerListGroupHeader(
    val id: String,
    val name: String,
    /** Its sessions and its subgroups' that the list shows (pinned ones show in Pinned); while searching, its matches. */
    val count: Int,
    /** The sessions showing under it, in order: only search's matches, since a tap opens the group. */
    val sessionIDs: List<String> = emptyList(),
    /** It can trade places with the sibling above or below it: saved order only, never while searching. */
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false,
    /** Rename and Remove: always on the phone, on a shared server once its plugin offers them. */
    val canRename: Boolean = true,
    val canRemove: Boolean = true,
    /** Its sessions' and subgroups' status, rolled up; the row keeps the room for it either way. */
    val status: GroupStatus = GroupStatus.none,
    /** Some member has a pending follow-up, whatever [status] shows. */
    val hasFollowUp: Boolean = false,
    val isSubgroup: Boolean = false,
    /** Remove has to wait until its subgroups are moved or removed. */
    val hasSubgroups: Boolean = false,
    /** New Subgroup: a root, where groups can nest. */
    val canCreateSubgroup: Boolean = false,
    /** Move To: some other place it can go (a root to hold it, or the top level). */
    val canMoveToParent: Boolean = false,
)

/** The New Group row's sort menu: the device's order and what Last Activity can see. */
data class DrawerGroupSort(val byActivity: Boolean, val coverage: GroupActivityCoverage)

/** What the list shows besides its rows. */
data class DrawerListShortcuts(
    val catchUpCount: Int = 0,
    /** Catch Up just finished with nothing left: the finish's identity (`CatchUpFeature.State.finishes`), played once. */
    val catchUpFinish: Int? = null,
    val canMarkAllRead: Boolean = false,
    val showsWhatsNew: Boolean = false,
    /** This server's pending follow-ups. */
    val followUpCount: Int = 0,
) {
    val catchUpFinished: Boolean get() = catchUpFinish != null
    val showsCatchUp: Boolean get() = catchUpCount > 0 || catchUpFinished
}

/** The list's view-local inputs beyond Home's state. */
data class DrawerListInputs(
    val selectedSessionID: String?,
    val reducedMotion: Boolean,
    val increasedContrast: Boolean,
    /** Section headers always show. */
    val alwaysShowsHeaders: Boolean = true,
    /** Server Flow: the server the drawer shows, which may still be connecting. */
    val serverSwap: ServerSwapTarget? = null,
    /** The rows chosen in selection mode ([DrawerSelection]). */
    val checkedIDs: Set<String> = emptySet(),
)

data class DrawerListModel(
    val sections: Map<DrawerListSection, List<DrawerListItem>> = emptyMap(),
    val rows: Map<String, DrawerListRow> = emptyMap(),
    val groupHeaders: Map<String, DrawerListGroupHeader> = emptyMap(),
    val headerFollows: Map<DrawerSectionHeaderKind, Boolean> = emptyMap(),
    val shortcuts: DrawerListShortcuts = DrawerListShortcuts(),
    val archivedCount: Int = 0,
    val serverNote: ServerSwitchNote? = null,
    val search: String = "",
    val footer: DrawerListFooter? = null,
    /** Page answers read: each one checks the footer again. */
    val pagesRead: Int = 0,
    val serverID: String = "",
    val reducedMotion: Boolean = false,
    /** The New Group row's sharing menu; null without one. */
    val groupSharing: GroupSharingStatus? = null,
    /** The group the list is inside, with its title; null at the root and while searching. */
    val tray: DrawerListGroupHeader? = null,
    /** The New Group row's sort menu; null while there are no groups to order. */
    val groupSort: DrawerGroupSort? = null,
    /** Recents holds the sessions in no group: its header says Ungrouped while the server has groups. */
    val recentsAreUngrouped: Boolean = false,
) {
    /** Every item in display order. */
    val items: List<DrawerListItem> get() = order.flatMap { sections[it].orEmpty() }

    companion object {
        val order = listOf(DrawerListSection.top, DrawerListSection.pinned, DrawerListSection.groups, DrawerListSection.recents)
        const val ghostCount = 5

        /**
         * Home's state as the drawer shows it: shortcuts, then Pinned, the groups' rows and Recents
         * with their headers; inside a group, only its sessions ([tray]). [project] resolves a row's project. Every row has a project: a session in the
         * home folder or at the server's root gets one derived from its directory, so its tile is
         * stable from the first frame.
         */
        fun build(
            state: HomeFeature.State,
            inputs: DrawerListInputs,
            liftedID: String?,
            now: Instant,
            timestamp: (date: Instant, now: Instant) -> String,
            project: (OpenCodeSession, List<OpenCodeProject>) -> OpenCodeProject?,
            /** The group whose header is being dragged: its rows fold under it until it lands. */
            foldedGroupID: String? = null,
        ): DrawerListModel {
            var serverNote: ServerSwitchNote? = null
            val swap = inputs.serverSwap
            if (swap != null) {
                if (swap.isConnecting) {
                    // Ghost rows hold the other server's place until it answers.
                    return DrawerListModel(
                        sections = mapOf(
                            DrawerListSection.top to emptyList(),
                            DrawerListSection.pinned to emptyList(),
                            DrawerListSection.groups to emptyList(),
                            DrawerListSection.recents to listOf(DrawerListItem.Header(DrawerSectionHeaderKind.Recents)) +
                                (0 until ghostCount).map { DrawerListItem.Ghost(it) },
                        ),
                        headerFollows = mapOf(DrawerSectionHeaderKind.Recents to false),
                        search = state.search,
                        serverID = swap.id,
                        reducedMotion = inputs.reducedMotion,
                    )
                }
                serverNote = state.serverSwitchNote
            }
            val status = state.statusSnapshot()
            val followUps = state.openFollowUps
            val catchUpSessions = state.sessions.filter { status.needsCatchUp(it) }
            val catchUpCount = catchUpSessions.size
            val shortcuts = DrawerListShortcuts(
                catchUpCount = catchUpCount,
                catchUpFinish = if (state.catchUpState.justFinished && catchUpCount == 0) state.catchUpState.finishes else null,
                canMarkAllRead = catchUpSessions.any { status.indicator(it) == Indicator.unread },
                showsWhatsNew = state.showsWhatsNewButton,
                followUpCount = followUps.count { it.value.status == FollowUp.Status.pending },
            )
            val archivedCount = if (state.search.isEmpty()) state.inboxSidebarStatus.archived.size else 0
            val showsFollowUp = state.search.isEmpty() && followUps.isNotEmpty()
            val showsShortcuts = state.search.isEmpty() && (shortcuts.showsCatchUp || shortcuts.showsWhatsNew) || archivedCount > 0 ||
                showsFollowUp

            val top = buildList {
                if (serverNote != null && state.search.isEmpty()) add(DrawerListItem.ServerNote)
                if (state.search.isEmpty()) {
                    add(DrawerListItem.CatchUp)
                    if (showsFollowUp) add(DrawerListItem.FollowUp)
                    add(DrawerListItem.WhatsNew)
                }
                if (archivedCount > 0) add(DrawerListItem.Archived)
            }

            // One pass for both lists.
            val sessions = state.drawerSessions
            val pinned = sessions.pinned
            val recents = sessions.recents
            val rows = LinkedHashMap<String, DrawerListRow>()
            val headerFollows = HashMap<DrawerSectionHeaderKind, Boolean>()
            val hiddenIDs = state.hiddenSessionIDs
            val membership = state.shownGroups.membership(state.server.persistenceID)
            val reorderable = state.canReorderGroups
            val searching = state.search.isNotEmpty()

            fun row(session: OpenCodeSession, pinned: Boolean, inGroup: Boolean = false) = DrawerListRow(
                session = session,
                isPinnedSection = pinned,
                selected = inputs.selectedSessionID == session.id,
                indicator = status.indicator(session),
                muted = status.isMuted(session.id),
                hidden = session.id in hiddenIDs,
                unread = status.isUnread(session),
                isLifted = liftedID == session.id,
                time = timestamp(session.updatedAt, now),
                project = project(session, state.projects),
                server = state.server,
                reducedMotion = inputs.reducedMotion,
                increasedContrast = inputs.increasedContrast,
                checked = if (inputs.checkedIDs.isEmpty()) null else session.id in inputs.checkedIDs,
                groupID = membership[session.id],
                inGroup = inGroup,
                followUp = followUps[session.id]?.status == FollowUp.Status.pending,
            )

            fun header(kind: DrawerSectionHeaderKind, follows: Boolean): List<DrawerListItem> {
                headerFollows[kind] = follows
                return if (inputs.alwaysShowsHeaders || follows) listOf(DrawerListItem.Header(kind)) else emptyList()
            }

            // Unsent sessions lead Recents (or their group): they wait on you, and the server can't order them.
            val open = if (state.catchUp == null) state.sessionDetail else null
            val unsentRows = mutableListOf<DrawerListRow>()
            for (draft in state.unsentDrafts) {
                val session = draft.placeholderSession
                if (state.sessions.any { it.id == session.id }) continue
                if (state.search.isNotEmpty() && !session.title.contains(state.search.trim(), ignoreCase = true)) continue
                unsentRows += DrawerListRow(
                    session = session,
                    isPinnedSection = false,
                    selected = open != null && open.session == null && open.outboxDraftID == draft.draftID,
                    indicator = if (draft.isFailed) Indicator.none else Indicator.running,
                    muted = false,
                    unread = false,
                    isLifted = false,
                    time = timestamp(session.updatedAt, now),
                    project = project(session, state.projects),
                    server = state.server,
                    reducedMotion = inputs.reducedMotion,
                    increasedContrast = inputs.increasedContrast,
                    unsent = if (draft.isFailed) DrawerListRow.Unsent.Failed else DrawerListRow.Unsent.Sending,
                    groupID = membership[session.id],
                )
            }

            val serverID = state.server.persistenceID
            val shownGroups = state.shownGroups
            val entries = sessions.groups.associateBy { it.group.id }
            val rollups = if (entries.isEmpty()) emptyMap() else state.groupRollups()
            val parents = shownGroups.parents(serverID)
            val parentIDs = parents.values.toHashSet()
            val nests = state.supportsSubgroups
            // A group's own sessions as its list shows them: unsent first, then newest; pinned ones stay in Pinned.
            fun unsentIn(id: String) = unsentRows.filter { membership[it.session.id] == id && it.session.id !in rows }
            fun listedIn(id: String) = entries[id]?.sessions.orEmpty().filter { it.id !in rows }
            fun subtreeCount(id: String) = (listOf(id) + parents.filterValues { it == id }.keys).sumOf { unsentIn(it).size + listedIn(it).size }
            fun groupHeader(group: SessionGroup, name: String, count: Int, index: Int, siblings: Int, sessionIDs: List<String> = emptyList()) =
                DrawerListGroupHeader(
                    id = group.id,
                    name = name,
                    count = count,
                    sessionIDs = sessionIDs,
                    canMoveUp = reorderable && !searching && index > 0,
                    canMoveDown = reorderable && !searching && index < siblings - 1,
                    canRename = state.canEditGroups("rename"),
                    canRemove = state.canEditGroups("remove"),
                    status = rollups[group.id]?.status ?: GroupStatus.none,
                    hasFollowUp = rollups[group.id]?.hasFollowUp == true,
                    isSubgroup = group.id in parents,
                    hasSubgroups = group.id in parentIDs,
                    canCreateSubgroup = nests && group.id !in parents,
                    canMoveToParent = nests && shownGroups.moveTargets(serverID, group.id).isNotEmpty(),
                )

            val trayEntry = state.shownDrawerGroup?.let { group -> entries[group.id] }
            if (trayEntry != null) {
                val children = state.shownSiblings(trayEntry.group.id, rollups)
                val childHeaders = children.withIndex().associate { (index, child) ->
                    child.id to groupHeader(child, child.name, subtreeCount(child.id), index, children.size)
                }
                val title = groupHeader(trayEntry.group, trayEntry.group.name, subtreeCount(trayEntry.group.id), 0, 1)
                return tray(state, inputs, trayEntry, title, childHeaders, unsentRows, membership, shortcuts, swap?.id) { row(it, pinned = false, inGroup = true) }
            }

            val pinnedItems = mutableListOf<DrawerListItem>()
            if (pinned.isNotEmpty()) {
                pinnedItems += header(DrawerSectionHeaderKind.Pinned, follows = showsShortcuts)
                for (session in pinned) {
                    rows[session.id] = row(session, pinned = true)
                    pinnedItems += DrawerListItem.Session(session.id)
                }
            }
            val groupItems = mutableListOf<DrawerListItem>()
            val groupHeaders = LinkedHashMap<String, DrawerListGroupHeader>()
            if (searching) {
                // Every group with a match, each root before its subgroups, which show their path.
                for (group in state.serverGroups) {
                    val id = group.id
                    val unsentHere = unsentIn(id)
                    val listed = listedIn(id)
                    val count = unsentHere.size + listed.size
                    if (count == 0) continue
                    val showsRows = id != foldedGroupID
                    val name = shownGroups.displayName(serverID, id, always = true) { parent, child -> localized(R.string.drawer_group_path, parent, child) }
                    groupHeaders[id] = groupHeader(
                        group, name, count, 0, 1,
                        sessionIDs = if (showsRows) unsentHere.map { it.session.id } + listed.map { it.id } else emptyList(),
                    )
                    groupItems += DrawerListItem.GroupHeader(id)
                    if (!showsRows) continue
                    for (unsent in unsentHere) {
                        rows[unsent.session.id] = unsent.copy(inGroup = true)
                        groupItems += DrawerListItem.Session(unsent.session.id)
                    }
                    for (session in listed) {
                        rows[session.id] = row(session, pinned = false, inGroup = true)
                        groupItems += DrawerListItem.Session(session.id)
                    }
                }
            } else {
                // The roots, each one compact row that opens it; its subgroups show inside it.
                val roots = state.shownSiblings(null, rollups)
                for ((index, group) in roots.withIndex()) {
                    groupHeaders[group.id] = groupHeader(group, group.name, subtreeCount(group.id), index, roots.size)
                    groupItems += DrawerListItem.GroupHeader(group.id)
                }
            }
            // The New Group row shows while the list is not searched; searching shows only groups with a match.
            val showsGroupSection = !searching || groupHeaders.isNotEmpty()
            val hasGroups = groupHeaders.isNotEmpty()
            if (!searching) groupItems += DrawerListItem.NewGroup
            val ungroupedUnsent = unsentRows.filter { membership[it.session.id] == null }
            val recentItems = mutableListOf<DrawerListItem>()
            val recentsFollows = pinned.isNotEmpty() || showsGroupSection || showsShortcuts
            if (recents.isEmpty() && ungroupedUnsent.isEmpty() && liftedID != null) {
                // The lifted row is pinned or grouped: Recents shows as a place to drop it.
                recentItems += header(DrawerSectionHeaderKind.Recents, follows = recentsFollows)
                recentItems += DrawerListItem.RecentsDrop
            } else if (recents.isNotEmpty() || ungroupedUnsent.isNotEmpty()) {
                recentItems += header(DrawerSectionHeaderKind.Recents, follows = recentsFollows)
                for (unsent in ungroupedUnsent) {
                    rows[unsent.session.id] = unsent
                    recentItems += DrawerListItem.Session(unsent.session.id)
                }
                for (session in recents) {
                    if (session.id in rows) continue
                    rows[session.id] = row(session, pinned = false)
                    recentItems += DrawerListItem.Session(session.id)
                }
            } else if (pinned.isEmpty() && !hasGroups && (state.search.isNotEmpty() || state.hasConnected) && state.drawerListFooter == null) {
                recentItems += DrawerListItem.Empty
            }
            val footer = state.drawerListFooter
            if (footer != null) recentItems += DrawerListItem.Footer
            return DrawerListModel(
                sections = mapOf(
                    DrawerListSection.top to top,
                    DrawerListSection.pinned to pinnedItems,
                    DrawerListSection.groups to groupItems,
                    DrawerListSection.recents to recentItems,
                ),
                rows = rows,
                groupHeaders = groupHeaders,
                headerFollows = headerFollows,
                shortcuts = shortcuts,
                archivedCount = archivedCount,
                serverNote = serverNote,
                search = state.search,
                footer = footer,
                pagesRead = if (footer != null) state.sessionPages.pagesRead else 0,
                serverID = swap?.id ?: state.server.persistenceID,
                groupSharing = state.groupSharingStatus,
                reducedMotion = inputs.reducedMotion,
                groupSort = if (searching || entries.isEmpty()) null else DrawerGroupSort(state.groupsByActivity, state.groupActivityCoverage(rollups)),
                recentsAreUngrouped = entries.isNotEmpty(),
            )
        }

        /**
         * Inside a group: its title, its subgroups' rows, then only its own sessions as Recents shows
         * them (unsent first, then newest), and the footer while the group has members the loaded pages
         * haven't reached.
         */
        private fun tray(
            state: HomeFeature.State,
            inputs: DrawerListInputs,
            entry: DrawerGroupSessions,
            title: DrawerListGroupHeader,
            children: Map<String, DrawerListGroupHeader>,
            unsentRows: List<DrawerListRow>,
            membership: Map<String, String>,
            shortcuts: DrawerListShortcuts,
            swapID: String?,
            row: (OpenCodeSession) -> DrawerListRow,
        ): DrawerListModel {
            val id = entry.group.id
            val rows = LinkedHashMap<String, DrawerListRow>()
            val items = mutableListOf<DrawerListItem>()
            for (child in children.keys) items += DrawerListItem.GroupHeader(child)
            for (unsent in unsentRows) {
                if (membership[unsent.session.id] != id) continue
                rows[unsent.session.id] = unsent.copy(inGroup = true, canLift = false)
                items += DrawerListItem.Session(unsent.session.id)
            }
            for (session in entry.sessions) {
                if (session.id in rows) continue
                rows[session.id] = row(session).copy(canLift = false)
                items += DrawerListItem.Session(session.id)
            }
            val known = HashSet<String>()
            for (session in state.sessions) known += session.id
            for (unsent in unsentRows) known += unsent.session.id
            val hasUnlisted = entry.group.sessionIDs.any { it !in known && it !in state.recentlyDeletedIDs }
            val footer = state.drawerListFooter?.takeIf { hasUnlisted }
            val end = when {
                footer != null -> listOf(DrawerListItem.Footer)
                items.isEmpty() -> listOf(DrawerListItem.Empty)
                else -> emptyList()
            }
            return DrawerListModel(
                sections = mapOf(
                    DrawerListSection.top to listOf(DrawerListItem.GroupTitle(id)),
                    DrawerListSection.pinned to emptyList(),
                    DrawerListSection.groups to items,
                    DrawerListSection.recents to end,
                ),
                rows = rows,
                groupHeaders = children,
                shortcuts = shortcuts,
                search = state.search,
                footer = footer,
                pagesRead = if (footer != null) state.sessionPages.pagesRead else 0,
                serverID = swapID ?: state.server.persistenceID,
                reducedMotion = inputs.reducedMotion,
                tray = title,
            )
        }
    }
}
