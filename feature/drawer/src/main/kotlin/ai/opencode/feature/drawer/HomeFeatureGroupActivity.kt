package ai.opencode.feature.drawer

import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.HomeFeature.State.Indicator
import ai.opencode.feature.drawer.groups.GroupActivityCoverage
import ai.opencode.feature.drawer.groups.SessionGroup

/*
 * What a group row says about its sessions: the sessions' own drawer status rolled up to their group
 * and its parent, and the newest native activity that orders groups by Last Activity. Status comes
 * only from what this phone knows of each session (the row indicator's attention, running and unread,
 * and an unsent first message's sending or failure); the server's recorded execution outcomes are
 * history and never light a row.
 */

/** A group's rolled-up status, in the drawer's precedence: attention, running, a failed send, unread. */
enum class GroupStatus { attention, running, failed, unread, none }

data class GroupRollup(
    val status: GroupStatus = GroupStatus.none,
    /** The newest native `time.updated` seen among the members (epoch ms), or null when none was seen. */
    val lastActivity: Long? = null,
    /** [lastActivity] covers every member: none can be newer unseen. */
    val activityComplete: Boolean = false,
)

private fun GroupStatus.outranks(other: GroupStatus): Boolean = ordinal < other.ordinal

private fun Indicator.groupStatus(): GroupStatus = when (this) {
    Indicator.attention -> GroupStatus.attention
    Indicator.running -> GroupStatus.running
    Indicator.unread -> GroupStatus.unread
    Indicator.none -> GroupStatus.none
}

/**
 * Every group's rollup over its direct members and its subgroups' members, pinned and hidden ones
 * included. The time is the newest of the loaded sessions and, on a shared server with the hierarchy,
 * the server's last summary; it's complete when every member is loaded, or the current summary found them all.
 */
fun State.groupRollups(): Map<String, GroupRollup> {
    val serverID = server.persistenceID
    val shown = shownGroups
    val groups = shown.groups(serverID)
    if (groups.isEmpty()) return emptyMap()
    val parents = shown.parents(serverID)
    val membership = shown.membership(serverID)
    val status = statusSnapshot()
    val archiving = inboxSidebarStatus.archivedIDs
    val statuses = HashMap<String, GroupStatus>()
    val newest = HashMap<String, Long>()
    val known = HashSet<String>(recentlyDeletedIDs)

    fun credit(groupID: String, groupStatus: GroupStatus, at: Long?) {
        for (id in listOfNotNull(groupID, parents[groupID])) {
            if (groupStatus.outranks(statuses[id] ?: GroupStatus.none)) statuses[id] = groupStatus
            if (at != null && at > (newest[id] ?: Long.MIN_VALUE)) newest[id] = at
        }
    }

    for (session in sessions) {
        val groupID = membership[session.id] ?: continue
        known += session.id
        if (session.isArchived || session.id in archiving) continue
        credit(groupID, status.indicator(session).groupStatus(), session.updatedAt.toEpochMilli())
    }
    for (draft in unsentDrafts) {
        val groupID = membership[draft.sessionID] ?: continue
        if (draft.sessionID in known) continue
        known += draft.sessionID
        credit(groupID, if (draft.isFailed) GroupStatus.failed else GroupStatus.running, null)
    }

    val summary = sharedGroups?.summary
    val summaryIsCurrent = summary != null && summary.revision == sharedGroups?.revision && !groupSync.summaryFailed && !groupSync.isOffline
    val summaries = summary?.groups?.associateBy { it.groupID }.orEmpty()
    val rollups = HashMap<String, GroupRollup>()
    for (group in groups) {
        val members = groups.filter { it.id == group.id || parents[it.id] == group.id }.flatMap(SessionGroup::sessionIDs)
        val entry = summaries[group.id]
        val local = newest[group.id]
        val remote = entry?.lastActivityAt
        rollups[group.id] = GroupRollup(
            status = statuses[group.id] ?: GroupStatus.none,
            lastActivity = if (local == null) remote else if (remote == null) local else maxOf(local, remote),
            activityComplete = members.all { it in known } || (summaryIsCurrent && entry?.complete == true),
        )
    }
    return rollups
}

/** How much the Last Activity order can see, for the sort menu's note. */
fun State.groupActivityCoverage(rollups: Map<String, GroupRollup> = groupRollups()): GroupActivityCoverage {
    val shared = sharedGroups
    val summary = shared?.summary
    val allComplete = rollups.values.all { it.activityComplete }
    return when {
        summary == null -> if (allComplete) GroupActivityCoverage.Complete else GroupActivityCoverage.LoadedOnly
        summary.revision != shared.revision || groupSync.summaryFailed || groupSync.isOffline ->
            if (allComplete) GroupActivityCoverage.Complete else GroupActivityCoverage.Stale
        allComplete -> GroupActivityCoverage.Complete
        else -> GroupActivityCoverage.Partial
    }
}

/**
 * [parentID]'s children (null: the roots) as the drawer lists them: their saved order, or under Last
 * Activity the newest first, then the groups with none seen in their saved order. Sorting never writes the saved order.
 */
fun State.shownSiblings(parentID: String?, rollups: Map<String, GroupRollup>): List<SessionGroup> {
    val siblings = shownGroups.siblings(server.persistenceID, parentID)
    if (!groupsByActivity || siblings.size < 2) return siblings
    return siblings.sortedByDescending { rollups[it.id]?.lastActivity ?: Long.MIN_VALUE }
}
