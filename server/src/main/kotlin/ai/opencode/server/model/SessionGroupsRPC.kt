package ai.opencode.server.model

import kotlinx.serialization.Serializable

/**
 * A shared session group as the server's `session-groups` plugin stores it: ordered members by session ID.
 * [parentID] names its root in a hierarchy snapshot; null for a root and in every legacy snapshot.
 */
@Serializable
data class OpenCodeSessionGroup(
    val id: String,
    val name: String,
    val sessionIDs: List<String> = emptyList(),
    val parentID: String? = null,
)

/** The plugin's whole store: every write answers with one, and `list` reads one. */
@Serializable
data class OpenCodeSessionGroupsSnapshot(
    val revision: Long,
    val groups: List<OpenCodeSessionGroup> = emptyList(),
)

/** What `capabilities` reported, or the original list/create/assign contract when the plugin predates it. */
data class OpenCodeSessionGroupsCapabilities(
    val apiVersion: Int,
    val mutations: Set<String>,
    val operationIDs: Boolean,
    /** `hierarchyVersion`; absent on a plugin with only the flat contract. */
    val hierarchyVersion: Int? = null,
) {
    fun supports(method: String): Boolean = method in mutations

    /** The plugin offers the `hierarchy*` methods this client speaks. */
    val supportsHierarchy: Boolean get() = hierarchyVersion == HIERARCHY_VERSION

    companion object {
        /** A plugin that answers `list` but not `capabilities`. */
        val original = OpenCodeSessionGroupsCapabilities(apiVersion = 1, mutations = setOf("create", "assign"), operationIDs = false)

        const val HIERARCHY_VERSION = 1
    }
}

/** One write to the shared store; [method] is the RPC method it posts to. */
sealed interface OpenCodeSessionGroupsWrite {
    val method: String

    data class Create(val name: String) : OpenCodeSessionGroupsWrite {
        override val method: String get() = "create"
    }

    /** A null [groupID] ungroups the session. */
    data class Assign(val groupID: String?, val sessionID: String) : OpenCodeSessionGroupsWrite {
        override val method: String get() = "assign"
    }

    /** Every current group ID, once each, in the new order. */
    data class Reorder(val groupIDs: List<String>) : OpenCodeSessionGroupsWrite {
        override val method: String get() = "reorder"
    }

    data class Rename(val groupID: String, val name: String) : OpenCodeSessionGroupsWrite {
        override val method: String get() = "rename"
    }

    data class Remove(val groupID: String) : OpenCodeSessionGroupsWrite {
        override val method: String get() = "remove"
    }

    /** The one-time copy of a phone's groups into a store that has never accepted a write; flat groups only. */
    data class Import(val groups: List<OpenCodeSessionGroup>) : OpenCodeSessionGroupsWrite {
        override val method: String get() = "import"
    }

    /** A hierarchy write: answered with the hierarchy snapshot (every group's `parentID`). */
    sealed interface Hierarchy : OpenCodeSessionGroupsWrite

    /** An empty group appended under the root [parentID], or among the roots with null. */
    data class HierarchyCreate(val name: String, val parentID: String?) : Hierarchy {
        override val method: String get() = "hierarchyCreate"
    }

    /** [groupID] keeps its ID and members and joins the end of [parentID]'s children (null: the roots). */
    data class HierarchyMove(val groupID: String, val parentID: String?) : Hierarchy {
        override val method: String get() = "hierarchyMove"
    }

    /** Every child of [parentID] (null: every root), once each, in the new order. */
    data class HierarchyReorder(val parentID: String?, val groupIDs: List<String>) : Hierarchy {
        override val method: String get() = "hierarchyReorder"
    }

    /** A null [groupID] ungroups the session. */
    data class HierarchyAssign(val groupID: String?, val sessionID: String) : Hierarchy {
        override val method: String get() = "hierarchyAssign"
    }

    data class HierarchyRename(val groupID: String, val name: String) : Hierarchy {
        override val method: String get() = "hierarchyRename"
    }

    /** Refused while the group has children; its direct members are ungrouped. */
    data class HierarchyRemove(val groupID: String) : Hierarchy {
        override val method: String get() = "hierarchyRemove"
    }
}

/** How the observed members' last recorded runs ended: history, never current activity. */
@Serializable
data class OpenCodeSessionGroupOutcomes(
    val succeeded: Int = 0,
    val failed: Int = 0,
    val interrupted: Int = 0,
    val unreported: Int = 0,
)

/**
 * One group's `hierarchySummary` rollup over its direct members and its subgroups'. [lastActivityAt] is
 * the newest native `time.updated` (epoch ms) among the members the scan found; without [complete],
 * older members may have been missed, so the time and counts are observations.
 */
@Serializable
data class OpenCodeSessionGroupSummary(
    val groupID: String,
    val lastActivityAt: Long? = null,
    val memberCount: Int = 0,
    val observedNonArchivedCount: Int = 0,
    val complete: Boolean = false,
    val lastExecutionOutcomes: OpenCodeSessionGroupOutcomes = OpenCodeSessionGroupOutcomes(),
)

/** `hierarchySummary`: the rollups for the group [revision] the server read. */
@Serializable
data class OpenCodeSessionGroupsSummary(
    val revision: Long,
    val groups: List<OpenCodeSessionGroupSummary> = emptyList(),
)

/** A tagged `RpcError` body the server answered with (HTTP 400): the plugin's domain failures and the RPC layer's own. */
data class OpenCodeRpcError(
    val type: String,
    /** `data.currentRevision`, for `revision_conflict` and `import_refused`. */
    val currentRevision: Long? = null,
) {
    companion object {
        const val REVISION_CONFLICT = "revision_conflict"
        const val IMPORT_REFUSED = "import_refused"
        const val OPERATION_CONFLICT = "operation_conflict"
        const val INVALID_INPUT = "invalid_input"
        const val GROUP_NOT_FOUND = "group_not_found"
        const val SESSION_NOT_FOUND = "session_not_found"
        /** The location is up but its plugin is still loading. */
        const val UNAVAILABLE = "rpc.unavailable"
        const val METHOD_NOT_FOUND = "rpc.method_not_found"
        const val SCHEMA_INVALID = "rpc.invalid_input"
    }
}
