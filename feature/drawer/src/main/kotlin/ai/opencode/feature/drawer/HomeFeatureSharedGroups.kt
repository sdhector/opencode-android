package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.core.strings.localized
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.groups.DrawerSharedKeys
import ai.opencode.feature.drawer.groups.GroupEdit
import ai.opencode.feature.drawer.groups.GroupSharingPrompt
import ai.opencode.feature.drawer.groups.GroupSync
import ai.opencode.feature.drawer.groups.GroupWrite
import ai.opencode.feature.drawer.groups.PendingImport
import ai.opencode.feature.drawer.groups.SessionGroup
import ai.opencode.feature.drawer.groups.SessionGroups
import ai.opencode.feature.drawer.groups.SharedGroupsServer
import ai.opencode.feature.drawer.groups.SharedSessionGroups
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeRpcError
import ai.opencode.server.model.OpenCodeSessionGroupsCapabilities
import ai.opencode.server.model.OpenCodeSessionGroupsSnapshot
import ai.opencode.server.model.OpenCodeSessionGroupsWrite
import kotlin.time.Duration.Companion.seconds

/*
 * Shared Session Groups: a server with the session-groups plugin can keep the drawer's groups for
 * every client. Once this phone opts in for a server, the server's snapshot is authoritative: edits
 * queue as writes checked against its revision and show at once over the last snapshot, which the
 * drawer keeps showing while the server is away. Collapse and the phone's own groups stay here.
 */

/** This server's shared state, or null while its groups are phone-local. */
val State.sharedGroups: SharedGroupsServer? get() = sharedSessionGroups[server.persistenceID]

/** The groups the drawer shows: the phone's, or the shared snapshot with queued edits over it. */
val State.shownGroups: SessionGroups
    get() {
        val shared = sharedGroups ?: return sessionGroups
        val serverID = server.persistenceID
        var groups = SessionGroups(if (shared.groups.isEmpty()) emptyMap() else mapOf(serverID to shared.groups))
        for (write in groupSync.queue) groups = write.edit.applied(groups, serverID)
        for (write in groupSync.waiting) groups = write.edit.applied(groups, serverID)
        if (shared.collapsedIDs.isEmpty()) return groups
        return SessionGroups(
            groups.byServer.mapValues { (_, list) -> list.map { if (it.id in shared.collapsedIDs) it.copy(isCollapsed = true) else it } },
        )
    }

/** Whether [method] can change this server's groups: always on the phone, after discovery on a shared server. */
fun State.canEditGroups(method: String): Boolean {
    if (sharedGroups == null) return true
    if (method == "create" || method == "assign") return true
    val capabilities = groupSync.capabilities ?: return false
    return capabilities.supportsHierarchy || capabilities.supports(method)
}

/**
 * Whether groups can hold subgroups here: always on the phone; on a shared server only once its plugin
 * reports the hierarchy contract. A legacy server's groups stay one level.
 */
val State.supportsSubgroups: Boolean
    get() = sharedGroups == null || groupSync.capabilities?.supportsHierarchy == true

/** What the drawer's sharing menu offers; null keeps the group section as it was (no plugin, never shared). */
data class GroupSharingStatus(val isShared: Boolean, val isOffline: Boolean, val canImport: Boolean)

val State.groupSharingStatus: GroupSharingStatus?
    get() {
        val shared = sharedGroups
        if (shared == null) {
            return if (groupSync.support is GroupSync.Support.Available) GroupSharingStatus(isShared = false, isOffline = false, canImport = false) else null
        }
        return GroupSharingStatus(isShared = true, isOffline = groupSync.isOffline, canImport = canImportLocalGroups(shared))
    }

private fun State.canImportLocalGroups(shared: SharedGroupsServer): Boolean =
    shared.revision == 0L && shared.groups.isEmpty() && groupSync.queue.none { it.edit is GroupEdit.Import } &&
        groupSync.capabilities?.supports("import") == true && sessionGroups.groups(server.persistenceID).isNotEmpty()

/** Applies [edits] to the phone's groups, or queues them as writes to a shared server. */
internal fun State.editingGroups(edits: List<GroupEdit>): State {
    if (edits.isEmpty()) return this
    val serverID = server.persistenceID
    if (sharedGroups == null) return updatingSessionGroups { edits.fold(it) { groups, edit -> edit.applied(groups, serverID) } }
    val uuid = dependency(UuidClient)
    var sync = groupSync
    for (edit in edits) {
        val write = GroupWrite(operationID = "android-" + uuid.next(), edit = edit)
        sync = if (edit is GroupEdit.Assign && waitsForSession(edit.sessionID)) sync.copy(waiting = sync.waiting + write) else sync.copy(queue = sync.queue + write)
    }
    return copy(groupSync = sync)
}

internal fun State.editingGroups(vararg edits: GroupEdit): State = editingGroups(edits.toList())

/** A session only this phone has yet (an unsent draft): the server can't group it until it exists. */
private fun State.waitsForSession(sessionID: String): Boolean =
    sessions.none { it.id == sessionID } && sessionPages.search?.results.orEmpty().none { it.id == sessionID }

/** A deleted draft's waiting assignment goes with it. */
internal fun State.forgettingWaitingGroupWrites(sessionID: String): State =
    withSync { sync -> sync.copy(waiting = sync.waiting.filterNot { (it.edit as GroupEdit.Assign).sessionID == sessionID }) }

private fun State.updatingShared(transform: (SharedGroupsServer) -> SharedGroupsServer): State {
    val serverID = server.persistenceID
    val reference = shared(DrawerSharedKeys.sharedSessionGroups)
    reference.update { it.updating(serverID, transform) }
    val live = reference.value
    return if (live == sharedSessionGroups) this else copy(sharedSessionGroups = live)
}

private fun State.settingShared(entry: SharedGroupsServer?): State {
    val serverID = server.persistenceID
    val reference = shared(DrawerSharedKeys.sharedSessionGroups)
    reference.update { SharedSessionGroups(if (entry == null) it.byServer - serverID else it.byServer + (serverID to entry)) }
    val live = reference.value
    return if (live == sharedSessionGroups) this else copy(sharedSessionGroups = live)
}

private fun State.withSync(transform: (GroupSync) -> GroupSync): State {
    val next = transform(groupSync)
    return if (next == groupSync) this else copy(groupSync = next)
}

internal fun HomeFeature.reduceSharedGroups(state: State, action: Action): HomeReduced {
    val serverID = state.server.persistenceID
    val haptics = dependency(HapticsClient)
    when (action) {
        Action.GroupSharingDiscoveryRequested -> {
            if (state.sharedGroups != null || state.groupSync.support != GroupSync.Support.Unknown) return Reduced(state)
            if (state.groupSync.isReading || !state.isConnected) return Reduced(state)
            return readingGroups(state)
        }

        Action.ShareGroupsTapped -> {
            if (state.sharedGroups != null || state.groupSync.prompt != null) return Reduced(state)
            haptics.play(Haptic.tap)
            val s = state.withSync { it.copy(prompt = GroupSharingPrompt.Checking) }
            return if (s.groupSync.isReading) Reduced(s) else readingGroups(s)
        }

        is Action.ShareGroupsConfirmed -> {
            val prompt = state.groupSync.prompt as? GroupSharingPrompt.Share ?: return Reduced(state)
            haptics.play(Haptic.confirm)
            AppLog.app.info { "Shared groups: sharing on, revision ${prompt.snapshot.revision}, ${prompt.serverGroupCount} groups, imports ${action.importsLocal && prompt.canImport}" }
            var s = state.settingShared(SharedGroupsServer().applying(prompt.snapshot))
            s = s.withSync { it.copy(prompt = null, isOffline = false, pollsLeft = if (s.isDrawerOpen) pollRounds else 0) }
            val poll = if (s.isDrawerOpen) pollingGroups() else Effect.none()
            if (!action.importsLocal || !prompt.canImport) return Reduced(s, poll)
            val imported = importingLocalGroups(s)
            return Reduced(imported.state, Effect.merge(poll, imported.effect))
        }

        Action.ImportLocalGroupsTapped -> {
            val shared = state.sharedGroups ?: return Reduced(state)
            if (!state.canImportLocalGroups(shared)) return Reduced(state)
            return Reduced(state.withSync { it.copy(prompt = GroupSharingPrompt.Import(state.sessionGroups.groups(serverID).size)) })
        }

        Action.ImportLocalGroupsConfirmed -> {
            if (state.groupSync.prompt !is GroupSharingPrompt.Import) return Reduced(state)
            val shared = state.sharedGroups ?: return Reduced(state)
            val s = state.withSync { it.copy(prompt = null) }
            if (!s.canImportLocalGroups(shared)) return Reduced(s)
            haptics.play(Haptic.confirm)
            return importingLocalGroups(s)
        }

        Action.StopSharingGroupsTapped -> {
            if (state.sharedGroups == null) return Reduced(state)
            return Reduced(state.withSync { it.copy(prompt = GroupSharingPrompt.Stop) })
        }

        Action.StopSharingGroupsConfirmed -> {
            if (state.groupSync.prompt != GroupSharingPrompt.Stop || state.sharedGroups == null) return Reduced(state)
            haptics.play(Haptic.confirm)
            AppLog.app.info { "Shared groups: sharing off" }
            var s = state.settingShared(null)
            // Discovery stands: the menu can offer sharing again.
            s = s.copy(groupSync = GroupSync(support = state.groupSync.support))
            val draft = s.draftGroup
            if (draft != null && s.shownGroups.group(serverID, draft.groupID) == null) s = s.copy(draftGroup = null)
            return Reduced(
                s,
                Effect.merge(
                    Effect.cancel(HomeFeature.CancelID.SharedGroupsRead),
                    Effect.cancel(HomeFeature.CancelID.SharedGroupsWrite),
                    Effect.cancel(HomeFeature.CancelID.SharedGroupsPoll),
                    Effect.cancel(HomeFeature.CancelID.SharedGroupsImport),
                    Effect.cancel(HomeFeature.CancelID.SharedGroupsSummary),
                ),
            )
        }

        Action.GroupSharingPromptDismissed -> return Reduced(state.withSync { it.copy(prompt = null) })

        is Action.SharedGroupsRead -> {
            if (action.serverID != serverID) return Reduced(state)
            return Reduced(readFinished(state, action.read))
        }

        is Action.SharedGroupsWritten -> {
            if (action.serverID != serverID) return Reduced(state)
            return writeFinished(state, action)
        }

        is Action.SharedGroupsImportPrepared -> {
            if (action.serverID != serverID || state.sharedGroups == null) return Reduced(state)
            if (action.groups.isEmpty()) return Reduced(state)
            val pending = PendingImport(operationID = "android-import-" + dependency(UuidClient).next(), groups = action.groups)
            val local = state.sessionGroups
            val collapsed = action.groups.filter { local.group(serverID, it.id)?.isCollapsed == true }.map { it.id }
            val s = state.updatingShared { it.copy(pendingImport = pending, collapsedIDs = it.collapsedIDs + collapsed) }
            return Reduced(s.withSync { it.copy(queue = it.queue + GroupWrite(pending.operationID, GroupEdit.Import(pending))) })
        }

        Action.SharedGroupsPollTick -> {
            val s = state.withSync { it.copy(pollsLeft = (it.pollsLeft - 1).coerceAtLeast(0)) }
            if (s.sharedGroups == null || !s.isDrawerOpen || !s.isConnected) return Reduced(s)
            if (s.groupSync.isReading || s.groupSync.isWriting || s.groupSync.queue.isNotEmpty()) return Reduced(s)
            return readingGroups(s)
        }

        is Action.SharedGroupsSummaryRead -> {
            if (action.serverID != serverID) return Reduced(state)
            return summaryFinished(state, action)
        }

        Action.ClearGroupsError -> return Reduced(state.copy(groupsError = null))

        else -> return Reduced(state)
    }
}

/** Drawer-visible refreshes (30 s apart) a drawer left open gets before they stop. */
private const val pollRounds = 20
private val pollInterval = 30.seconds

private fun pollingGroups(): Effect<Action> {
    val clock = dependency(Clock)
    return Effect.run<Action> { send ->
        repeat(pollRounds) {
            clock.sleep(pollInterval)
            send(Action.SharedGroupsPollTick)
        }
    }.cancellable(HomeFeature.CancelID.SharedGroupsPoll, cancelInFlight = true)
}

/**
 * Reads the shared groups again when the drawer opens, the app returns, or the server reconnects;
 * keeps a bounded periodic read going while the drawer stays open, and stops it when it closes or
 * the app leaves. Only shared servers read.
 */
internal fun HomeFeature.sharedGroupsLifecycle(state: State, action: Action): HomeReduced {
    if (state.sharedGroups == null) return Reduced(state)
    return when (action) {
        is Action.SessionDrawerArrived ->
            if (action.isOpen) {
                val read = if (state.isConnected) readingGroups(state) else Reduced(state)
                Reduced(read.state.withSync { it.copy(pollsLeft = pollRounds) }, Effect.merge(read.effect, pollingGroups()))
            } else {
                Reduced(state.withSync { it.copy(pollsLeft = 0) }, Effect.cancel(HomeFeature.CancelID.SharedGroupsPoll))
            }
        Action.SceneBecameActive -> {
            val read = readingGroups(state)
            if (!state.isDrawerOpen) read else Reduced(read.state.withSync { it.copy(pollsLeft = pollRounds) }, Effect.merge(read.effect, pollingGroups()))
        }
        is Action.ServerConnected -> readingGroups(state)
        Action.SceneEnteredBackground, Action.ConnectionLost ->
            Reduced(state.withSync { it.copy(pollsLeft = 0) }, Effect.cancel(HomeFeature.CancelID.SharedGroupsPoll))
        else -> Reduced(state)
    }
}

/** The outcome of one discovery and `list`. */
data class SharedGroupsReadResult(
    val support: GroupSync.Support,
    val snapshot: OpenCodeSessionGroupsSnapshot? = null,
    val error: OpenCodeClientError? = null,
)

private fun HomeFeature.readingGroups(state: State): HomeReduced {
    if (state.groupSync.isReading) return Reduced(state)
    val server = state.server
    val support = state.groupSync.support
    val client = dependency(OpenCodeClient)
    val clock = dependency(Clock)
    val s = state.withSync { it.copy(isReading = true) }
    return Reduced(
        s,
        Effect.run<Action> { send ->
            send(Action.SharedGroupsRead(server.persistenceID, readSharedGroups(client, clock, server, support)))
        }.cancellable(HomeFeature.CancelID.SharedGroupsRead),
    )
}

/** The route isn't there: no plugin, or one that predates the method. */
private val OpenCodeClientError.isMissingRoute: Boolean
    get() = statusCode == 404 || rpcError?.type == OpenCodeRpcError.METHOD_NOT_FOUND

private val OpenCodeClientError.isPluginLoading: Boolean get() = rpcError?.type == OpenCodeRpcError.UNAVAILABLE

/** Reads sent while the location's plugin is still loading, a few seconds apart. */
private const val pluginLoadingAttempts = 3

private suspend fun <T> whilePluginLoads(clock: Clock, block: suspend () -> T): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (error: OpenCodeClientError) {
            if (!error.isPluginLoading || attempt >= pluginLoadingAttempts) throw error
            clock.sleep((attempt * 2).seconds)
            attempt += 1
        }
    }
}

internal suspend fun readSharedGroups(
    client: OpenCodeClient,
    clock: Clock,
    server: OpenCodeServer,
    known: GroupSync.Support,
): SharedGroupsReadResult {
    var capabilities: OpenCodeSessionGroupsCapabilities? = (known as? GroupSync.Support.Available)?.capabilities
    var capabilitiesMissing = false
    if (capabilities == null) {
        try {
            capabilities = whilePluginLoads(clock) { client.sessionGroupsCapabilities(server) }
        } catch (error: OpenCodeClientError) {
            if (!error.isMissingRoute) return SharedGroupsReadResult(GroupSync.Support.Unknown, error = error)
            capabilitiesMissing = true
        }
    }
    val hierarchy = capabilities?.supportsHierarchy == true
    val snapshot = try {
        whilePluginLoads(clock) { if (hierarchy) client.sessionGroupsHierarchy(server) else client.sessionGroups(server) }
    } catch (error: OpenCodeClientError) {
        if (capabilitiesMissing && error.isMissingRoute) return SharedGroupsReadResult(GroupSync.Support.Unavailable)
        val support = capabilities?.let { GroupSync.Support.Available(it) } ?: GroupSync.Support.Unknown
        return SharedGroupsReadResult(support, error = error)
    }
    return SharedGroupsReadResult(GroupSync.Support.Available(capabilities ?: OpenCodeSessionGroupsCapabilities.original), snapshot)
}

private fun readFinished(state: State, read: SharedGroupsReadResult): State {
    // A read sharing stopped under was cancelled with it.
    if (!state.groupSync.isReading) return state
    var s = state.withSync { sync ->
        sync.copy(isReading = false, support = if (read.support == GroupSync.Support.Unknown) sync.support else read.support)
    }
    val snapshot = read.snapshot
    val shared = s.sharedGroups
    if (snapshot != null) {
        if (shared != null) s = s.updatingShared { it.applying(snapshot) }
        s = s.withSync { it.copy(isOffline = false, needsRead = false, summaryDue = true) }
    } else if (shared != null) {
        AppLog.app.notice { "Shared groups: read failed (${read.error?.logSummary ?: "no plugin"}); keeping revision ${shared.revision}" }
        s = s.withSync { it.copy(isOffline = true) }
        // Nothing queued may fall back to the phone: the edits are dropped and said so.
        if (s.groupSync.queue.isNotEmpty()) s = failingQueue(s, localized(R.string.drawer_groups_error_unreachable))
    }
    when (s.groupSync.prompt) {
        GroupSharingPrompt.Checking -> {
            val capabilities = s.groupSync.capabilities
            s = if (snapshot != null && capabilities != null) {
                val local = s.sessionGroups.groups(s.server.persistenceID).size
                val canImport = snapshot.revision == 0L && snapshot.groups.isEmpty() && local > 0 && capabilities.supports("import")
                s.withSync { it.copy(prompt = GroupSharingPrompt.Share(snapshot, local, canImport)) }
            } else {
                val detail = if (read.support == GroupSync.Support.Unavailable) R.string.drawer_groups_error_no_plugin else R.string.drawer_groups_error_unreachable
                showingGroupsError(s.withSync { it.copy(prompt = null) }, localized(detail))
            }
        }
        else -> Unit
    }
    return s
}

/** Sends the next queued write once nothing else is in flight; run after every action (`HomeFeatureCore`). */
internal fun HomeFeature.drainingGroupWrites(state: State): HomeReduced {
    val shared = state.sharedGroups ?: return Reduced(state)
    var s = state.promotingWaitingWrites()
    val sync = s.groupSync
    if (sync.isWriting || sync.isReading || sync.queue.isEmpty()) return Reduced(s)
    val capabilities = sync.capabilities
    if (sync.needsRead || capabilities == null) return readingGroups(s)
    val serverID = s.server.persistenceID
    val current = SessionGroups(mapOf(serverID to shared.groups))
    val hierarchy = capabilities.supportsHierarchy
    var queue = sync.queue
    while (queue.isNotEmpty()) {
        val head = queue.first()
        val write = head.edit.write(current, serverID, hierarchy)
        if (write == null) {
            queue = queue.drop(1)
            continue
        }
        val supported = write is OpenCodeSessionGroupsWrite.Hierarchy || write.method == "create" || write.method == "assign" || capabilities.supports(write.method)
        if (!supported) {
            AppLog.app.notice { "Shared groups: ${write.method} unsupported; dropped" }
            queue = queue.drop(1)
            continue
        }
        s = s.withSync { it.copy(queue = queue, isWriting = true) }
        val server = s.server
        val revision = shared.revision
        val operationID = if (capabilities.operationIDs || write is OpenCodeSessionGroupsWrite.Hierarchy) head.operationID else null
        val client = dependency(OpenCodeClient)
        return Reduced(
            s,
            Effect.run<Action> { send ->
                val result = try {
                    Action.SharedGroupsWritten(server.persistenceID, head.operationID, snapshot = client.writeSessionGroups(server, write, revision, operationID))
                } catch (error: OpenCodeClientError) {
                    Action.SharedGroupsWritten(server.persistenceID, head.operationID, error = error)
                }
                send(result)
            }.cancellable(HomeFeature.CancelID.SharedGroupsWrite),
        )
    }
    return Reduced(s.withSync { it.copy(queue = queue) })
}

/** Unsent sessions' assignments join the queue once the server lists the session. */
private fun State.promotingWaitingWrites(): State {
    val waiting = groupSync.waiting
    if (waiting.isEmpty()) return this
    val (ready, still) = waiting.partition { write -> !waitsForSession((write.edit as GroupEdit.Assign).sessionID) }
    if (ready.isEmpty()) return this
    return withSync { it.copy(queue = it.queue + ready, waiting = still) }
}

private fun HomeFeature.writeFinished(state: State, action: Action.SharedGroupsWritten): HomeReduced {
    // An answer to a write this run no longer waits for (sharing stopped and started again) changes nothing.
    val head = state.groupSync.queue.firstOrNull()
    if (!state.groupSync.isWriting || head == null || head.operationID != action.operationID) return Reduced(state)
    var s = state.withSync { it.copy(isWriting = false, queue = it.queue.drop(1)) }
    val snapshot = action.snapshot
    if (snapshot != null) {
        val before = s.sharedGroups?.groups.orEmpty()
        val created = (head.edit as? GroupEdit.Create)?.groupID
        s = s.updatingShared { it.applying(snapshot, keepingCollapsed = setOfNotNull(created)) }
        s = s.withSync { it.copy(isOffline = false) }
        when (val edit = head.edit) {
            is GroupEdit.Create -> s = adoptingCreatedGroup(s, edit, before, snapshot)
            is GroupEdit.Import -> {
                AppLog.app.info { "Shared groups: imported ${edit.pending.groups.size} groups, revision ${snapshot.revision}" }
                // A read that landed while the import was in flight dropped IDs the server didn't have yet.
                val serverID = s.server.persistenceID
                val ids = snapshot.groups.mapTo(HashSet()) { it.id }
                val collapsed = s.sessionGroups.groups(serverID).filter { it.isCollapsed && it.id in ids }.map { it.id }
                if (collapsed.isNotEmpty()) s = s.updatingShared { it.copy(collapsedIDs = it.collapsedIDs + collapsed) }
                dependency(HapticsClient).play(Haptic.success)
                if (s.groupSync.capabilities?.supportsHierarchy == true) {
                    // The import's answer is the flat shape: read the hierarchy, then link the subgroups it carried.
                    val links = edit.pending.groups.filter { it.parentID != null && it.id in ids }
                    s = s.editingGroups(links.map { GroupEdit.Reparent(it.id, it.parentID) })
                    return readingGroups(s.withSync { it.copy(needsRead = true) })
                }
            }
            else -> Unit
        }
        return Reduced(s)
    }
    val error = action.error ?: return Reduced(s)
    val type = error.rpcError?.type
    AppLog.app.notice { "Shared groups: ${head.edit::class.simpleName} failed (${type ?: error.logSummary})" }
    val isImport = head.edit is GroupEdit.Import
    val unreachable = type == null || type == OpenCodeRpcError.UNAVAILABLE
    if (unreachable) {
        // An import that may have landed keeps its operation ID, so trying again can't import twice.
        s = s.withSync { it.copy(isOffline = true, needsRead = true) }
        val detail = if (isImport) R.string.drawer_groups_error_import_unreachable else R.string.drawer_groups_error_unreachable
        return Reduced(failingQueue(s, localized(detail)))
    }
    if (isImport) s = s.updatingShared { it.copy(pendingImport = null) }
    s = droppingDependents(s, head.edit)
    val detail = when (type) {
        OpenCodeRpcError.REVISION_CONFLICT ->
            if (isImport) R.string.drawer_groups_error_import_refused else R.string.drawer_groups_error_conflict
        OpenCodeRpcError.IMPORT_REFUSED -> R.string.drawer_groups_error_import_refused
        OpenCodeRpcError.GROUP_NOT_FOUND -> R.string.drawer_groups_error_group_gone
        OpenCodeRpcError.SESSION_NOT_FOUND ->
            if (isImport) R.string.drawer_groups_error_import_session_gone else R.string.drawer_groups_error_session_gone
        else -> R.string.drawer_groups_error_rejected
    }
    s = s.withSync { it.copy(needsRead = true) }
    return Reduced(showingGroupsError(s, localized(detail)))
}

/**
 * The server named the group [edit] made: the edits, collapse, draft and drawer path that used the phone's ID for
 * it move to the server's. A create answered by a matching retry still finds it, as the newest group
 * the snapshot before it didn't have.
 */
private fun adoptingCreatedGroup(
    state: State,
    edit: GroupEdit.Create,
    before: List<SessionGroup>,
    snapshot: OpenCodeSessionGroupsSnapshot,
): State {
    val known = before.mapTo(HashSet()) { it.id }
    val name = SessionGroups.normalizedName(edit.name)
    val created = snapshot.groups.lastOrNull { it.id !in known && it.name == name } ?: snapshot.groups.lastOrNull { it.id !in known }
    val from = edit.groupID
    if (created == null) {
        val s = if (from in state.sharedGroups?.collapsedIDs.orEmpty()) state.updatingShared { it.copy(collapsedIDs = it.collapsedIDs - from) } else state
        return droppingDependents(s, edit)
    }
    val to = created.id
    var s = state.withSync { sync ->
        sync.copy(
            queue = sync.queue.map { it.copy(edit = it.edit.renamingGroup(from, to)) },
            waiting = sync.waiting.map { it.copy(edit = it.edit.renamingGroup(from, to)) },
        )
    }
    if (from in s.sharedGroups?.collapsedIDs.orEmpty()) s = s.updatingShared { it.copy(collapsedIDs = it.collapsedIDs - from + to) }
    val draft = s.draftGroup
    if (draft != null && draft.groupID == from) s = s.copy(draftGroup = draft.copy(groupID = to))
    val path = s.drawerPath.renamingGroup(from, to)
    if (path != s.drawerPath) s = s.copy(drawerPath = path)
    return s
}

/** A create that failed takes the edits that needed its group with it. */
private fun droppingDependents(state: State, edit: GroupEdit): State {
    if (edit !is GroupEdit.Create) return state
    val id = edit.groupID
    return state.withSync { sync ->
        sync.copy(queue = sync.queue.filterNot { it.edit.dependsOn(id) }, waiting = sync.waiting.filterNot { it.edit.dependsOn(id) })
    }
}

/** Drops every queued edit (the drawer returns to the last snapshot) and says why. */
private fun failingQueue(state: State, detail: String): State {
    val s = state.withSync { it.copy(queue = emptyList()) }
    return showingGroupsError(s, detail)
}

private fun showingGroupsError(state: State, detail: String): State {
    dependency(HapticsClient).play(Haptic.error)
    return state.copy(groupsError = DrawerFailure(DrawerFailure.Action.groups, sessionTitle = "", detail = detail))
}

/** Clears a groups notice a few seconds after it shows, as a failed delete's does. */
internal fun HomeFeature.clearingGroupsError(before: State, after: State): Effect<Action> {
    if (after.groupsError == null || after.groupsError == before.groupsError) return Effect.none()
    val clock = dependency(Clock)
    return Effect.run<Action> { send ->
        clock.sleep(5.seconds)
        send(Action.ClearGroupsError)
    }.cancellable(HomeFeature.CancelID.GroupsError, cancelInFlight = true)
}

/**
 * Copies this phone's groups for the server into its fresh store, once. A retry after a lost answer
 * resends the same payload under the same operation ID; otherwise the payload is built now, leaving
 * out sessions the server no longer has (an import naming one is refused whole).
 */
private fun HomeFeature.importingLocalGroups(state: State): HomeReduced {
    val serverID = state.server.persistenceID
    val pending = state.sharedGroups?.pendingImport
    if (pending != null) {
        return Reduced(state.withSync { it.copy(queue = it.queue + GroupWrite(pending.operationID, GroupEdit.Import(pending))) })
    }
    val seen = HashSet<String>()
    val groups = state.sessionGroups.groups(serverID).mapNotNull { group ->
        val name = SessionGroups.normalizedName(group.name) ?: return@mapNotNull null
        SessionGroup(id = group.id, name = name, sessionIDs = group.sessionIDs.filter { seen.add(it) }, parentID = group.parentID)
    }
    if (groups.isEmpty()) return Reduced(state)
    val listed = state.sessions.mapTo(HashSet()) { it.id }
    val unlisted = groups.flatMap { it.sessionIDs }.filter { it !in listed }
    val server = state.server
    val client = dependency(OpenCodeClient)
    return Reduced(
        state,
        Effect.run<Action> { send ->
            val gone = HashSet<String>()
            for (sessionID in unlisted.take(importLookupLimit)) {
                try {
                    client.getSession(server, sessionID)
                } catch (error: OpenCodeClientError) {
                    if (error.statusCode == 404) gone += sessionID
                }
            }
            send(Action.SharedGroupsImportPrepared(serverID, groups.map { it.copy(sessionIDs = it.sessionIDs.filter { id -> id !in gone }) }))
        }.cancellable(HomeFeature.CancelID.SharedGroupsImport, cancelInFlight = true),
    )
}

/** Sessions outside the loaded list looked up before an import; past this the import sends them as they are. */
private const val importLookupLimit = 50

/**
 * Asks a shared hierarchy server for its activity summary while the drawer orders groups by Last
 * Activity: once the groups are settled (nothing read or written in flight), after a refresh read them
 * again, or when the snapshot reaches a revision not yet asked about. Runs after every action.
 */
internal fun HomeFeature.readingGroupSummaryIfDue(state: State): HomeReduced {
    if (!state.groupsByActivity || !state.isConnected) return Reduced(state)
    val shared = state.sharedGroups ?: return Reduced(state)
    val sync = state.groupSync
    if (sync.capabilities?.supportsHierarchy != true) return Reduced(state)
    if (sync.isReadingSummary || sync.isReading || sync.isWriting || sync.needsRead || sync.queue.isNotEmpty()) return Reduced(state)
    if (!sync.summaryDue && sync.summaryAskedRevision == shared.revision) return Reduced(state)
    val request = sync.summaryRequest + 1
    val server = state.server
    val client = dependency(OpenCodeClient)
    val s = state.withSync {
        it.copy(summaryRequest = request, isReadingSummary = true, summaryDue = false, summaryAskedRevision = shared.revision)
    }
    return Reduced(
        s,
        Effect.run<Action> { send ->
            val result = try {
                Action.SharedGroupsSummaryRead(server.persistenceID, request, summary = client.sessionGroupsSummary(server))
            } catch (error: OpenCodeClientError) {
                Action.SharedGroupsSummaryRead(server.persistenceID, request, error = error)
            }
            send(result)
        }.cancellable(HomeFeature.CancelID.SharedGroupsSummary),
    )
}

/**
 * Keeps a summary only for the revision the drawer shows. A newer one means the groups changed: they
 * are read again, which asks again. Anything else leaves the last verified summary in place, marked stale.
 */
private fun HomeFeature.summaryFinished(state: State, action: Action.SharedGroupsSummaryRead): HomeReduced {
    if (action.request != state.groupSync.summaryRequest || !state.groupSync.isReadingSummary) return Reduced(state)
    val s = state.withSync { it.copy(isReadingSummary = false) }
    val shared = s.sharedGroups ?: return Reduced(s)
    val summary = action.summary
    if (summary == null) {
        AppLog.app.notice { "Shared groups: summary failed (${action.error?.logSummary}); keeping revision ${shared.summary?.revision}" }
        return Reduced(s.withSync { it.copy(summaryFailed = true) })
    }
    return when {
        summary.revision == shared.revision ->
            Reduced(s.updatingShared { it.copy(summary = summary) }.withSync { it.copy(summaryFailed = false) })
        summary.revision > shared.revision -> readingGroups(s.withSync { it.copy(summaryFailed = true) })
        else -> Reduced(s.withSync { it.copy(summaryFailed = true) })
    }
}
