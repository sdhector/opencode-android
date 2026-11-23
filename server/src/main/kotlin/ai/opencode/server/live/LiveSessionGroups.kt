package ai.opencode.server.live

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSessionGroup
import ai.opencode.server.model.OpenCodeSessionGroupsCapabilities
import ai.opencode.server.model.OpenCodeSessionGroupsSnapshot
import ai.opencode.server.model.OpenCodeSessionGroupsSummary
import ai.opencode.server.model.OpenCodeSessionGroupsWrite
import ai.opencode.server.retry.RequestIdempotency
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The `session-groups` plugin's RPC routes. Every body wraps its input as `{"input":{…}}` and every
 * success answers `{"output":…}`; failures are tagged `RpcError`s (`OpenCodeClientError.rpcError`).
 */

private const val ROUTE = "/api/rpc/session-groups"

@Serializable
private data class Output<T>(val output: T)

@Serializable
private data class CapabilitiesPayload(
    val apiVersion: Int,
    val operationIDs: Boolean = false,
    val mutations: List<String> = emptyList(),
    val hierarchyVersion: Int? = null,
)

/** A hierarchy snapshot's group: `parentID` is required (null for a root), so a flat answer fails to decode. */
@Serializable
private data class HierarchyGroupPayload(
    val id: String,
    val name: String,
    val sessionIDs: List<String> = emptyList(),
    val parentID: JsonElement,
)

@Serializable
private data class HierarchySnapshotPayload(val revision: Long, val groups: List<HierarchyGroupPayload> = emptyList()) {
    fun snapshot(): OpenCodeSessionGroupsSnapshot = OpenCodeSessionGroupsSnapshot(
        revision,
        groups.map { group ->
            val parent = (group.parentID as? JsonPrimitive)?.takeIf { it.isString }?.content
            OpenCodeSessionGroup(id = group.id, name = group.name, sessionIDs = group.sessionIDs, parentID = parent)
        },
    )
}

private val emptyInput: ByteArray = """{"input":{}}""".toByteArray()

internal suspend fun requestSessionGroupsCapabilities(server: OpenCodeServer): OpenCodeSessionGroupsCapabilities {
    val reply = request<Output<CapabilitiesPayload>>(
        "$ROUTE/capabilities", method = "POST", body = emptyInput,
        idempotency = RequestIdempotency.Idempotent, server = server,
    ).output
    return OpenCodeSessionGroupsCapabilities(reply.apiVersion, reply.mutations.toSet(), reply.operationIDs, reply.hierarchyVersion)
}

internal suspend fun requestSessionGroups(server: OpenCodeServer): OpenCodeSessionGroupsSnapshot =
    request<Output<OpenCodeSessionGroupsSnapshot>>(
        "$ROUTE/list", method = "POST", body = emptyInput,
        idempotency = RequestIdempotency.Idempotent, server = server,
    ).output

/** `hierarchyList`: every group with its `parentID`. */
internal suspend fun requestSessionGroupsHierarchy(server: OpenCodeServer): OpenCodeSessionGroupsSnapshot =
    request<Output<HierarchySnapshotPayload>>(
        "$ROUTE/hierarchyList", method = "POST", body = emptyInput,
        idempotency = RequestIdempotency.Idempotent, server = server,
    ).output.snapshot()

/** `hierarchySummary`: each group's activity rollup, for the revision it names. */
internal suspend fun requestSessionGroupsSummary(server: OpenCodeServer): OpenCodeSessionGroupsSummary =
    request<Output<OpenCodeSessionGroupsSummary>>(
        "$ROUTE/hierarchySummary", method = "POST", body = emptyInput,
        idempotency = RequestIdempotency.Idempotent, server = server,
    ).output

/**
 * Sends [write] checked against [expectedRevision]. With an [operationID] the server records the
 * write, so a resend of the same body after a lost answer is safe and returns the current snapshot.
 * A hierarchy write is answered, and read, as a hierarchy snapshot.
 */
internal suspend fun requestWriteSessionGroups(
    server: OpenCodeServer,
    write: OpenCodeSessionGroupsWrite,
    expectedRevision: Long,
    operationID: String?,
): OpenCodeSessionGroupsSnapshot {
    val path = "$ROUTE/${write.method}"
    val body = writeBody(write, expectedRevision, operationID).toString().toByteArray()
    val idempotency = if (operationID != null) RequestIdempotency.Idempotent else RequestIdempotency.Unsafe
    return if (write is OpenCodeSessionGroupsWrite.Hierarchy) {
        request<Output<HierarchySnapshotPayload>>(path, method = "POST", body = body, idempotency = idempotency, server = server).output.snapshot()
    } else {
        request<Output<OpenCodeSessionGroupsSnapshot>>(path, method = "POST", body = body, idempotency = idempotency, server = server).output
    }
}

/** Built by hand: ungrouping and the roots send an explicit `null`, which the wire encoder would drop. */
internal fun writeBody(write: OpenCodeSessionGroupsWrite, expectedRevision: Long, operationID: String?): JsonObject = buildJsonObject {
    putJsonObject("input") {
        when (write) {
            is OpenCodeSessionGroupsWrite.Create -> put("name", write.name)
            is OpenCodeSessionGroupsWrite.Assign -> {
                putNullable("groupID", write.groupID)
                put("sessionID", write.sessionID)
            }
            is OpenCodeSessionGroupsWrite.Reorder -> putJsonArray("groupIDs") { write.groupIDs.forEach { add(it) } }
            is OpenCodeSessionGroupsWrite.Rename -> {
                put("groupID", write.groupID)
                put("name", write.name)
            }
            is OpenCodeSessionGroupsWrite.Remove -> put("groupID", write.groupID)
            is OpenCodeSessionGroupsWrite.Import -> putJsonArray("groups") {
                for (group in write.groups) {
                    addJsonObject {
                        put("id", group.id)
                        put("name", group.name)
                        putJsonArray("sessionIDs") { group.sessionIDs.forEach { add(it) } }
                    }
                }
            }
            is OpenCodeSessionGroupsWrite.HierarchyCreate -> {
                put("name", write.name)
                putNullable("parentID", write.parentID)
            }
            is OpenCodeSessionGroupsWrite.HierarchyMove -> {
                put("groupID", write.groupID)
                putNullable("parentID", write.parentID)
            }
            is OpenCodeSessionGroupsWrite.HierarchyReorder -> {
                putNullable("parentID", write.parentID)
                putJsonArray("groupIDs") { write.groupIDs.forEach { add(it) } }
            }
            is OpenCodeSessionGroupsWrite.HierarchyAssign -> {
                putNullable("groupID", write.groupID)
                put("sessionID", write.sessionID)
            }
            is OpenCodeSessionGroupsWrite.HierarchyRename -> {
                put("groupID", write.groupID)
                put("name", write.name)
            }
            is OpenCodeSessionGroupsWrite.HierarchyRemove -> put("groupID", write.groupID)
        }
        put("expectedRevision", expectedRevision)
        if (operationID != null) put("operationID", operationID)
    }
}

private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}
