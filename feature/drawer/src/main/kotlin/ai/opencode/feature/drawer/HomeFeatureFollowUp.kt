package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.followup.FollowUp
import ai.opencode.feature.drawer.followup.FollowUpSnooze
import ai.opencode.feature.drawer.followup.FollowUpTimeZone
import ai.opencode.feature.drawer.followup.FollowUps
import ai.opencode.feature.drawer.followup.completedReplyID
import ai.opencode.feature.drawer.groups.DrawerSharedKeys
import ai.opencode.feature.session.watchesTranscript
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable

/*
 * Follow Up (`followup/FollowUps.kt`) as Home keeps it. A reply counts as seen only when an
 * authoritative read of the root on screen shows it finished, and the reader hadn't read that run
 * before the session came on screen ([FollowUpWatch]). Drawer, Catch Up and Mark All reads that
 * never showed the reply don't count; Catch Up's Mark as Read does, since its card shows the reply.
 */

/**
 * A root on screen and the idle watermark it had been read up to when it opened. Kept for this run
 * only, by `serverID|sessionID` in [DrawerSharedKeys.followUpWatches], not in [State]: it is
 * bookkeeping for the screen, never shown.
 */
@Serializable
data class FollowUpWatch(
    val sessionID: String,
    /** A reply whose run ended at or before this was read before the session came on screen. */
    val readIdle: Long,
    /** A reply an authoritative read brought while the transcript was covered, noted once it shows. */
    val unshown: ShownReply? = null,
)

@Serializable
data class ShownReply(val replyID: String, val idle: Long)

/** The last Done or Snooze, for its Undo. */
data class FollowUpUndo(val kind: Kind, val changes: List<Change>, val generation: Int) {
    enum class Kind { done, snooze }

    data class Change(val key: String, val before: FollowUp, val after: FollowUp)
}

sealed interface FollowUpAction {
    data class DoneTapped(val sessionIDs: List<String>) : FollowUpAction
    data class SnoozeTapped(val sessionIDs: List<String>, val snooze: FollowUpSnooze) : FollowUpAction
    data class BringBackTapped(val sessionID: String) : FollowUpAction
    data object UndoTapped : FollowUpAction
    data class UndoExpired(val generation: Int) : FollowUpAction
    /** The drawer, the Follow Up page or Catch Up opened, or the app came back: snoozes that are due come back. */
    data object DueCheck : FollowUpAction
    /** The Follow Up page showed: its sessions the loaded pages don't hold are looked up for their titles. */
    data object PageShown : FollowUpAction
    /** A Follow Up row was tapped: a session only looked up joins the list so it can open. */
    data class RowTapped(val sessionID: String) : FollowUpAction
}

/** `$followUps.withLock { … }`. */
internal fun State.updatingFollowUps(transform: (FollowUps) -> FollowUps): State {
    val reference = shared(DrawerSharedKeys.followUps)
    reference.update(transform)
    val live = reference.value
    return if (live == followUps) this else copy(followUps = live)
}

/** The root the main screen shows, while it is a session (not a draft). */
private val State.followUpRootID: String?
    get() {
        val detail = sessionDetail ?: return null
        val session = detail.session ?: return null
        return detail.familyRootID ?: session.id.takeIf { session.parentID == null }
    }

/** How far [sessionID] had been read: its idle watermark when nothing is unread, else what the server last saw read. */
private fun State.followUpReadIdle(sessionID: String): Long? {
    // A tap read the session before its screen opened (an uncached transcript): how it stood then.
    pendingOpenReadState[sessionID]?.let { pending -> if (pending.wasUnread) return pending.viewedMilliseconds ?: 0 }
    val session = sessions.firstOrNull { it.id == sessionID }
        ?: sessionDetail?.session?.takeIf { it.id == sessionID }
        ?: return null
    return if (hasUnreadWatermark(session)) session.viewedMilliseconds ?: 0 else session.idleMilliseconds ?: 0
}

/** The main screen's root's watch, if it has one. Reads a shared key: call it from a reducer. */
val State.followUpWatch: FollowUpWatch?
    get() {
        val rootID = followUpRootID ?: return null
        return shared(DrawerSharedKeys.followUpWatches).value[sessionKey(rootID)]
    }

private fun State.settingFollowUpWatch(watch: FollowUpWatch) {
    val key = sessionKey(watch.sessionID)
    val reference = shared(DrawerSharedKeys.followUpWatches)
    if (reference.value[key] == watch) return
    reference.update { watches -> (watches - key).entries.toList().takeLast(followUpWatchLimit - 1).associate { it.toPair() } + (key to watch) }
}

private const val followUpWatchLimit = 16

/**
 * Runs after every action: starts a watch when the action changed the main screen's root (or the
 * root has none yet, as after a cold launch), measured against the state before it, which may also
 * have marked it read; and notes a reply that arrived while the transcript was covered once it shows.
 */
internal fun State.followingFollowUpWatch(before: State): State {
    val rootID = followUpRootID ?: return this
    var watch = followUpWatch
    if (watch == null || rootID != before.followUpRootID) {
        watch = FollowUpWatch(rootID, before.followUpReadIdle(rootID) ?: followUpReadIdle(rootID) ?: 0)
        settingFollowUpWatch(watch)
    }
    val unshown = watch.unshown ?: return this
    val detail = sessionDetail ?: return this
    if (!detail.readsOnView || !detail.watchesTranscript || detail.session?.id != rootID) return this
    settingFollowUpWatch(watch.copy(unshown = null))
    return notingFollowUp(rootID, unshown)
}

/** An authoritative read of [sessionID] on the main screen: a finished reply the reader hadn't read becomes a follow-up. */
internal fun State.notingFollowUpFromOpenDetail(sessionID: String, items: List<OpenCodeTranscriptItem>): State {
    val watch = followUpWatch ?: return this
    val detail = sessionDetail ?: return this
    if (watch.sessionID != sessionID || detail.session?.id != sessionID || !detail.readsOnView) return this
    val reply = shownReply(sessionID, items, watch.readIdle) ?: return this
    if (!detail.watchesTranscript) {
        settingFollowUpWatch(watch.copy(unshown = reply))
        return this
    }
    return notingFollowUp(sessionID, reply)
}

/** Catch Up's Mark as Read: the card showed the reply. Call before the read is recorded. */
internal fun State.notingFollowUpFromCatchUp(session: OpenCodeSession): State {
    val detail = sessionDetail?.takeIf { it.session?.id == session.id }
    val items = detail?.items ?: transcriptCache[session.id] ?: return this
    val readIdle = followUpReadIdle(session.id) ?: return this
    val reply = shownReply(session.id, items, readIdle, idle = session.idleMilliseconds) ?: return this
    return notingFollowUp(session.id, reply)
}

private fun State.shownReply(sessionID: String, items: List<OpenCodeTranscriptItem>, readIdle: Long, idle: Long? = null): ShownReply? {
    val listed = sessions.firstOrNull { it.id == sessionID }
    val shown = sessionDetail?.session?.takeIf { it.id == sessionID }
    val session = listed ?: shown ?: return null
    if (session.parentID != null || session.isArchived || sessionID in busySessionIDs) return null
    val runIdle = maxOf(idle ?: 0, listed?.idleMilliseconds ?: 0, shown?.idleMilliseconds ?: 0)
    if (runIdle <= 0 || runIdle <= readIdle) return null
    val replyID = items.completedReplyID() ?: return null
    return ShownReply(replyID, runIdle)
}

private fun State.notingFollowUp(sessionID: String, reply: ShownReply): State {
    val now = dependency(DateClient).now()
    return updatingFollowUps { it.noting(sessionKey(sessionID), reply.replyID, reply.idle, now) }
}

/** The server took the reader's message in [sessionID]: its follow-up is answered. */
internal fun State.notingFollowUpReplySent(sessionID: String): State {
    val key = sessionKey(sessionID)
    if (followUps[key]?.isOpen != true) return this
    return updatingFollowUps { it.handling(key, FollowUp.Status.replied) }
}

internal fun State.notingFollowUpArchived(sessionID: String): State {
    val key = sessionKey(sessionID)
    if (followUps[key]?.isOpen != true) return this
    return updatingFollowUps { it.handling(key, FollowUp.Status.archived) }
}

internal fun State.forgettingFollowUp(sessionID: String): State {
    val key = sessionKey(sessionID)
    if (followUps[key] == null) return this
    return updatingFollowUps { it.removing(key) }
}

internal fun State.wakingDueFollowUps(): State {
    if (followUps.entries.values.none { it.status == FollowUp.Status.snoozed }) return this
    val now = dependency(DateClient).now()
    return updatingFollowUps { it.wakingDue(now) }
}

/** This server's open follow-ups by session ID, archived sessions left out. */
val State.openFollowUps: Map<String, FollowUp>
    get() {
        val open = followUps.open(server.persistenceID)
        if (open.isEmpty()) return open
        val archiving = inboxSidebarStatus.archivedIDs
        return open.filterKeys { id -> id !in archiving && sessions.firstOrNull { it.id == id }?.isArchived != true }
    }

/** This server's sessions with a pending (not snoozed) follow-up. */
val State.pendingFollowUpIDs: Set<String>
    get() = openFollowUps.filterValues { it.status == FollowUp.Status.pending }.keys

/** One Follow Up page row. */
data class FollowUpRow(
    val id: String,
    /** Null until a session the loaded pages don't hold has been looked up. */
    val session: OpenCodeSession?,
    val entry: FollowUp,
)

/** The page's sections: pending, newest reply first; then snoozed, soonest first. */
data class FollowUpRows(val pending: List<FollowUpRow>, val snoozed: List<FollowUpRow>) {
    val isEmpty: Boolean get() = pending.isEmpty() && snoozed.isEmpty()
}

fun State.followUpRows(): FollowUpRows {
    val pending = ArrayList<FollowUpRow>()
    val snoozed = ArrayList<FollowUpRow>()
    for ((id, entry) in openFollowUps) {
        val row = FollowUpRow(id, linkedSession(id), entry)
        if (entry.status == FollowUp.Status.snoozed) snoozed += row else pending += row
    }
    pending.sortByDescending { it.entry.seenAt }
    snoozed.sortBy { it.entry.snoozedUntil ?: Long.MAX_VALUE }
    return FollowUpRows(pending, snoozed)
}

internal fun HomeFeature.reduceFollowUp(state: State, action: FollowUpAction): HomeReduced {
    when (action) {
        is FollowUpAction.DoneTapped -> {
            val changed = state.changingFollowUps(action.sessionIDs) { key, entries -> entries.handling(key, FollowUp.Status.done) }
                ?: return Reduced(state)
            dependency(HapticsClient).play(Haptic.success)
            return offeringUndo(changed.first, FollowUpUndo.Kind.done, changed.second)
        }

        is FollowUpAction.SnoozeTapped -> {
            val now = dependency(DateClient).now()
            val until = action.snooze.until(now, dependency(FollowUpTimeZone).zone()) ?: return Reduced(state)
            val changed = state.changingFollowUps(action.sessionIDs) { key, entries -> entries.snoozing(key, until) }
                ?: return Reduced(state)
            dependency(HapticsClient).play(Haptic.confirm)
            return offeringUndo(changed.first, FollowUpUndo.Kind.snooze, changed.second)
        }

        is FollowUpAction.BringBackTapped -> {
            val key = state.sessionKey(action.sessionID)
            return Reduced(state.updatingFollowUps { it.bringingBack(key) })
        }

        FollowUpAction.UndoTapped -> {
            val undo = state.followUpUndo ?: return Reduced(state)
            var s = state.copy(followUpUndo = null)
            for (change in undo.changes) s = s.updatingFollowUps { it.restoring(change.key, expected = change.after, restored = change.before) }
            return Reduced(s, Effect.cancel(HomeFeature.CancelID.FollowUpUndo))
        }

        is FollowUpAction.UndoExpired ->
            return Reduced(if (state.followUpUndo?.generation == action.generation) state.copy(followUpUndo = null) else state)

        FollowUpAction.DueCheck -> return Reduced(state.wakingDueFollowUps())

        FollowUpAction.PageShown -> {
            val s = state.wakingDueFollowUps()
            val unknown = s.openFollowUps.keys.filter { s.linkedSession(it) == null }
            return if (unknown.isEmpty()) Reduced(s) else resolveSessionLinks(s, unknown)
        }

        is FollowUpAction.RowTapped -> {
            if (state.sessions.any { it.id == action.sessionID }) return Reduced(state)
            val session = state.linkedSessions[action.sessionID] ?: return Reduced(state)
            return Reduced(state.copy(sessions = listOf(session) + state.sessions))
        }
    }
}

/** Applies [change] to each of [sessionIDs]' open follow-ups; null when none changed. */
private fun State.changingFollowUps(
    sessionIDs: List<String>,
    change: (String, FollowUps) -> FollowUps,
): Pair<State, List<FollowUpUndo.Change>>? {
    var s = this
    val changes = ArrayList<FollowUpUndo.Change>()
    for (id in sessionIDs.distinct()) {
        val key = s.sessionKey(id)
        val before = s.followUps[key] ?: continue
        if (!before.isOpen) continue
        s = s.updatingFollowUps { change(key, it) }
        val after = s.followUps[key] ?: continue
        if (after != before) changes += FollowUpUndo.Change(key, before, after)
    }
    return if (changes.isEmpty()) null else s to changes
}

private fun offeringUndo(state: State, kind: FollowUpUndo.Kind, changes: List<FollowUpUndo.Change>): HomeReduced {
    val generation = (state.followUpUndo?.generation ?: 0) + 1
    val clock = dependency(Clock)
    return Reduced(
        state.copy(followUpUndo = FollowUpUndo(kind, changes, generation)),
        Effect.run<HomeFeature.Action> { send ->
            clock.sleep(followUpUndoDuration)
            send(HomeFeature.Action.FollowUp(FollowUpAction.UndoExpired(generation)))
        }.cancellable(HomeFeature.CancelID.FollowUpUndo, cancelInFlight = true),
    )
}

internal val followUpUndoDuration = 5.seconds
