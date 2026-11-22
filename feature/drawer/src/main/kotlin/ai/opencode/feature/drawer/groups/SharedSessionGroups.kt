package ai.opencode.feature.drawer.groups

import ai.opencode.server.model.OpenCodeSessionGroup
import ai.opencode.server.model.OpenCodeSessionGroupsCapabilities
import ai.opencode.server.model.OpenCodeSessionGroupsSnapshot
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
) {
    /**
     * Takes [snapshot] unless an answer of a later revision has already landed. A store past
     * revision 0 can't take an import, so one still waiting for its answer is settled.
     */
    fun applying(snapshot: OpenCodeSessionGroupsSnapshot, keepingCollapsed: Set<String> = emptySet()): SharedGroupsServer {
        if (snapshot.revision < revision) return this
        val groups = snapshot.groups.map { SessionGroup(id = it.id, name = it.name, sessionIDs = it.sessionIDs) }
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

    /** [groupID] is the phone's ID for it until the server's answer names the real one. */
    data class Create(val groupID: String, val name: String) : GroupEdit {
        override fun applied(groups: SessionGroups, serverID: String) = groups.creating(serverID, groupID, name)
    }

    data class Assign(val sessionID: String, val groupID: String?) : GroupEdit {
        override fun applied(groups: SessionGroups, serverID: String) = groups.assigning(serverID, listOf(sessionID), groupID)
    }

    data class Move(val groupID: String, val index: Int) : GroupEdit {
        override fun applied(groups: SessionGroups, serverID: String) = groups.moving(serverID, groupID, index)
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
        is Create -> this
        is Assign -> if (groupID == from) copy(groupID = to) else this
        is Move -> if (groupID == from) copy(groupID = to) else this
        is Rename -> if (groupID == from) copy(groupID = to) else this
        is Remove -> if (groupID == from) copy(groupID = to) else this
        is Import -> this
    }

    /** The group this edit can't be sent without, when it's one only this phone knows yet. */
    val dependsOnGroup: String?
        get() = when (this) {
            is Assign -> groupID
            is Move -> groupID
            is Rename -> groupID
            is Remove -> groupID
            is Create, is Import -> null
        }

    /**
     * The write that makes this edit on [current] (the authoritative snapshot), or null when the
     * snapshot already shows it and nothing needs sending.
     */
    fun write(current: SessionGroups, serverID: String): OpenCodeSessionGroupsWrite? = when (this) {
        is Create -> OpenCodeSessionGroupsWrite.Create(name)
        is Assign -> if (current.groupID(serverID, sessionID) == groupID) null else OpenCodeSessionGroupsWrite.Assign(groupID, sessionID)
        is Move -> {
            val before = current.groups(serverID).map { it.id }
            val after = current.moving(serverID, groupID, index).groups(serverID).map { it.id }
            if (before == after || groupID !in before) null else OpenCodeSessionGroupsWrite.Reorder(after)
        }
        is Rename -> {
            val name = SessionGroups.normalizedName(name)
            val group = current.group(serverID, groupID)
            if (name == null || group?.name == name) null else OpenCodeSessionGroupsWrite.Rename(groupID, name)
        }
        is Remove -> if (current.group(serverID, groupID) == null) null else OpenCodeSessionGroupsWrite.Remove(groupID)
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
) {
    sealed interface Support {
        data object Unknown : Support
        /** The server has no session-groups plugin: the drawer stays phone-local, as before. */
        data object Unavailable : Support
        data class Available(val capabilities: OpenCodeSessionGroupsCapabilities) : Support
    }

    val capabilities: OpenCodeSessionGroupsCapabilities? get() = (support as? Support.Available)?.capabilities
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
