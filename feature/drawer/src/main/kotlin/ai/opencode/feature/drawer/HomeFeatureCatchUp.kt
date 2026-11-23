package ai.opencode.feature.drawer

import ai.opencode.feature.session.*
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.map
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.server.OpenCodeClient

/*
 * Home's half of Catch Up. `CatchUpFeature` owns the queue and its transient records; Home opens
 * each card as the session on screen, marks read, and hands back to the drawer it was opened from.
 */


internal fun HomeFeature.reduceCatchUp(state: State, action: CatchUpFeature.Action): HomeReduced {
    when (action) {
        CatchUpFeature.Action.Tapped -> {
            val queue = state.catchUpSessions.map { it.id }
            if (queue.isEmpty()) return Reduced(state)
            val detail = state.sessionDetail
            val returnSessionID = if (detail?.isDraft == false) detail.familyRootID else null
            val returnComposerSessionID =
                if (detail?.isDraft == false && detail.session?.id != returnSessionID) detail.session?.id else null
            // Catch Up takes over an open drawer without it arriving shut.
            val s = state.copy(isDrawerOpen = false).withCatchUp(
                CatchUpFeature.Stack(
                    queue = queue,
                    returnSessionID = returnSessionID,
                    returnComposerSessionID = returnComposerSessionID,
                    returnComposer = detail?.sessionComposer ?: ai.opencode.feature.composer.DraftState(),
                ),
            )
            val opened = openCatchUpCard(s)
            return Reduced(opened.state, Effect.merge(Effect.cancel(HomeFeature.CancelID.SelectedTranscript), opened.effect))
        }

        is CatchUpFeature.Action.CardDismissed -> {
            val stack = state.catchUp ?: return lateCardDismissed(state, action)
            if (stack.queue.firstOrNull() != action.sessionID) return Reduced(state)
            var session = state.sessions.firstOrNull { it.id == action.sessionID } ?: return Reduced(state)
            // The card may have observed a newer idle watermark than the list.
            val observed = state.sessionDetail?.session
            if (observed != null && observed.id == action.sessionID && (observed.idleMilliseconds ?: -1) > (session.idleMilliseconds ?: -1)) {
                session = session.copy(idleMilliseconds = observed.idleMilliseconds)
            }
            val seenBeforeCard = if (state.sessionDetail?.session?.id == action.sessionID) state.sessionDetail?.catchUpOpeningSeen else null
            val marksRead = action.disposition == CatchUpFeature.Disposition.markRead
            var s = (if (marksRead) state.notingFollowUpFromCatchUp(session) else state).withCatchUp(
                stack.dismissFront(session, action.disposition, neededAttention = action.sessionID in state.attentionSessionIDs),
            )
            val opened = openCatchUpCard(s)
            s = opened.state
            // The card's flight plays its own haptic (the stack controller).
            if (action.disposition != CatchUpFeature.Disposition.markRead) {
                // Kept unread: what the card showed is still new next time.
                if (seenBeforeCard != null) {
                    s.sessionSnapshots[action.sessionID]?.let { snapshot ->
                        s = s.copy(sessionSnapshots = s.sessionSnapshots + (action.sessionID to snapshot.copy(seenItemIDs = seenBeforeCard)))
                    }
                    if (seenBeforeCard.isNotEmpty()) s = s.copy(unopenedSeen = s.unopenedSeen + (action.sessionID to seenBeforeCard))
                }
                return Reduced(s, opened.effect)
            }
            s = s.markingReadLocally(session).clearingUnseenBaseline(session.id)
            return Reduced(s, Effect.merge(opened.effect, acknowledgeView(session, s.server)))
        }

        CatchUpFeature.Action.UndoTapped -> {
            val (card, catchUpState) = state.catchUpState.consumeLastUndone()
            if (card == null) return Reduced(state)
            var s = state.copy(catchUpState = catchUpState)
            if (card.disposition == CatchUpFeature.Disposition.markRead) {
                s.sessions.firstOrNull { it.id == card.sessionID }?.let { s = s.markingUnreadLocally(it) }
            }
            // A receipt still in flight would otherwise clear the restored unread.
            val opened = openCatchUpCard(s)
            return Reduced(opened.state, Effect.merge(opened.effect, Effect.cancel(HomeFeature.CancelID.ViewReceipt(card.sessionID))))
        }

        CatchUpFeature.Action.ExitTapped -> {
            if (state.catchUpState.exitingStack == null && state.catchUp == null) return Reduced(state)
            return handBackFromCatchUp(state)
        }

        is CatchUpFeature.Action.Finished -> {
            if (state.catchUpState.exitingStack == null) return Reduced(state)
            // A card kept unread or a request still waiting: Catch Up closes all the same, but the
            // row isn't all caught up.
            var s = state
            if (s.catchUpSessions.isNotEmpty()) s = s.copy(catchUpState = s.catchUpState.copy(justFinished = false))
            return handBackFromCatchUp(s)
        }

        CatchUpFeature.Action.OpenTapped -> {
            val stack = state.catchUp ?: return Reduced(state)
            val detail = state.sessionDetail ?: return Reduced(state)
            val rootID = detail.familyRootID ?: return Reduced(state)
            if (stack.queue.firstOrNull() != rootID) return Reduced(state)
            var root = state.sessions.firstOrNull { it.id == rootID } ?: return Reduced(state)
            // The card may have observed a newer idle watermark than the list.
            val observed = if (detail.session?.id == rootID) detail.session else detail.familySessions.firstOrNull { it.id == rootID }
            if (observed != null && (observed.idleMilliseconds ?: -1) > (root.idleMilliseconds ?: -1)) {
                root = root.copy(idleMilliseconds = observed.idleMilliseconds)
            }
            // The card already is the session: keep it, its draft, and any subagent the reader
            // opened, and let it read as any open session.
            var s = state.leavingCatchUp()
            val viewed = openViewedSession(s, root, cachedItems = null, opensDetail = false)
            s = viewed.state.promotingCatchUpDetail().updatingDetail { it.markViewed(root) }
            val receipts = mutableListOf(viewed.effect)
            // A subagent on screen reads too, as it would had it been picked.
            val shown = detail.session
            val idle = shown?.idleMilliseconds
            if (shown != null && shown.id != rootID && idle != null && idle > (shown.viewedMilliseconds ?: -1)) {
                s = s.updatingDetail { it.markViewed(shown) }
                receipts += acknowledgeShownSubagent(shown.id, idle, s.server)
            }
            return Reduced(s, Effect.merge(receipts))
        }

        CatchUpFeature.Action.MarkAllReadTapped -> {
            // Waiting sessions keep their unread state as well as their request: what led up to the
            // question is still unseen.
            val status = state.statusSnapshot()
            val unread = state.sessions.filter { status.indicator(it) == State.Indicator.unread }
            if (unread.isEmpty()) return Reduced(state)
            dependency(HapticsClient).play(Haptic.selection)
            val receipts = ArrayList<Pair<String, Long>>()
            val backups = LinkedHashMap<String, CatchUpFeature.ReadBackup>()
            var s = state
            for (session in unread) {
                val idle = session.idleMilliseconds
                if (idle != null) {
                    backups[session.id] = s.readBackup(session, idle)
                    receipts += session.id to idle
                }
                s = s.markingReadLocally(session).clearingUnseenBaseline(session.id)
            }
            s = s.withMarkAllReceipts(s.markAllReceipts + backups).reconcilingCatchUp()
            return Reduced(
                s,
                CatchUpFeature.acknowledgeViews(receipts, s.server, dependency(OpenCodeClient)).map { Action.CatchUp(it) },
            )
        }

        is CatchUpFeature.Action.MarkAllReceiptResponse -> {
            val (resolved, catchUpState) = state.catchUpState.consumeLastResolvedReceipt()
            if (resolved == null) return Reduced(state)
            val s = state.copy(catchUpState = catchUpState)
            return Reduced(
                if (resolved.succeeded) s.notingViewAcknowledged(resolved.sessionID, resolved.backup.idle)
                else s.restoringRead(resolved.sessionID, resolved.backup).reconcilingCatchUp(),
            )
        }

        else -> return Reduced(state)
    }
}

/** Back to the drawer Catch Up was opened from (a sidebar returns on its own). */
private fun HomeFeature.handBackFromCatchUp(state: State): HomeReduced {
    val previousID = state.sessionDetail?.session?.id
    var s = state.returningFromCatchUp()
    if (s.showsDrawerLabel) s = s.copy(isDrawerOpen = true)
    val openedID = s.sessionDetail?.session?.id
    if (openedID == null || openedID == previousID) return Reduced(s)
    return cancelBackgroundTranscriptReads(s, setOf(openedID))
}

private fun HomeFeature.openCatchUpCard(state: State): HomeReduced {
    val (s, changed) = state.openingCatchUpCard()
    if (!changed) return Reduced(s)
    return cancelBackgroundTranscriptReads(s, setOfNotNull(s.sessionDetail?.session?.id))
}

/** A card still flying off when something else closed Catch Up: Mark as Read still counts. */
private fun HomeFeature.lateCardDismissed(state: State, action: CatchUpFeature.Action.CardDismissed): HomeReduced {
    if (action.disposition != CatchUpFeature.Disposition.markRead) return Reduced(state)
    val session = state.sessions.firstOrNull { it.id == action.sessionID } ?: return Reduced(state)
    val s = state.markingReadLocally(session).clearingUnseenBaseline(session.id)
    return Reduced(s, acknowledgeView(session, s.server))
}
