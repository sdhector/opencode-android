package ai.opencode.feature.drawer.groups

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import kotlinx.serialization.Serializable

/** A named, collapsible bundle of root sessions on one server. Phone-local: the server never hears of it. */
@Serializable
data class SessionGroup(
    val id: String,
    val name: String,
    val isCollapsed: Boolean = false,
    val sessionIDs: List<String> = emptyList(),
)

/**
 * Every server's groups, by `OpenCodeServer.persistenceID`. A session belongs to at most one group
 * on its server; membership is by session ID, so a session outside the loaded page, archived or
 * hidden keeps its place.
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

    fun creating(serverID: String, groupID: String, name: String): SessionGroups {
        val trimmed = normalizedName(name) ?: return this
        if (group(serverID, groupID) != null) return this
        return withGroups(serverID, groups(serverID) + SessionGroup(id = groupID, name = trimmed))
    }

    fun renaming(serverID: String, groupID: String, name: String): SessionGroups {
        val trimmed = normalizedName(name) ?: return this
        return updating(serverID, groupID) { it.copy(name = trimmed) }
    }

    fun settingCollapsed(serverID: String, groupID: String, collapsed: Boolean): SessionGroups =
        updating(serverID, groupID) { it.copy(isCollapsed = collapsed) }

    /** Removes the group only: its sessions keep everything else. */
    fun removing(serverID: String, groupID: String): SessionGroups =
        withGroups(serverID, groups(serverID).filterNot { it.id == groupID })

    /** Puts [groupID] at [index] (clamped) among this server's groups; the others keep their order. */
    fun moving(serverID: String, groupID: String, index: Int): SessionGroups {
        val groups = groups(serverID)
        val from = groups.indexOfFirst { it.id == groupID }
        if (from < 0) return this
        val to = index.coerceIn(0, groups.size - 1)
        if (to == from) return this
        val next = groups.toMutableList()
        next.add(to, next.removeAt(from))
        return withGroups(serverID, next)
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
}
