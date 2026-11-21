package ai.opencode.feature.drawer.list

import ai.opencode.core.strings.localized
import ai.opencode.feature.drawer.DrawerListFooter
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeFeature.State.Indicator
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.ServerSwapTarget
import ai.opencode.feature.drawer.ServerSwitchNote
import ai.opencode.feature.drawer.drawerListFooter
import ai.opencode.feature.drawer.drawerSessions
import ai.opencode.feature.drawer.hiddenSessionIDs
import ai.opencode.feature.drawer.placeholderSession
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
    data class Header(val kind: DrawerSectionHeaderKind) : DrawerListItem
    data class Session(val id: String) : DrawerListItem
    /** A local group's header ([DrawerListGroupHeader]); its sessions follow while it is open. */
    data class GroupHeader(val groupID: String) : DrawerListItem
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
            is Header -> "header-${kind.name}"
            is Session -> "session-$id"
            is GroupHeader -> "group-$groupID"
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
            if (time.isNotEmpty()) parts += time
            return parts.joinToString(", ")
        }
}

/** A group's header as the list shows it. */
data class DrawerListGroupHeader(
    val id: String,
    val name: String,
    /** Sessions in the group that this phone has loaded, whether or not they show. */
    val count: Int,
    /** Closed: its sessions don't show. Searching opens every group that has a match. */
    val isCollapsed: Boolean,
    /** The sessions showing under it, in order: none while it is closed. */
    val sessionIDs: List<String> = emptyList(),
)

/** What the list shows besides its rows. */
data class DrawerListShortcuts(
    val catchUpCount: Int = 0,
    /** Catch Up just finished with nothing left: the finish's identity (`CatchUpFeature.State.finishes`), played once. */
    val catchUpFinish: Int? = null,
    val canMarkAllRead: Boolean = false,
    val showsWhatsNew: Boolean = false,
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
) {
    /** Every item in display order. */
    val items: List<DrawerListItem> get() = order.flatMap { sections[it].orEmpty() }

    companion object {
        val order = listOf(DrawerListSection.top, DrawerListSection.pinned, DrawerListSection.groups, DrawerListSection.recents)
        const val ghostCount = 5

        /**
         * Home's state as the drawer shows it: shortcuts, then Pinned and Recents with their
         * headers. [project] resolves a row's project. Every row has a project: a session in the
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
            val catchUpSessions = state.sessions.filter { status.needsCatchUp(it) }
            val catchUpCount = catchUpSessions.size
            val shortcuts = DrawerListShortcuts(
                catchUpCount = catchUpCount,
                catchUpFinish = if (state.catchUpState.justFinished && catchUpCount == 0) state.catchUpState.finishes else null,
                canMarkAllRead = catchUpSessions.any { status.indicator(it) == Indicator.unread },
                showsWhatsNew = state.showsWhatsNewButton,
            )
            val archivedCount = if (state.search.isEmpty()) state.inboxSidebarStatus.archived.size else 0
            val showsShortcuts = state.search.isEmpty() && (shortcuts.showsCatchUp || shortcuts.showsWhatsNew) || archivedCount > 0

            val top = buildList {
                if (serverNote != null && state.search.isEmpty()) add(DrawerListItem.ServerNote)
                if (state.search.isEmpty()) {
                    add(DrawerListItem.CatchUp)
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
            val membership = state.sessionGroups.membership(state.server.persistenceID)
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
            )

            fun header(kind: DrawerSectionHeaderKind, follows: Boolean): List<DrawerListItem> {
                headerFollows[kind] = follows
                return if (inputs.alwaysShowsHeaders || follows) listOf(DrawerListItem.Header(kind)) else emptyList()
            }

            // The New Group row shows while the list is not searched; searching shows only groups with a match.
            val showsGroupSection = !searching || sessions.groups.any { it.sessions.isNotEmpty() }
            val pinnedItems = mutableListOf<DrawerListItem>()
            if (pinned.isNotEmpty()) {
                pinnedItems += header(DrawerSectionHeaderKind.Pinned, follows = showsShortcuts)
                for (session in pinned) {
                    rows[session.id] = row(session, pinned = true)
                    pinnedItems += DrawerListItem.Session(session.id)
                }
            }
            // Unsent sessions lead Recents: they wait on you, and the server can't order them.
            val open = if (state.catchUp == null) state.sessionDetail else null
            val unsentRows = mutableListOf<DrawerListRow>()
            for (draft in state.unsentDrafts) {
                val session = draft.placeholderSession
                if (session.id in rows || state.sessions.any { it.id == session.id }) continue
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
            val groupItems = mutableListOf<DrawerListItem>()
            val groupHeaders = LinkedHashMap<String, DrawerListGroupHeader>()
            for (entry in sessions.groups) {
                val id = entry.group.id
                val unsentHere = unsentRows.filter { membership[it.session.id] == id && it.session.id !in rows }
                val listed = entry.sessions.filter { it.id !in rows }
                val count = unsentHere.size + listed.size
                if (searching && count == 0) continue
                val collapsed = entry.group.isCollapsed && !searching
                groupHeaders[id] = DrawerListGroupHeader(
                    id = id,
                    name = entry.group.name,
                    count = count,
                    isCollapsed = collapsed,
                    sessionIDs = if (collapsed) emptyList() else unsentHere.map { it.session.id } + listed.map { it.id },
                )
                groupItems += DrawerListItem.GroupHeader(id)
                if (collapsed) continue
                for (unsent in unsentHere) {
                    rows[unsent.session.id] = unsent.copy(inGroup = true)
                    groupItems += DrawerListItem.Session(unsent.session.id)
                }
                for (session in listed) {
                    rows[session.id] = row(session, pinned = false, inGroup = true)
                    groupItems += DrawerListItem.Session(session.id)
                }
            }
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
                reducedMotion = inputs.reducedMotion,
            )
        }
    }
}
