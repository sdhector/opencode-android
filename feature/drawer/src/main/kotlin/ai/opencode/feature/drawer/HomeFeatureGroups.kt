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
import ai.opencode.feature.drawer.groups.SessionGroup
import ai.opencode.feature.drawer.groups.SessionGroups

/*
 * Local Session Groups: named, collapsible bundles of root sessions that live on this phone only.
 * Nothing here talks to the server or touches a session's title, project or directory.
 */

/** `$sessionGroups.withLock { … }`. */
internal fun State.updatingSessionGroups(transform: (SessionGroups) -> SessionGroups): State {
    val reference = shared(DrawerSharedKeys.sessionGroups)
    reference.update(transform)
    val live = reference.value
    return if (live == sessionGroups) this else copy(sessionGroups = live)
}

/** This server's groups, in the order they were made. */
val State.serverGroups: List<SessionGroup> get() = sessionGroups.groups(server.persistenceID)

fun State.groupID(sessionID: String): String? = sessionGroups.groupID(server.persistenceID, sessionID)

internal fun HomeFeature.reduceGroups(state: State, action: Action): HomeReduced {
    val haptics = dependency(HapticsClient)
    val serverID = state.server.persistenceID
    when (action) {
        is Action.NewSessionInGroupTapped -> {
            if (state.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) return Reduced(state)
            if (state.sessionGroups.group(serverID, action.groupID) == null) return Reduced(state)
            val opened = state.leavingCatchUp().openingDraft(reason = "you tapped New Session in a group")
            val visit = opened.sessionDetail?.visit ?: return Reduced(opened)
            return Reduced(opened.copy(draftGroup = DraftGroupTarget(serverID, visit, action.groupID)))
        }

        is Action.CreateGroup -> {
            if (SessionGroups.normalizedName(action.name) == null) return Reduced(state)
            haptics.play(Haptic.confirm)
            val groupID = "grp_" + dependency(UuidClient).next()
            val members = movableIDs(state, action.sessionIDs)
            var s = members.fold(state) { acc, id -> acc.adoptingSearchResult(id) }
            s = s.updatingSessionGroups { it.creating(serverID, groupID, action.name).assigning(serverID, members, groupID) }
            return Reduced(s)
        }

        is Action.RenameGroup ->
            return Reduced(state.updatingSessionGroups { it.renaming(serverID, action.groupID, action.name) })

        is Action.ToggleGroupCollapsed -> {
            val group = state.sessionGroups.group(serverID, action.groupID) ?: return Reduced(state)
            haptics.play(Haptic.selection)
            return Reduced(state.updatingSessionGroups { it.settingCollapsed(serverID, action.groupID, !group.isCollapsed) })
        }

        is Action.RemoveGroup -> {
            if (state.sessionGroups.group(serverID, action.groupID) == null) return Reduced(state)
            haptics.play(Haptic.confirm)
            var s = state.updatingSessionGroups { it.removing(serverID, action.groupID) }
            if (s.draftGroup?.groupID == action.groupID) s = s.copy(draftGroup = null)
            return Reduced(s)
        }

        is Action.MoveSessionsToGroup -> {
            val members = movableIDs(state, action.sessionIDs)
            if (members.isEmpty()) return Reduced(state)
            if (action.groupID != null && state.sessionGroups.group(serverID, action.groupID) == null) return Reduced(state)
            haptics.play(Haptic.selection)
            val s = members.fold(state) { acc, id -> acc.adoptingSearchResult(id) }
            return Reduced(s.updatingSessionGroups { it.assigning(serverID, members, action.groupID) })
        }

        else -> return Reduced(state)
    }
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
    if (target.serverID != serverID || sessionGroups.group(serverID, target.groupID) == null) return copy(draftGroup = null)
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
    val serverID = server.persistenceID
    if (sessionGroups.groupID(serverID, sessionID) == groupID) return this
    return updatingSessionGroups { it.assigning(serverID, listOf(sessionID), groupID) }
}
