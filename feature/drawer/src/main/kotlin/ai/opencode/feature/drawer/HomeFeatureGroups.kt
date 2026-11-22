package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Reduced
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.groups.DraftGroupTarget
import ai.opencode.feature.drawer.groups.DrawerSharedKeys
import ai.opencode.feature.drawer.groups.GroupEdit
import ai.opencode.feature.drawer.groups.SessionGroup
import ai.opencode.feature.drawer.groups.SessionGroups

/*
 * Session Groups: named bundles of root sessions, on this phone or shared through the server's plugin
 * (`HomeFeatureSharedGroups.kt`), that the drawer opens one at a time (`drawerPath`). Nothing here
 * touches a session's title, archive, project or directory.
 */

/** `$sessionGroups.withLock { … }`. */
internal fun State.updatingSessionGroups(transform: (SessionGroups) -> SessionGroups): State {
    val reference = shared(DrawerSharedKeys.sessionGroups)
    reference.update(transform)
    val live = reference.value
    return if (live == sessionGroups) this else copy(sessionGroups = live)
}

/** This server's groups, in the order the drawer shows them. */
val State.serverGroups: List<SessionGroup> get() = shownGroups.groups(server.persistenceID)

fun State.groupID(sessionID: String): String? = shownGroups.groupID(server.persistenceID, sessionID)

/** The group the drawer is inside, while it still exists; null at the root. */
val State.openGroup: SessionGroup?
    get() {
        val groupID = drawerPath.groupID ?: return null
        return shownGroups.group(server.persistenceID, groupID)
    }

/** The group whose sessions the drawer's list shows: [openGroup], except while searching, which lists every match. */
val State.shownDrawerGroup: SessionGroup? get() = if (search.isEmpty()) openGroup else null

/** Drops the path's levels whose group is gone (removed, or no longer on a shared server); runs after every action. */
internal fun State.resolvingDrawerPath(): State {
    if (drawerPath.isRoot) return this
    val shown = shownGroups
    val serverID = server.persistenceID
    val resolved = drawerPath.resolved { shown.group(serverID, it) != null }
    return if (resolved == drawerPath) this else copy(drawerPath = resolved)
}

internal fun HomeFeature.reduceGroups(state: State, action: Action): HomeReduced {
    val haptics = dependency(HapticsClient)
    val serverID = state.server.persistenceID
    when (action) {
        is Action.NewSessionInGroupTapped -> {
            if (state.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) return Reduced(state)
            if (state.shownGroups.group(serverID, action.groupID) == null) return Reduced(state)
            val opened = state.leavingCatchUp().openingDraft(reason = "you tapped New Session in a group")
            val visit = opened.sessionDetail?.visit ?: return Reduced(opened)
            return Reduced(opened.copy(draftGroup = DraftGroupTarget(serverID, visit, action.groupID)))
        }

        Action.DrawerNewSessionTapped -> {
            val group = state.shownDrawerGroup ?: return reduceDrawer(state, Action.NewSessionTapped)
            return reduceGroups(state, Action.NewSessionInGroupTapped(group.id))
        }

        is Action.DrawerGroupOpened -> {
            if (state.shownGroups.group(serverID, action.groupID) == null) return Reduced(state)
            return Reduced(state.copy(drawerPath = state.drawerPath.opening(action.groupID)))
        }

        Action.DrawerGroupBackTapped -> return Reduced(state.copy(drawerPath = state.drawerPath.back()))

        is Action.CreateGroup -> {
            if (SessionGroups.normalizedName(action.name) == null) return Reduced(state)
            haptics.play(Haptic.confirm)
            val groupID = "grp_" + dependency(UuidClient).next()
            val members = movableIDs(state, action.sessionIDs)
            val s = members.fold(state) { acc, id -> acc.adoptingSearchResult(id) }
            return Reduced(s.editingGroups(listOf(GroupEdit.Create(groupID, action.name)) + members.map { GroupEdit.Assign(it, groupID) }))
        }

        is Action.RenameGroup -> {
            if (!state.canEditGroups("rename") || state.shownGroups.group(serverID, action.groupID) == null) return Reduced(state)
            if (SessionGroups.normalizedName(action.name) == null) return Reduced(state)
            return Reduced(state.editingGroups(GroupEdit.Rename(action.groupID, action.name)))
        }

        is Action.RemoveGroup -> {
            if (!state.canEditGroups("remove") || state.shownGroups.group(serverID, action.groupID) == null) return Reduced(state)
            haptics.play(Haptic.confirm)
            var s = state.editingGroups(GroupEdit.Remove(action.groupID))
            if (s.draftGroup?.groupID == action.groupID) s = s.copy(draftGroup = null)
            return Reduced(s)
        }

        is Action.MoveSessionsToGroup -> return Reduced(movingSessions(state, action.sessionIDs, action.groupID))

        is Action.DrawerRowDroppedInGroup -> {
            if (action.groupID != null && state.shownGroups.group(serverID, action.groupID) == null) return Reduced(state)
            val unpinned = dropPin(state, action.sessionID, pinnedIndex = null)
            return Reduced(movingSessions(unpinned, listOf(action.sessionID), action.groupID))
        }

        is Action.MoveGroupUp -> return Reduced(movingGroup(state, action.groupID) { it - 1 })

        is Action.MoveGroupDown -> return Reduced(movingGroup(state, action.groupID) { it + 1 })

        is Action.DrawerGroupDropReleased -> {
            if (willMoveGroup(state, action.groupID, action.index)) haptics.play(Haptic.snap)
            return Reduced(state)
        }

        is Action.DrawerGroupDropped -> {
            if (!willMoveGroup(state, action.groupID, action.index)) return Reduced(state)
            return Reduced(state.editingGroups(GroupEdit.Move(action.groupID, action.index)))
        }

        else -> return Reduced(state)
    }
}

private fun willMoveGroup(state: State, groupID: String, index: Int): Boolean {
    if (!state.canEditGroups("reorder")) return false
    val groups = state.serverGroups
    val from = groups.indexOfFirst { it.id == groupID }
    return from >= 0 && index.coerceIn(0, groups.size - 1) != from
}

private fun movingGroup(state: State, groupID: String, index: (Int) -> Int): State {
    if (!state.canEditGroups("reorder")) return state
    val from = state.serverGroups.indexOfFirst { it.id == groupID }
    if (from < 0) return state
    val to = index(from)
    if (to !in state.serverGroups.indices) return state
    dependency(HapticsClient).play(Haptic.selection)
    return state.editingGroups(GroupEdit.Move(groupID, to))
}

private fun movingSessions(state: State, ids: List<String>, groupID: String?): State {
    val serverID = state.server.persistenceID
    val members = movableIDs(state, ids)
    if (members.isEmpty()) return state
    val shown = state.shownGroups
    if (groupID != null && shown.group(serverID, groupID) == null) return state
    dependency(HapticsClient).play(Haptic.selection)
    val s = members.fold(state) { acc, id -> acc.adoptingSearchResult(id) }
    // A shared group keeps its order when a member is assigned to it again; nothing needs sending.
    val moving = if (s.sharedGroups == null) members else members.filter { shown.groupID(serverID, it) != groupID }
    return s.editingGroups(moving.map { GroupEdit.Assign(it, groupID) })
}

/** The root sessions among [ids] (listed, found by search, or unsent), once each. */
private fun movableIDs(state: State, ids: List<String>): List<String> {
    val found = state.sessionPages.search?.results.orEmpty()
    return ids.distinct().filter { id ->
        val session = state.sessions.firstOrNull { it.id == id } ?: found.firstOrNull { it.id == id }
        if (session != null) session.parentID == null else state.unsentDrafts.any { it.sessionID == id }
    }
}

/**
 * Puts a draft's session in the group it was started from. The draft is named by its screen's
 * server and visit, then by its outbox lane once the first send is queued, then by the session ID
 * that lane will create, so a screen that moved on, another server, or a later draft can't take
 * its place. Runs after every action while a target is held.
 */
internal fun State.resolvingDraftGroup(): State {
    val target = draftGroup ?: return this
    val serverID = server.persistenceID
    if (target.serverID != serverID || shownGroups.group(serverID, target.groupID) == null) return copy(draftGroup = null)
    var next = target
    var s = this
    val detail = sessionDetail
    val onScreen = detail != null && detail.visit == target.visit
    if (onScreen && detail != null) {
        val created = detail.session
        if (created != null) {
            if (detail.draftBecameSessionID == created.id) s = s.assigningToGroup(created.id, target.groupID)
            return s.copy(draftGroup = null)
        }
        if (target.draftID == null && detail.outboxDraftID != null) next = next.copy(draftID = detail.outboxDraftID)
    }
    val draftID = next.draftID
    if (draftID == null) return if (onScreen) s.withDraftGroup(next) else s.copy(draftGroup = null)
    if (next.sessionID == null) {
        val lane = dependency(ComposerPersistence).loadOutbox(serverID, null)
            .firstOrNull { (it.draftID ?: it.pending.messageID) == draftID }
        if (lane != null) next = next.copy(sessionID = lane.createdSession?.id ?: lane.newSessionID)
    }
    val sessionID = next.sessionID
    if (sessionID != null) s = s.assigningToGroup(sessionID, target.groupID)
    // Off screen with nowhere to look yet, the lane is waited for; with its session assigned, it is done.
    return when {
        sessionID != null && !onScreen -> s.copy(draftGroup = null)
        else -> s.withDraftGroup(next)
    }
}

private fun State.withDraftGroup(target: DraftGroupTarget): State = if (target == draftGroup) this else copy(draftGroup = target)

private fun State.assigningToGroup(sessionID: String, groupID: String): State {
    if (shownGroups.groupID(server.persistenceID, sessionID) == groupID) return this
    return editingGroups(GroupEdit.Assign(sessionID, groupID))
}
