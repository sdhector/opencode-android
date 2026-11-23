package ai.opencode.feature.drawer.groups

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import kotlinx.serialization.Serializable

/** A named bundle of root sessions on one server: phone-local, or a shared server's (`SharedGroupsServer`). */
@Serializable
data class SessionGroup(
    val id: String,
    val name: String,
    /** Kept as saved so files round-trip; the drawer opens a group as its own list and no longer folds it. */
    val isCollapsed: Boolean = false,
    val sessionIDs: List<String> = emptyList(),
    /** The root group this one sits in (one level only); null for a root. Absent in files saved before subgroups. */
    val parentID: String? = null,
)

/**
 * Every server's groups, by `OpenCodeServer.persistenceID`. A session belongs to at most one group
 * on its server; membership is by session ID, so a session outside the loaded page, archived or
 * hidden keeps its place. A group is a root or the child of a root; each parent's children, and the
 * roots, keep their manual order as their order in the list.
 */
@Serializable
data class SessionGroups(val byServer: Map<String, List<SessionGroup>> = emptyMap()) {
    fun groups(serverID: String): List<SessionGroup> = byServer[serverID].orEmpty()

    fun group(serverID: String, groupID: String): SessionGroup? = groups(serverID).firstOrNull { it.id == groupID }

    fun groupID(serverID: String, sessionID: String): String? =
        groups(serverID).firstOrNull { sessionID in it.sessionIDs }?.id

    /** Session ID → its group's ID, worked out once for a loop over the sessions. */
    fun membership(serverID: String): Map<String, String> {
        val groups = groups(serverID)
        if (groups.isEmpty()) return emptyMap()
        val ids = HashMap<String, String>()
        for (group in groups) {
            for (sessionID in group.sessionIDs) ids.putIfAbsent(sessionID, group.id)
        }
        return ids
    }

    /**
     * The root [groupID] sits in, or null for a root. A link to a group that is gone or is itself a child
     * reads as a root, so a damaged file still shows every group.
     */
    fun parentID(serverID: String, groupID: String): String? = parents(serverID)[groupID]

    /** Group ID → its parent's ID, for the groups that have one. */
    fun parents(serverID: String): Map<String, String> {
        val groups = groups(serverID)
        if (groups.none { it.parentID != null }) return emptyMap()
        val byID = groups.associateBy { it.id }
        val parents = HashMap<String, String>()
        for (group in groups) {
            val parent = group.parentID?.let(byID::get) ?: continue
            if (parent.id != group.id && parent.parentID?.let(byID::get) == null) parents[group.id] = parent.id
        }
        return parents
    }

    /** [parentID]'s children (null: the roots) in their manual order. */
    fun siblings(serverID: String, parentID: String?): List<SessionGroup> {
        val parents = parents(serverID)
        return groups(serverID).filter { parents[it.id] == parentID }
    }

    fun hasChildren(serverID: String, groupID: String): Boolean = parents(serverID).values.any { it == groupID }

    /** [groupID] and its children. */
    fun subtree(serverID: String, groupID: String): Set<String> {
        val parents = parents(serverID)
        return groups(serverID).mapNotNullTo(LinkedHashSet()) { group -> group.id.takeIf { it == groupID || parents[it] == groupID } }
    }

    /** The drawer's path to [groupID]: its root first. */
    fun chain(serverID: String, groupID: String): List<String> {
        if (group(serverID, groupID) == null) return emptyList()
        return listOfNotNull(parentID(serverID, groupID), groupID)
    }

    /** Every group, each root followed by its children, in manual order. */
    fun tree(serverID: String): List<SessionGroup> {
        val parents = parents(serverID)
        if (parents.isEmpty()) return groups(serverID)
        val children = groups(serverID).filter { it.id in parents }.groupBy { parents.getValue(it.id) }
        return groups(serverID).filter { it.id !in parents }.flatMap { listOf(it) + children[it.id].orEmpty() }
    }

    /**
     * Where [groupID] can move: the roots that can hold it (never itself or the parent it's in), and
     * null (the top level) while it has a parent. A group with children stays a root.
     */
    fun moveTargets(serverID: String, groupID: String): List<String?> {
        val parent = group(serverID, groupID)?.let { parentID(serverID, groupID) }
        if (group(serverID, groupID) == null || hasChildren(serverID, groupID)) return emptyList()
        val roots = siblings(serverID, null).map { it.id }.filter { it != groupID && it != parent }
        return (if (parent != null) listOf<String?>(null) else emptyList()) + roots
    }

    /**
     * [groupID]'s name, as [path] (parent's name, its name) for a subgroup whose name another group
     * also has, or for every subgroup with [always].
     */
    fun displayName(serverID: String, groupID: String, always: Boolean = false, path: (parent: String, name: String) -> String): String {
        val group = group(serverID, groupID) ?: return ""
        val parent = parentID(serverID, groupID)?.let { group(serverID, it) } ?: return group.name
        val repeats = always || groups(serverID).count { it.name.equals(group.name, ignoreCase = true) } > 1
        return if (repeats) path(parent.name, group.name) else group.name
    }

    /** A new group at the end of [parentID]'s children (null: the roots); [parentID] has to be a root. */
    fun creating(serverID: String, groupID: String, name: String, parentID: String? = null): SessionGroups {
        val trimmed = normalizedName(name) ?: return this
        if (group(serverID, groupID) != null) return this
        if (parentID != null && (group(serverID, parentID) == null || parentID(serverID, parentID) != null)) return this
        return withGroups(serverID, groups(serverID) + SessionGroup(id = groupID, name = trimmed, parentID = parentID))
    }

    fun renaming(serverID: String, groupID: String, name: String): SessionGroups {
        val trimmed = normalizedName(name) ?: return this
        return updating(serverID, groupID) { it.copy(name = trimmed) }
    }

    /** Removes the group only: its sessions keep everything else. A group with children stays. */
    fun removing(serverID: String, groupID: String): SessionGroups {
        if (hasChildren(serverID, groupID)) return this
        return withGroups(serverID, groups(serverID).filterNot { it.id == groupID })
    }

    /**
     * Puts [groupID] at [index] (clamped) among its siblings; every other group, and the places the
     * siblings take in the list, stay as they were.
     */
    fun moving(serverID: String, groupID: String, index: Int): SessionGroups {
        val groups = groups(serverID)
        if (groups.none { it.id == groupID }) return this
        val parent = parentID(serverID, groupID)
        val parents = parents(serverID)
        val siblings = groups.filter { parents[it.id] == parent }
        val from = siblings.indexOfFirst { it.id == groupID }
        val to = index.coerceIn(0, siblings.size - 1)
        if (to == from) return this
        val ordered = siblings.toMutableList().apply { add(to, removeAt(from)) }.iterator()
        return withGroups(serverID, groups.map { if (parents[it.id] == parent) ordered.next() else it })
    }

    /** Moves [groupID] to the end of [parentID]'s children (null: the roots), when [moveTargets] allows it. */
    fun reparenting(serverID: String, groupID: String, parentID: String?): SessionGroups {
        if (parentID !in moveTargets(serverID, groupID)) return this
        val group = group(serverID, groupID) ?: return this
        return withGroups(serverID, groups(serverID).filterNot { it.id == groupID } + group.copy(parentID = parentID))
    }

    /** Moves [sessionIDs] into [groupID], out of whichever group held them; null just takes them out. */
    fun assigning(serverID: String, sessionIDs: Collection<String>, groupID: String?): SessionGroups {
        if (sessionIDs.isEmpty()) return this
        if (groupID != null && group(serverID, groupID) == null) return this
        val moving = sessionIDs.toSet()
        val next = groups(serverID).map { group ->
            val kept = group.sessionIDs.filterNot { it in moving }
            if (group.id != groupID) {
                if (kept.size == group.sessionIDs.size) group else group.copy(sessionIDs = kept)
            } else {
                group.copy(sessionIDs = kept + sessionIDs.filter { it in moving }.distinct())
            }
        }
        return withGroups(serverID, next)
    }

    fun forgetting(serverID: String, sessionID: String): SessionGroups = assigning(serverID, listOf(sessionID), null)

    private fun updating(serverID: String, groupID: String, transform: (SessionGroup) -> SessionGroup): SessionGroups {
        val groups = groups(serverID)
        val index = groups.indexOfFirst { it.id == groupID }
        if (index < 0) return this
        val next = transform(groups[index])
        if (next == groups[index]) return this
        return withGroups(serverID, groups.toMutableList().also { it[index] = next })
    }

    private fun withGroups(serverID: String, groups: List<SessionGroup>): SessionGroups {
        if (groups == byServer[serverID].orEmpty()) return this
        return SessionGroups(if (groups.isEmpty()) byServer - serverID else byServer + (serverID to groups))
    }

    companion object {
        const val MAX_NAME_LENGTH = 40

        fun normalizedName(raw: String): String? = raw.trim().take(MAX_NAME_LENGTH).trim().ifEmpty { null }
    }
}

/**
 * The group a draft's first send will put its session in. It names the draft by the identity its
 * screen carries (`SessionFeature.State.TaskIdentity`: server and visit), so a screen that is not
 * this draft, or another server's Home, never claims it. Once the first send is queued it also
 * holds the draft's outbox lane ([draftID]) and the ID its session will get ([sessionID]).
 */
data class DraftGroupTarget(
    val serverID: String,
    val visit: Int,
    val groupID: String,
    val draftID: String? = null,
    val sessionID: String? = null,
)

object DrawerSharedKeys {
    /** Phone-local session groups, server-scoped like pins. */
    val sessionGroups = SharedKey.fileStorage(FileDirectory.Documents, "session-groups.json", SessionGroups())

    /** Servers whose groups are shared, with each one's last snapshot and the phone's collapse state. */
    val sharedSessionGroups = SharedKey.fileStorage(FileDirectory.Documents, "shared-session-groups.json", SharedSessionGroups())

    /** This device orders groups by their newest activity instead of their saved order; never written to a server. */
    val groupsByActivity = SharedKey.appStorage("drawer-groups-by-activity", false)
}
