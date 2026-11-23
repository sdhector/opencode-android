package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.followup.FollowUp
import ai.opencode.feature.drawer.followup.FollowUpSnooze
import ai.opencode.feature.drawer.followup.FollowUpTimeZone
import ai.opencode.feature.drawer.followup.FollowUps
import ai.opencode.feature.drawer.followup.completedReplyID
import ai.opencode.feature.drawer.groups.DrawerSharedKeys
import ai.opencode.feature.drawer.groups.SessionGroup
import ai.opencode.feature.drawer.groups.SessionGroups
import ai.opencode.feature.drawer.list.DrawerListInputs
import ai.opencode.feature.drawer.list.DrawerListItem
import ai.opencode.feature.drawer.list.DrawerListModel
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptItem.AssistantMarkdown
import ai.opencode.server.model.OpenCodeTranscriptItem.UserText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Follow Up: what counts as a seen reply, how it's handled, snoozed and undone, and how the drawer shows it. */
class FollowUpTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val other = OpenCodeServer(url = "http://localhost:10", username = "", password = "")
    private val now: Instant = Instant.parse("2026-11-23T15:00:00Z")

    private fun session(id: String, idle: Long? = 2_000, viewed: Long? = 1_000, archived: Long? = null) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "p", directory = "/project",
        updatedAt = Instant.ofEpochSecond(10), idleMilliseconds = idle, viewedMilliseconds = viewed, archivedMilliseconds = archived,
    )

    private fun reply(id: String = "reply-1") = listOf(UserText("u-1", "question"), AssistantMarkdown(id, "answer"))

    private fun home(vararg sessions: OpenCodeSession, on: OpenCodeServer = server) =
        HomeFeature.State(on, OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions.toList(), activeSessionIDs = emptySet()))

    private fun CoroutineScope.scope(state: HomeFeature.State = home()) = TestStore(state, HomeFeature(), this) {
        this[Clock] = ImmediateClock()
        this[DateClient] = DateClient.constant(now)
        this[FollowUpTimeZone] = FollowUpTimeZone { ZoneId.of("America/Chicago") }
    }

    private fun HomeFeature.State.key(id: String) = sessionKey(id)

    /** One action that opens [session] on screen and reads it, as a drawer tap does. */
    private fun HomeFeature.State.opening(session: OpenCodeSession): HomeFeature.State =
        openingDetail(session).markingReadLocally(session).followingFollowUpWatch(before = this)

    private fun HomeFeature.State.visible(): HomeFeature.State =
        updatingDetail { it.copy(isVisible = true, isSceneActive = true, readsOnView = true) }.followingFollowUpWatch(before = this)

    private fun HomeFeature.State.shown(id: String, items: List<OpenCodeTranscriptItem>) = notingFollowUpFromOpenDetail(id, items)

    // region Reply identity

    @Test
    fun completedReplyIsTheLastFinishedAssistantText() {
        assertEquals("reply-1", reply().completedReplyID())
        assertEquals("info rows don't end a reply", "reply-1", (reply() + OpenCodeTranscriptItem.Info("info-1", "x")).completedReplyID())
        assertNull("still streaming", listOf(UserText("u", "q"), AssistantMarkdown("a", "partial", inProgress = true)).completedReplyID())
        assertNull("the reader spoke last", (reply() + UserText("u-2", "next")).completedReplyID())
        assertNull("ended on a tool call", (reply() + OpenCodeTranscriptItem.Tool("t", toolCall())).completedReplyID())
        assertNull("the run failed", (reply() + OpenCodeTranscriptItem.RunError("e", OpenCodeRunError(type = "api", message = "boom"))).completedReplyID())
        assertNull(emptyList<OpenCodeTranscriptItem>().completedReplyID())
    }

    private fun toolCall() = OpenCodeToolCall(
        callID = "t", name = "bash", status = OpenCodeToolCall.Status.Completed,
        input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), error = null, metadata = emptyMap(),
    )

    // endregion

    // region Seen on screen

    @Test
    fun anUnreadReplyOpenedAndShownBecomesPending() = runTest {
        val store = scope()
        store.withDependencies {
            val s = home(session("a")).opening(session("a")).visible().shown("a", reply())
            val entry = s.followUps[s.key("a")]
            assertEquals(FollowUp(replyID = "reply-1", idle = 2_000, seenAt = now.toEpochMilli()), entry)
            assertEquals(setOf("a"), s.pendingFollowUpIDs)
            assertFalse("read, not unread", s.isUnread(s.sessions.first()))
        }
    }

    @Test
    fun aSessionRestoredOnScreenAtLaunchIsWatchedFromItsFirstAction() = runTest {
        val store = scope()
        store.withDependencies {
            val launched = home(session("a")).openingDetail(session("a"))
            assertNull(launched.followUpWatch)
            val s = HomeFeature().reduce(launched, HomeFeature.Action.FollowUp(FollowUpAction.DueCheck)).state
            assertEquals(FollowUpWatch("a", readIdle = 1_000), s.followUpWatch)
            assertEquals("the watch isn't Home's state", launched, s)
            val shown = s.visible().shown("a", reply())
            assertEquals("reply-1", shown.followUps[shown.key("a")]?.replyID)
        }
    }

    @Test
    fun anAlreadyReadReplyIsNotBackfilled() = runTest {
        val store = scope()
        store.withDependencies {
            val read = session("a", idle = 2_000, viewed = 2_000)
            val s = home(read).opening(read).visible().shown("a", reply())
            assertTrue(s.followUps.entries.isEmpty())
            assertTrue("nothing is created at install for the history", home(read, session("b")).followUps.entries.isEmpty())
        }
    }

    @Test
    fun readingFromTheDrawerWithoutOpeningIsNotSeeing() = runTest {
        val store = scope()
        store.withDependencies {
            val s = HomeFeature().reduce(home(session("a")), HomeFeature.Action.MarkRead("a")).state
            assertTrue(s.followUps.entries.isEmpty())
            assertNull(s.followUpWatch)
        }
    }

    @Test
    fun aReplyIsNotedOnlyFromTheScreenThatShowsIt() = runTest {
        val store = scope()
        store.withDependencies {
            val opened = home(session("a"), session("b")).opening(session("a"))
            val early = opened.shown("a", reply())
            assertTrue("not on screen yet: kept, not noted", early.followUps.entries.isEmpty())
            val onScreen = early.visible()
            assertEquals("noted once it shows", "reply-1", onScreen.followUps[onScreen.key("a")]?.replyID)

            val covered = onScreen.copy(sessionDetail = null).opening(onScreen.sessions[1]).visible()
                .updatingDetail { it.copy(isTranscriptCovered = true) }
            val waiting = covered.shown("b", reply("reply-b"))
            assertNull("covered by a subagent or browser: not seen yet", waiting.followUps[waiting.key("b")])
            assertEquals(ShownReply("reply-b", 2_000), waiting.followUpWatch?.unshown)
            val uncovered = waiting.updatingDetail { it.copy(isTranscriptCovered = false) }.followingFollowUpWatch(before = waiting)
            assertEquals("reply-b", uncovered.followUps[uncovered.key("b")]?.replyID)
            assertNull(uncovered.followUpWatch?.unshown)
        }
    }

    @Test
    fun aReplyWatchedAsItStreamsIsPendingOnceFinished() = runTest {
        val store = scope()
        store.withDependencies {
            val running = session("a", idle = 1_000, viewed = 1_000)
            var s = home(running).opening(running).visible().copy(activeSessionIDs = setOf("a"))
            s = s.updatingSession("a") { it.copy(idleMilliseconds = 1_000) }
            s = s.shown("a", listOf(UserText("u", "q"), AssistantMarkdown("r", "par", inProgress = true)))
            assertTrue("streaming output is not a reply", s.followUps.entries.isEmpty())
            s = s.copy(activeSessionIDs = emptySet()).updatingSession("a") { it.copy(idleMilliseconds = 3_000, viewedMilliseconds = 3_000) }
            s = s.shown("a", listOf(UserText("u", "q"), AssistantMarkdown("r", "done")))
            assertEquals(FollowUp.Status.pending, s.followUps[s.key("a")]?.status)
            assertEquals(3_000L, s.followUps[s.key("a")]?.idle)
        }
    }

    @Test
    fun aRunEndingOnAToolOrRenameIsNotAReply() = runTest {
        val store = scope()
        store.withDependencies {
            var s = home(session("a")).opening(session("a")).visible()
            s = s.shown("a", reply() + OpenCodeTranscriptItem.Tool("t", toolCall()))
            assertTrue(s.followUps.entries.isEmpty())
            s = s.updatingSession("a") { it.copy(serverTitle = "Renamed", updatedAt = Instant.ofEpochSecond(99)) }
            assertTrue(s.followUps.entries.isEmpty())
        }
    }

    @Test
    fun catchUpMarkAsReadCountsButKeepUnreadDoesNot() = runTest {
        val store = scope()
        store.withDependencies {
            val base = home(session("a")).copy(transcriptCache = mapOf("a" to reply()))
            assertEquals("reply-1", base.notingFollowUpFromCatchUp(base.sessions.first()).followUps[base.key("a")]?.replyID)
            val read = home(session("b", idle = 2_000, viewed = 2_000)).copy(transcriptCache = mapOf("b" to reply()))
            assertNull(read.notingFollowUpFromCatchUp(read.sessions.first()).followUps[read.key("b")])
        }
    }

    @Test
    fun reopeningTheSameReplyAfterDoneDoesNotBringItBack() = runTest {
        val store = scope()
        store.withDependencies {
            var s = home(session("a")).opening(session("a")).visible().shown("a", reply())
            s = HomeFeature().reduceFollowUp(s, FollowUpAction.DoneTapped(listOf("a"))).state
            assertEquals(FollowUp.Status.done, s.followUps[s.key("a")]?.status)
            // The server flaps the read and the screen reads the same reply again.
            val unread = s.copy(sessionDetail = null).updatingSession("a") { it.copy(viewedMilliseconds = 1_000) }
            val again = unread.opening(unread.sessions.first()).visible().shown("a", reply())
            assertEquals(FollowUp.Status.done, again.followUps[again.key("a")]?.status)
            assertTrue(again.pendingFollowUpIDs.isEmpty())
            assertEquals("Done keeps the session where it is", s.sessions, home(session("a")).sessions.map { s.sessions.first { x -> x.id == it.id } })
        }
    }

    // endregion

    // region Handling

    @Test
    fun anAdmittedSendAnswersItAndAFailedOneDoesNot() = runTest {
        val store = scope()
        store.withDependencies {
            val s = home(session("a")).opening(session("a")).visible().shown("a", reply())
            val failed = HomeFeature().reduce(
                s,
                HomeFeature.Action.detail(
                    SessionFeature.Action.PromptFailed("a", OpenCodeClientError("Offline"), pendingPrompt()),
                ),
            ).state
            assertEquals("an offline send changes nothing", FollowUp.Status.pending, failed.followUps[s.key("a")]?.status)
            val sent = HomeFeature().reduce(s, HomeFeature.Action.detail(SessionFeature.Action.PromptAdmitted("a", "m-2", 0))).state
            assertEquals(FollowUp.Status.replied, sent.followUps[s.key("a")]?.status)
            assertTrue(sent.pendingFollowUpIDs.isEmpty())
        }
    }

    private fun pendingPrompt() = PendingPrompt(messageID = "m-2", text = "hi", attachments = emptyList(), requestEpoch = 0)

    @Test
    fun archivingHandlesItRestoringKeepsItHandledAndDeletingForgetsIt() = runTest {
        val store = scope()
        store.withDependencies {
            val s = home(session("a"), session("b")).opening(session("a")).visible().shown("a", reply())
                .let { it.copy(sessionDetail = null).opening(it.sessions[1]).visible().shown("b", reply("reply-b")) }
            val archived = HomeFeature().archiveConfirmed(s, "a").state
            assertEquals(FollowUp.Status.archived, archived.followUps[s.key("a")]?.status)
            val elsewhere = HomeFeature().reduce(s, HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionArchiveChanged("b", isArchived = true))).state
            assertEquals("archived on another device", FollowUp.Status.archived, elsewhere.followUps[s.key("b")]?.status)
            val restored = archived.updatingSession("a") { it.copy(archivedMilliseconds = null) }
            assertFalse("restoring doesn't undo the handling", "a" in restored.pendingFollowUpIDs)
            val deleted = s.purgingSession("b")
            assertNull(deleted.followUps[s.key("b")])
        }
    }

    @Test
    fun anArchivedSessionNeverShowsAFollowUp() = runTest {
        val store = scope()
        store.withDependencies {
            val s = home(session("a", archived = 5)).updatingFollowUps { it.noting("${server.persistenceID}|a", "r", 2_000, now) }
            assertTrue(s.openFollowUps.isEmpty())
        }
    }

    // endregion

    // region Snooze, Undo, time

    @Test
    fun snoozeTimesAreNineInTheMorningLocal() {
        val zone = ZoneId.of("America/Chicago")
        assertEquals(Instant.parse("2026-11-24T15:00:00Z"), FollowUpSnooze.Tomorrow.until(now, zone))
        assertEquals(Instant.parse("2026-11-30T15:00:00Z"), FollowUpSnooze.NextWeek.until(now, zone))
        assertEquals(Instant.parse("2026-12-03T15:00:00Z"), FollowUpSnooze.On(LocalDate.of(2026, 12, 3)).until(now, zone))
        assertNull("today's 9:00 has passed", FollowUpSnooze.On(LocalDate.of(2026, 11, 23)).until(now, zone))
        assertNull("a past date", FollowUpSnooze.On(LocalDate.of(2026, 11, 1)).until(now, zone))
        assertEquals(LocalDate.of(2026, 11, 24), FollowUpSnooze.firstDate(now, zone))
        assertEquals(LocalDate.of(2026, 11, 23), FollowUpSnooze.firstDate(Instant.parse("2026-11-23T12:00:00Z"), zone))
    }

    @Test
    fun snoozeHidesItUntilDueThenItComesBack() = runTest {
        val store = scope()
        store.withDependencies {
            var s = home(session("a")).opening(session("a")).visible().shown("a", reply())
            s = HomeFeature().reduceFollowUp(s, FollowUpAction.SnoozeTapped(listOf("a"), FollowUpSnooze.Tomorrow)).state
            val entry = s.followUps[s.key("a")]!!
            assertEquals(FollowUp.Status.snoozed, entry.status)
            assertEquals(Instant.parse("2026-11-24T15:00:00Z").toEpochMilli(), entry.snoozedUntil)
            assertTrue("no purple dot while snoozed", s.pendingFollowUpIDs.isEmpty())
            assertEquals("still found under Snoozed", listOf("a"), s.followUpRows().snoozed.map { it.id })
            assertTrue("not due yet", s.wakingDueFollowUps().pendingFollowUpIDs.isEmpty())
        }
        val later = TestStore(home(), HomeFeature(), this) { this[DateClient] = DateClient.constant(Instant.parse("2026-11-24T15:00:00Z")) }
        later.withDependencies {
            val snoozed = home(session("a")).updatingFollowUps {
                FollowUps(mapOf("${server.persistenceID}|a" to FollowUp("r", 2_000, 0, FollowUp.Status.snoozed, Instant.parse("2026-11-24T15:00:00Z").toEpochMilli())))
            }
            val back = HomeFeature().reduce(snoozed, HomeFeature.Action.SceneBecameActive).state
            assertEquals("returning to the app brings a due snooze back", setOf("a"), back.pendingFollowUpIDs)
            val viaPage = HomeFeature().reduceFollowUp(snoozed, FollowUpAction.DueCheck).state
            assertEquals(setOf("a"), viaPage.pendingFollowUpIDs)
        }
    }

    @Test
    fun bringBackNowAndChangeDate() = runTest {
        val store = scope()
        store.withDependencies {
            var s = home(session("a")).opening(session("a")).visible().shown("a", reply())
            s = HomeFeature().reduceFollowUp(s, FollowUpAction.SnoozeTapped(listOf("a"), FollowUpSnooze.Tomorrow)).state
            s = HomeFeature().reduceFollowUp(s, FollowUpAction.SnoozeTapped(listOf("a"), FollowUpSnooze.On(LocalDate.of(2026, 12, 3)))).state
            assertEquals(Instant.parse("2026-12-03T15:00:00Z").toEpochMilli(), s.followUps[s.key("a")]?.snoozedUntil)
            s = HomeFeature().reduceFollowUp(s, FollowUpAction.BringBackTapped("a")).state
            assertEquals(setOf("a"), s.pendingFollowUpIDs)
            val past = HomeFeature().reduceFollowUp(s, FollowUpAction.SnoozeTapped(listOf("a"), FollowUpSnooze.On(LocalDate.of(2026, 11, 1)))).state
            assertEquals("a past date is refused", s.followUps, past.followUps)
        }
    }

    @Test
    fun undoRestoresDoneAndSnoozeButNeverOverANewerReply() = runTest {
        val store = scope()
        store.withDependencies {
            val pending = home(session("a")).opening(session("a")).visible().shown("a", reply())
            val done = HomeFeature().reduceFollowUp(pending, FollowUpAction.DoneTapped(listOf("a"))).state
            assertEquals(FollowUpUndo.Kind.done, done.followUpUndo?.kind)
            val undone = HomeFeature().reduceFollowUp(done, FollowUpAction.UndoTapped).state
            assertEquals(pending.followUps, undone.followUps)
            assertNull(undone.followUpUndo)

            val snoozed = HomeFeature().reduceFollowUp(pending, FollowUpAction.SnoozeTapped(listOf("a"), FollowUpSnooze.NextWeek)).state
            // A newer reply is seen before Undo is tapped.
            val newer = snoozed.updatingSession("a") { it.copy(idleMilliseconds = 5_000) }.shown("a", reply() + listOf(UserText("u-2", "more"), AssistantMarkdown("reply-2", "again")))
            assertEquals("a new reply is pending again", "reply-2", newer.followUps[newer.key("a")]?.replyID)
            assertEquals(FollowUp.Status.pending, newer.followUps[newer.key("a")]?.status)
            val late = HomeFeature().reduceFollowUp(newer, FollowUpAction.UndoTapped).state
            assertEquals("Undo leaves the newer reply alone", newer.followUps, late.followUps)

            val expired = HomeFeature().reduceFollowUp(done, FollowUpAction.UndoExpired(done.followUpUndo!!.generation)).state
            assertNull(expired.followUpUndo)
            assertEquals(FollowUp.Status.done, expired.followUps[expired.key("a")]?.status)
        }
    }

    @Test
    fun recordsNeverExpireWhileOpen() {
        var records = FollowUps()
        records = records.noting("s|open", "r", 1, Instant.EPOCH)
        for (i in 0..FollowUps.handledLimit + 5) records = records.noting("s|$i", "r$i", 1, Instant.ofEpochMilli(i + 10L)).handling("s|$i", FollowUp.Status.done)
        assertTrue(records.entries.values.count { !it.isOpen } <= FollowUps.handledLimit)
        assertEquals(FollowUp.Status.pending, records["s|open"]?.status)
    }

    // endregion

    // region Persistence and server scope

    @Test
    fun followUpsPersistPerServerAcrossARestart() = runTest {
        val store = scope()
        store.withDependencies {
            val s = home(session("a")).opening(session("a")).visible().shown("a", reply())
            assertEquals(s.followUps, shared(DrawerSharedKeys.followUps).value)
            assertTrue("only the key and the reply's identity, no text", shared(DrawerSharedKeys.followUps).value.toString().contains("reply-1"))
            assertFalse(shared(DrawerSharedKeys.followUps).value.toString().contains("answer"))
            val relaunched = home(session("a"))
            assertEquals(setOf("a"), relaunched.pendingFollowUpIDs)
            val elsewhere = home(session("a"), on = other)
            assertTrue("another server's session with the same ID shows nothing", elsewhere.pendingFollowUpIDs.isEmpty())
            val sentElsewhere = elsewhere.notingFollowUpReplySent("a")
            assertEquals(FollowUp.Status.pending, sentElsewhere.followUps["${server.persistenceID}|a"]?.status)
        }
    }

    // endregion

    // region Drawer

    private fun HomeFeature.State.model(): DrawerListModel = DrawerListModel.build(
        state = this,
        inputs = DrawerListInputs(selectedSessionID = null, reducedMotion = true, increasedContrast = false),
        liftedID = null,
        now = Instant.EPOCH,
        timestamp = { _, _ -> "" },
        project = { _, _ -> null },
    )

    @Test
    fun rowsAndGroupsShowFollowUpBelowNativeStatus() = runTest {
        val store = scope()
        store.withDependencies {
            var s = home(session("a", idle = 2_000, viewed = 2_000), session("b"), session("c", idle = 2_000, viewed = 2_000)).copy(
                sessionGroups = SessionGroups(
                    mapOf(
                        server.persistenceID to listOf(
                            SessionGroup(id = "root", name = "Root", sessionIDs = listOf("c")),
                            SessionGroup(id = "child", name = "Child", sessionIDs = listOf("a", "b"), parentID = "root"),
                        ),
                    ),
                ),
                pinnedSessionKeys = listOf("${server.persistenceID}|a"),
            )
            s = s.updatingFollowUps {
                it.noting("${server.persistenceID}|a", "ra", 2_000, now).noting("${server.persistenceID}|b", "rb", 2_000, now)
            }
            val rollups = s.groupRollups()
            assertEquals("unread (b) outranks Follow Up, the pinned member's included", GroupStatus.unread, rollups["child"]?.status)
            assertEquals(true, rollups["child"]?.hasFollowUp)
            assertEquals("the parent rolls up the same", GroupStatus.unread, rollups["root"]?.status)
            val readB = s.updatingSession("b") { it.copy(viewedMilliseconds = 2_000) }
            assertEquals(GroupStatus.followUp, readB.groupRollups()["child"]?.status)
            assertEquals(GroupStatus.followUp, readB.groupRollups()["root"]?.status)

            val model = s.model()
            val pinned = model.rows.getValue("a")
            assertTrue(pinned.followUp)
            assertEquals(HomeFeature.State.Indicator.none, pinned.indicator)
            assertTrue(pinned.accessibilityValue.contains("Follow Up"))
            assertTrue(model.items.contains(DrawerListItem.FollowUp))
            assertEquals(2, model.shortcuts.followUpCount)

            val running = s.copy(activeSessionIDs = setOf("a"))
            val runningRow = running.model().rows.getValue("a")
            assertEquals("running keeps its ring", HomeFeature.State.Indicator.running, runningRow.indicator)
            assertTrue("and still says Follow Up to TalkBack", runningRow.accessibilityValue.contains("Follow Up"))
        }
    }

    // endregion
}
