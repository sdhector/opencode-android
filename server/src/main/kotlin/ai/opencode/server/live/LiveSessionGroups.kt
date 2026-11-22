package ai.opencode.server.live

import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSessionGroupsCapabilities
import ai.opencode.server.model.OpenCodeSessionGroupsSnapshot
import ai.opencode.server.model.OpenCodeSessionGroupsWrite
import ai.opencode.server.retry.RequestIdempotency
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
)

private val emptyInput: ByteArray = """{"input":{}}""".toByteArray()

internal suspend fun requestSessionGroupsCapabilities(server: OpenCodeServer): OpenCodeSessionGroupsCapabilities {
    val reply = request<Output<CapabilitiesPayload>>(
        "$ROUTE/capabilities", method = "POST", body = emptyInput,
        idempotency = RequestIdempotency.Idempotent, server = server,
    ).output
    return OpenCodeSessionGroupsCapabilities(reply.apiVersion, reply.mutations.toSet(), reply.operationIDs)
}

internal suspend fun requestSessionGroups(server: OpenCodeServer): OpenCodeSessionGroupsSnapshot =
    request<Output<OpenCodeSessionGroupsSnapshot>>(
        "$ROUTE/list", method = "POST", body = emptyInput,
        idempotency = RequestIdempotency.Idempotent, server = server,
    ).output

/**
 * Sends [write] checked against [expectedRevision]. With an [operationID] the server records the
 * write, so a resend of the same body after a lost answer is safe and returns the current snapshot.
 */
internal suspend fun requestWriteSessionGroups(
    server: OpenCodeServer,
    write: OpenCodeSessionGroupsWrite,
    expectedRevision: Long,
    operationID: String?,
): OpenCodeSessionGroupsSnapshot =
    request<Output<OpenCodeSessionGroupsSnapshot>>(
        "$ROUTE/${write.method}", method = "POST",
        body = writeBody(write, expectedRevision, operationID).toString().toByteArray(),
        idempotency = if (operationID != null) RequestIdempotency.Idempotent else RequestIdempotency.Unsafe,
        server = server,
    ).output

/** Built by hand: `assign` must send `"groupID": null` to ungroup, which the wire encoder would drop. */
internal fun writeBody(write: OpenCodeSessionGroupsWrite, expectedRevision: Long, operationID: String?): JsonObject = buildJsonObject {
    putJsonObject("input") {
        when (write) {
            is OpenCodeSessionGroupsWrite.Create -> put("name", write.name)
            is OpenCodeSessionGroupsWrite.Assign -> {
                if (write.groupID == null) put("groupID", JsonNull) else put("groupID", write.groupID)
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
        }
        put("expectedRevision", expectedRevision)
        if (operationID != null) put("operationID", operationID)
    }
}
