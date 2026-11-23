package ai.opencode.feature.drawer.groups

import ai.opencode.server.model.OpenCodeSessionGroup
import ai.opencode.server.model.OpenCodeSessionGroupsCapabilities
import ai.opencode.server.model.OpenCodeSessionGroupsSnapshot
import ai.opencode.server.model.OpenCodeSessionGroupsSummary
import ai.opencode.server.model.OpenCodeSessionGroupsWrite
import kotlinx.serialization.Serializable

/**
 * A server whose session groups are shared: its plugin's snapshot is authoritative and this is the
 * last one seen, kept so the drawer shows it while the server is away. Collapse stays on the phone.
 * The phone's own groups for the server stay untouched in [SessionGroups] and return when sharing stops.
 */
@Serializable
data class SharedGroupsServer(
    val revision: Long = 0,
    /** In the server's order; `isCollapsed` is always false here ([collapsedIDs] holds it). */
    val groups: List<SessionGroup> = emptyList(),
    val collapsedIDs: Set<String> = emptySet(),
    /** An import sent without an answer: a retry resends it as it was, under the same operation ID. */
    val pendingImport: PendingImport? = null,
    /**
     * The last `hierarchySummary` that matched the snapshot's revision when it landed. Kept through later
     * revisions and while the server is away as the last verified activity; [GroupActivityCoverage] says how current it is.
     */
    val summary: OpenCodeSessionGroupsSummary? = null,
) {
    /**
     * Takes [snapshot] unless an answer of a later revision has already landed. A store past
     * revision 0 can't take an import, so one still waiting for its answer is settled.
     */
    fun applying(snapshot: OpenCodeSessionGroupsSnapshot, keepingCollapsed: Set<String> = emptySet()): SharedGroupsServer {
        if (snapshot.revision < revision) return this
        val groups = snapshot.groups.map { SessionGroup(id = it.id, name = it.name, sessionIDs = it.sessionIDs, parentID = it.parentID) }
        val ids = groups.mapTo(HashSet()) { it.id }
        if (snapshot.revision == 0L) pendingImport?.groups?.mapTo(ids) { it.id }
        val next = copy(
            revision = snapshot.revision,
            groups = groups,
            collapsedIDs = collapsedIDs.filterTo(HashSet()) { it in ids || it in keepingCollapsed },
            pendingImport = if (snapshot.revision > 0) null else pendingImport,
        )
        return if (next == this) this else next
    }
}

@Serializable
data class PendingImport(val operationID: String, val groups: List<SessionGroup>)

/** Every shared server's state, by `OpenCodeServer.persistenceID`. A server without an entry never opted in. */
@Serializable
data class SharedSessionGroups(val byServer: Map<String, SharedGroupsServer> = emptyMap()) {
    operator fun get(serverID: String): SharedGroupsServer? = byServer[serverID]

    fun updating(serverID: String, transform: (SharedGroupsServer) -> SharedGroupsServer): SharedSessionGroups {
        val current = byServer[serverID] ?: return this
        val next = transform(current)
        return if (next == current) this else SharedSessionGroups(byServer + (serverID to next))
    }
}

/**
 * One change to a server's groups. Applied in place for a phone-local server; for a shared one it
 * queues, shows at once over the last snapshot, and becomes its RPC write only when it is sent, so it
 * is checked against the newest revision.
 */
sealed interface GroupEdit {
    fun applied(groups: SessionGroups, serverID: String): SessionGroups

    /** [groupID] is the phone's ID for it until the server's answer names the real one. A [parentID] makes a subgroup. */
    data class Create(val groupID: String, val name: String, val parentID: String? = null) : GroupEdit {
        override fun applied(groups: SessionGroups, serverID: String) = groups.creating(serverID, groupID, name, parentID)
    }

    data class Assign(val sessionID: String, val groupID: String?) : GroupEdit {
        override fun applied(groups: SessionGroups, serverID: String) = groups.assigning(serverID, listOf(sessionID), groupID)
    }

    /** [index] is the group's place among its siblings. */
    data class Move(val groupID: String, val index: Int) : GroupEdit {
        override fun applied(groups: SessionGroups, serverID: String) = groups.moving(serverID, groupID, index)
    }

    /** Into [parentID]'s children (null: the top level), last among them. */
    data class Reparent(val groupID: String, val parentID: String?) : GroupEdit {
        override fun applied(groups: SessionGroups, serverID: String) = groups.reparenting(serverID, groupID, parentID)
    }

    data class Rename(val groupID: String, val name: String) : GroupEdit {
        override fun applied(groups: SessionGroups, serverID: String) = groups.renaming(serverID, groupID, name)
    }

    data class Remove(val groupID: String) : GroupEdit {
        override fun applied(groups: SessionGroups, serverID: String) = groups.removing(serverID, groupID)
    }

    data class Import(val pending: PendingImport) : GroupEdit {
        override fun applied(groups: SessionGroups, serverID: String): SessionGroups =
            if (groups.groups(serverID).isNotEmpty()) groups else SessionGroups(groups.byServer + (serverID to pending.groups))
    }

    /** The edit with [from] (a created group's phone ID) replaced by the server's [to]. */
    fun renamingGroup(from: String, to: String): GroupEdit = when (this) {
        is Create -> if (parentID == from) copy(parentID = to) else this
        is Assign -> if (groupID == from) copy(groupID = to) else this
        is Move -> if (groupID == from) copy(groupID = to) else this
        is Reparent -> copy(groupID = if (groupID == from) to else groupID, parentID = if (parentID == from) to else parentID)
        is Rename -> if (groupID == from) copy(groupID = to) else this
        is Remove -> if (groupID == from) copy(groupID = to) else this
        is Import -> this
    }

    /** Whether this edit can't be sent without [groupID], a group only this phone may know yet. */
    fun dependsOn(groupID: String): Boolean = when (this) {
        is Create -> parentID == groupID
        is Assign -> this.groupID == groupID
        is Move -> this.groupID == groupID
        is Reparent -> this.groupID == groupID || parentID == groupID
        is Rename -> this.groupID == groupID
        is Remove -> this.groupID == groupID
        is Import -> false
    }

    /**
     * The write that makes this edit on [current] (the authoritative snapshot), or null when the
     * snapshot already shows it and nothing needs sending. With [hierarchy] every edit but an import
     * is a hierarchy write; without it a subgroup edit has no write.
     */
    fun write(current: SessionGroups, serverID: String, hierarchy: Boolean = false): OpenCodeSessionGroupsWrite? = when (this) {
        is Create -> when {
            hierarchy -> OpenCodeSessionGroupsWrite.HierarchyCreate(name, parentID)
            parentID == null -> OpenCodeSessionGroupsWrite.Create(name)
            else -> null
        }
        is Assign -> when {
            current.groupID(serverID, sessionID) == groupID -> null
            hierarchy -> OpenCodeSessionGroupsWrite.HierarchyAssign(groupID, sessionID)
            else -> OpenCodeSessionGroupsWrite.Assign(groupID, sessionID)
        }
        is Move -> {
            val parent = current.parentID(serverID, groupID)
            val before = current.siblings(serverID, parent).map { it.id }
            val after = current.moving(serverID, groupID, index).siblings(serverID, parent).map { it.id }
            when {
                before == after || groupID !in before -> null
                hierarchy -> OpenCodeSessionGroupsWrite.HierarchyReorder(parent, after)
                else -> OpenCodeSessionGroupsWrite.Reorder(current.moving(serverID, groupID, index).groups(serverID).map { it.id })
            }
        }
        is Reparent -> {
            val group = current.group(serverID, groupID)
            if (!hierarchy || group == null || current.parentID(serverID, groupID) == parentID) null
            else OpenCodeSessionGroupsWrite.HierarchyMove(groupID, parentID)
        }
        is Rename -> {
            val name = SessionGroups.normalizedName(name)
            val group = current.group(serverID, groupID)
            when {
                name == null || group?.name == name -> null
                hierarchy -> OpenCodeSessionGroupsWrite.HierarchyRename(groupID, name)
                else -> OpenCodeSessionGroupsWrite.Rename(groupID, name)
            }
        }
        is Remove -> when {
            current.group(serverID, groupID) == null -> null
            hierarchy -> OpenCodeSessionGroupsWrite.HierarchyRemove(groupID)
            else -> OpenCodeSessionGroupsWrite.Remove(groupID)
        }
        // Import takes flat groups only; a hierarchy server gets the links as moves afterwards.
        is Import -> OpenCodeSessionGroupsWrite.Import(
            pending.groups.map { OpenCodeSessionGroup(id = it.id, name = it.name, sessionIDs = it.sessionIDs) },
        )
    }
}

/** A queued shared edit and the operation ID its write keeps across resends. */
data class GroupWrite(val operationID: String, val edit: GroupEdit)

/** What Home knows about the connected server's shared groups this run: nothing here is persisted. */
data class GroupSync(
    val support: Support = Support.Unknown,
    /** A discovery or `list` in flight. */
    val isReading: Boolean = false,
    /** The last read or write didn't reach the plugin: the drawer shows the last-known groups. */
    val isOffline: Boolean = false,
    /** Edits not yet confirmed, oldest first; the first is in flight while [isWriting]. */
    val queue: List<GroupWrite> = emptyList(),
    val isWriting: Boolean = false,
    /** Assignments of unsent sessions, sent once the server lists their session. */
    val waiting: List<GroupWrite> = emptyList(),
    /** Writes wait for a fresh snapshot (after a conflict). */
    val needsRead: Boolean = false,
    val prompt: GroupSharingPrompt? = null,
    /** Drawer-visible refreshes left before the periodic read stops. */
    val pollsLeft: Int = 0,
    /** The number of the last `hierarchySummary` sent; only its answer lands. */
    val summaryRequest: Int = 0,
    val isReadingSummary: Boolean = false,
    /** A refresh read the groups since the last summary: activity may have moved at the same revision. */
    val summaryDue: Boolean = false,
    /** The snapshot revision the last summary was asked at, so a revision is asked about once unless a refresh comes. */
    val summaryAskedRevision: Long? = null,
    /** The last summary read failed or answered another revision: the kept one is the last verified. */
    val summaryFailed: Boolean = false,
) {
    sealed interface Support {
        data object Unknown : Support
        /** The server has no session-groups plugin: the drawer stays phone-local, as before. */
        data object Unavailable : Support
        data class Available(val capabilities: OpenCodeSessionGroupsCapabilities) : Support
    }

    val capabilities: OpenCodeSessionGroupsCapabilities? get() = (support as? Support.Available)?.capabilities
}

/** How much of the groups' activity the drawer can see, for ordering by Last Activity. */
enum class GroupActivityCoverage {
    /** The server's summary for this revision found every member (or the end of the list). */
    Complete,
    /** The server's summary for this revision is current, but some groups' members weren't all found. */
    Partial,
    /** The last verified summary is kept while the server can't give a current one. */
    Stale,
    /** No server summary: only the sessions this phone has loaded. */
    LoadedOnly,
}

/** The sharing dialog the drawer shows. */
sealed interface GroupSharingPrompt {
    /** Reading the server's groups before asking. */
    data object Checking : GroupSharingPrompt

    /**
     * Turning sharing on. [serverGroupCount] groups are on the server; [localGroupCount] on this
     * phone. [canImport]: the server's store has never been written and this phone has groups.
     */
    data class Share(val snapshot: OpenCodeSessionGroupsSnapshot, val localGroupCount: Int, val canImport: Boolean) : GroupSharingPrompt {
        val serverGroupCount: Int get() = snapshot.groups.size
    }

    data class Import(val localGroupCount: Int) : GroupSharingPrompt

    data object Stop : GroupSharingPrompt
}
