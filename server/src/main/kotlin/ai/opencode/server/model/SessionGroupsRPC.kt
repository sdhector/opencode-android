package ai.opencode.server.model

import kotlinx.serialization.Serializable

/** A shared session group as the server's `session-groups` plugin stores it: ordered members by session ID. */
@Serializable
data class OpenCodeSessionGroup(
    val id: String,
    val name: String,
    val sessionIDs: List<String> = emptyList(),
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
) {
    fun supports(method: String): Boolean = method in mutations

    companion object {
        /** A plugin that answers `list` but not `capabilities`. */
        val original = OpenCodeSessionGroupsCapabilities(apiVersion = 1, mutations = setOf("create", "assign"), operationIDs = false)
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

    /** The one-time copy of a phone's groups into a store that has never accepted a write. */
    data class Import(val groups: List<OpenCodeSessionGroup>) : OpenCodeSessionGroupsWrite {
        override val method: String get() = "import"
    }
}

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
