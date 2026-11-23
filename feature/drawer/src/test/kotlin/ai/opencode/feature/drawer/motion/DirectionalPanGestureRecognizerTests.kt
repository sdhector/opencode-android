package ai.opencode.feature.drawer.motion

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.BackSwipeRules
import ai.opencode.feature.drawer.catchup.CatchUpDisposition
import ai.opencode.feature.drawer.catchup.CatchUpStackController
import ai.opencode.feature.drawer.catchup.CatchUpStage
import ai.opencode.feature.drawer.container.CatchUpModeMorph
import ai.opencode.feature.drawer.container.DrawerScrim
import ai.opencode.feature.drawer.container.SessionDrawerController
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Port of iOS `DirectionalPanGestureRecognizerTests.swift` (all of its suites: the pan's decision,
 * `DrawerSpring`, `DrawerCorner`, `DrawerDim`, the hold rule, `DrawerTimeReveal`,
 * `DrawerTimestamp`, Catch Up's stack, and the drawer container's settle). iOS's
 * `SessionDrawerViewController` maps to [SessionDrawerController] (+ [DrawerMotionState] and
 * [CatchUpStackController]); its display link runs here on a virtual frame clock.
 *
 * Deleted (UIKit-only):
 * - `DrawerSpringTests.displayCornersByDevice`'s `DrawerCorner.displayRadius(screen:…)` rows:
 *   iOS looks the radius up in an iPhone screen-size table; Android reads the display's own
 *   `RoundedCorner` from the window insets. The `radius(isMoving:offset:display:)` rows are ported.
 * - `DrawerTimeRevealTests.revealedTimeStaysLeftOfTheRow`: lays out a UIKit
 *   `DrawerListSessionCell` and measures its `UILabel` frame.
 * - `CatchUpTransitionTests.cardRotationDoesNotChangeHeaderOrComposerLayout` and
 *   `realSessionCardLocksHeaderTranscriptAndComposerAcrossSwipeAndPromotion`: UIView frame,
 *   safe-area and `adjustedContentInset` probes of a hosted SessionView in a UIWindow.
 * - `catchingCardMidSettle…`'s step 4 (a weak display-link target lets the view controller
 *   deallocate): UIKit/ARC lifetime; Android's loops are coroutines cancelled with their scope.
 *
 * Dropped: `ComposerSendTransitionTests.interruptedSendRestoresTheBubbleAndRemovesBothSnapshots`.
 *   Android sends without a flight (deliberate divergence, docs/parity/session.md).
 *
 * UIKit-only assertions dropped inside ported tests: subview/snapshot counts, `CALayer` masks
 * and animation keys, `safeAreaRegions`, first responders and `UIAccessibility` screen changes
 * (their Android counterparts — the fade's state, TalkBack state, focus — are asserted).
 */
class DirectionalPanGestureRecognizerTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun decisionTable() {
        val decide = DirectionalPan::decision
        data class Row(val origin: DirectionalPan.TouchOrigin, val presented: Boolean, val dx: Float, val dy: Float, val expected: DirectionalPan.Decision)
        val rows = listOf(
            // Rows have no swipe actions: a leftward drag on one closes the drawer.
            Row(DirectionalPan.TouchOrigin.DrawerList, true, -20f, 1f, DirectionalPan.Decision.Begin),
            Row(DirectionalPan.TouchOrigin.DrawerChrome, true, -20f, 1f, DirectionalPan.Decision.Begin),
            Row(DirectionalPan.TouchOrigin.ExposedCard, true, -20f, 1f, DirectionalPan.Decision.Begin),
            // Toward the card, the list slides its rows to show their times.
            Row(DirectionalPan.TouchOrigin.DrawerList, true, 20f, 1f, DirectionalPan.Decision.Reveal),
            Row(DirectionalPan.TouchOrigin.DrawerChrome, true, 20f, 1f, DirectionalPan.Decision.Begin),
            Row(DirectionalPan.TouchOrigin.Content(horizontalScrollerOwnsRightwardDrag = false), false, 20f, 1f, DirectionalPan.Decision.Begin),
            Row(DirectionalPan.TouchOrigin.Content(horizontalScrollerOwnsRightwardDrag = true), false, 20f, 1f, DirectionalPan.Decision.Fail),
            Row(DirectionalPan.TouchOrigin.Content(horizontalScrollerOwnsRightwardDrag = false), false, -20f, 1f, DirectionalPan.Decision.Fail),
            Row(DirectionalPan.TouchOrigin.Content(horizontalScrollerOwnsRightwardDrag = false), false, 9f, 10f, DirectionalPan.Decision.Fail),
            Row(DirectionalPan.TouchOrigin.Content(horizontalScrollerOwnsRightwardDrag = false), false, 3f, 2f, DirectionalPan.Decision.Wait),
        )
        for (row in rows) {
            assertEquals(row.toString(), row.expected, decide(row.origin, row.presented, row.dx, row.dy, BackSwipeRules.decisionDistance.value))
        }
    }

    /** Android only: inside a group, toward the card goes back a level instead of revealing times. */
    @Test
    fun groupListGoesBackInsteadOfRevealing() {
        val decide = DirectionalPan::decision
        val distance = BackSwipeRules.decisionDistance.value
        val group = DirectionalPan.TouchOrigin.DrawerGroupList
        assertEquals(DirectionalPan.Decision.LeaveGroup, decide(group, true, 20f, 1f, distance))
        assertEquals("the root keeps the reveal", DirectionalPan.Decision.Reveal, decide(DirectionalPan.TouchOrigin.DrawerList, true, 20f, 1f, distance))
        assertEquals("leftward still closes the drawer", DirectionalPan.Decision.Begin, decide(group, true, -20f, 1f, distance))
        assertEquals("vertical stays with the list's scroll", DirectionalPan.Decision.Fail, decide(group, true, 9f, 10f, distance))
        assertEquals(DirectionalPan.Decision.Wait, decide(group, true, 3f, 2f, distance))
    }
}

class DrawerGroupBackPanTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Harness(width: Float = 330f) {
        val progress = mutableListOf<Float>()
        var backs = 0
        var settles = 0
        val pan = DrawerGroupBackPan(
            width = { width },
            onProgress = { progress += it },
            onBack = { backs += 1 },
            onSettle = { settles += 1 },
        )
    }

    @Test
    fun followsTheFingerAsBackProgress() {
        val harness = Harness()
        harness.pan.began()
        harness.pan.changed(-20f)
        harness.pan.changed(82.5f)
        harness.pan.changed(660f)
        assertEquals(listOf(0f, 0f, 0.25f, 1f), harness.progress)
        assertEquals(0f, DrawerGroupBackPan.progress(40f, width = 0f))
    }

    @Test
    fun aCompletedSwipeGoesBackOnceAndAShortOneSettles() {
        val harness = Harness()
        harness.pan.began()
        harness.pan.changed(200f)
        harness.pan.ended(velocity = 0f)
        assertEquals("past halfway goes back one level", 1, harness.backs)
        assertEquals(0, harness.settles)
        assertFalse(harness.pan.isActive)

        harness.pan.began()
        harness.pan.changed(60f)
        harness.pan.ended(velocity = 0f)
        assertEquals(1, harness.backs)
        assertEquals("short of halfway it settles", 1, harness.settles)

        harness.pan.began()
        harness.pan.changed(30f)
        harness.pan.ended(velocity = 2_000f)
        assertEquals("a flick goes back", 2, harness.backs)

        harness.pan.began()
        harness.pan.changed(250f)
        harness.pan.ended(velocity = -2_000f)
        assertEquals("a flick away never goes back", 2, harness.backs)
        assertEquals(2, harness.settles)
    }

    @Test
    fun aCancelledOrCutShortDragSettlesOnceAndIgnoresTheRest() {
        val harness = Harness()
        harness.pan.began()
        harness.pan.changed(250f)
        harness.pan.cancelled()
        assertEquals(1, harness.settles)
        harness.pan.changed(300f)
        harness.pan.ended(velocity = 2_000f)
        harness.pan.cancelled()
        assertEquals("the rest of the drag does nothing", 0, harness.backs)
        assertEquals(1, harness.settles)
        assertEquals(listOf(0f, 250f / 330f), harness.progress)
    }
}

class DrawerSpringTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun arrivesOnceAndStopsAtTheEdge() {
        for ((position, velocity) in listOf(330.0 to 0.0, 200.0 to -2_000.0, 100.0 to -9_000.0)) {
            val spring = DrawerSpring(position, velocity, 0.0, 0.0..330.0)
            var arrivals = 0
            for (i in 0 until 240) {
                val events = spring.step(1.0 / 120)
                arrivals += events.count { it == DrawerSpring.Event.Arrived }
                assertTrue("never passes the edge", spring.position >= 0)
                if (DrawerSpring.Event.Settled in events) break
            }
            assertEquals(1, arrivals)
            assertEquals(0.0, spring.position, 0.0)
        }
    }

    /** A fast full swipe released right on an edge, still moving outwards, stops there instead of sliding off screen and springing back. */
    @Test
    fun aReleaseOnTheEdgeMovingOutwardsStopsThere() {
        for ((target, velocity) in listOf(0.0 to -2_500.0, 330.0 to 2_500.0)) {
            val spring = DrawerSpring(target, velocity, target, 0.0..330.0)
            val events = spring.step(1.0 / 120)
            assertEquals("never leaves the edge it's resting on", target, spring.position, 0.0)
            assertTrue(DrawerSpring.Event.Arrived in events)
            assertTrue(DrawerSpring.Event.Settled in events)
        }
        // Moving outwards from just inside an edge: never passes it.
        for ((start, target, velocity) in listOf(Triple(3.0, 0.0, -4_000.0), Triple(327.0, 330.0, 4_000.0))) {
            val spring = DrawerSpring(start, velocity, target, 0.0..330.0)
            repeat(60) {
                spring.step(1.0 / 120)
                assertTrue(spring.position in 0.0..330.0)
            }
            assertEquals(target, spring.position, 0.0)
        }
    }

    /** iOS's per-device `displayRadius` table is n/a on Android (see the file note); the card's rule is ported. */
    @Test
    fun displayCornersByDevice() {
        assertEquals(0f, DrawerCorner.radius(isMoving = false, offset = 0f, display = 62f))
        assertEquals(62f, DrawerCorner.radius(isMoving = true, offset = 0f, display = 62f))
        assertEquals(62f, DrawerCorner.radius(isMoving = false, offset = 330f, display = 62f))
    }

    @Test
    fun sidebarDimClearsSlightlyFasterThanLinear() {
        assertEquals(0.5f, DrawerDim.alpha(progress = 0f, maximum = 0.5f))
        assertEquals(0f, DrawerDim.alpha(progress = 1f, maximum = 0.5f))
        assertEquals(0f, DrawerDim.alpha(progress = 2f, maximum = 0.5f))
        val half = DrawerDim.alpha(progress = 0.5f, maximum = 0.5f)
        assertTrue(half < 0.25f && half > 0.18f)
        var previous = 1f
        for (step in 0..20) {
            val alpha = DrawerDim.alpha(progress = step / 20f, maximum = 0.5f)
            assertTrue(alpha <= previous)
            previous = alpha
        }
        val light = DrawerDim.maximum(isDark = false)
        val dark = DrawerDim.maximum(isDark = true)
        assertTrue(light in 0.6f..0.7f && dark in 0.6f..0.7f)
    }
}

class DrawerHoldTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun aHoldKeepsItsTouch() {
        assertFalse("a quick drag is the drawer's", DirectionalPan.yieldsToHold(heldForSeconds = 0.05, holdBegan = false))
        assertTrue("a rested finger keeps its hold", DirectionalPan.yieldsToHold(heldForSeconds = 0.6, holdBegan = false))
        assertTrue("a begun long press keeps its touch", DirectionalPan.yieldsToHold(heldForSeconds = 0.1, holdBegan = true))
    }
}

class DrawerTimeRevealTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun rowsFollowThenRubberBand() {
        val reach = 76f
        assertEquals(0f, DrawerTimeReveal.offset(-40f, reach))
        assertEquals(0f, DrawerTimeReveal.offset(0f, reach))
        assertTrue("follows the finger closely at first", abs(DrawerTimeReveal.offset(40f, reach) - 34f) < 0.01f)
        var previous = 0f
        var translation = 0f
        while (translation <= 1_000f) {
            val offset = DrawerTimeReveal.offset(translation, reach)
            assertTrue("never moves back while the finger moves on", offset >= previous)
            assertTrue("rubber-bands short of a limit", offset < reach * 1.35f)
            previous = offset
            translation += 10f
        }
        assertTrue("far enough to uncover the times", DrawerTimeReveal.offset(120f, reach) > reach)
    }

    @Test
    fun releasingSpringsBackAndReduceMotionOnlyFades() {
        val reveal = DrawerTimeReveal()
        reveal.update(DrawerRevealPhase.Began, reach = 76f, reducedMotion = false)
        reveal.update(DrawerRevealPhase.Changed(150f), reach = 76f, reducedMotion = false)
        assertTrue(reveal.offset > 76f)
        assertEquals(1f, reveal.progress)
        reveal.update(DrawerRevealPhase.Ended, reach = 76f, reducedMotion = false)
        assertEquals(0f, reveal.offset)
        assertEquals(0f, reveal.progress)

        reveal.update(DrawerRevealPhase.Began, reach = 76f, reducedMotion = true)
        reveal.update(DrawerRevealPhase.Changed(38f), reach = 76f, reducedMotion = true)
        assertEquals("Reduce Motion never slides the rows", 0f, reveal.offset)
        assertTrue(abs(reveal.progress - 0.5f) < 0.001f)
    }

    @Test
    fun timestampsReadLikeMessages() {
        val zone = ZoneId.of("America/New_York")
        val locale = Locale.forLanguageTag("en-US")
        // Wednesday, October 7, 2026, 3:00 PM.
        val now = LocalDateTime.of(2026, 10, 7, 15, 0).atZone(zone).toInstant()
        fun text(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): String {
            val date = LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()
            return DrawerTimestamp.text(date, now = now, zone = zone, locale = locale).replace('\u202F', ' ')
        }
        assertEquals("12:28 PM", text(2026, 10, 7, 12, 28))
        assertEquals("Yesterday", text(2026, 10, 6, 23, 59))
        assertEquals("Friday", text(2026, 10, 2, 9))
        assertEquals("Thursday", text(2026, 10, 1, 9))
        assertEquals("9/30/2026", text(2026, 9, 30, 9))
    }
}

/** A frame clock on the test's virtual time (iOS's display link), one frame per [frameMillis]. */
private class VirtualFrameClock(private val scheduler: TestCoroutineScheduler, private val frameMillis: Long = 16) : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        delay(frameMillis)
        return onFrame(scheduler.currentTime * 1_000_000)
    }
}

private fun TestScope.motionScope(): CoroutineScope = backgroundScope + VirtualFrameClock(testScheduler)

class CatchUpTransitionTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun TestScope.controller(isPresented: Boolean): SessionDrawerController {
        val scope = motionScope()
        val stack = CatchUpStackController().also {
            it.scope = scope
            it.containerWidth = 440f
            it.cardHeight = 800f
        }
        return SessionDrawerController(scope, DrawerMotionState(scope, isPresented, 330f), stack).also {
            it.drawerWidth = 330f
        }
    }

    @Test
    fun immediateDismissUndoAndExitLeaveNoTransitionSnapshots() = runTest {
        val controller = controller(isPresented = true)
        var dismissed = 0
        var undone = 0
        val stage = CatchUpStage(
            queue = listOf("first", "second"),
            undoDirection = CatchUpDisposition.markRead,
            onDismiss = { _, _ -> dismissed += 1 },
            onUndo = { undone += 1 },
        )

        controller.updateCatchUp(stage, returnsToDrawer = false, animated = true)
        controller.catchUpStack.begin(stage)
        assertEquals("The entrance owns the card", CatchUpModeMorph.EnterCatchUp, controller.morph)
        controller.dismissCard(CatchUpDisposition.markRead)
        assertEquals("The store hears a dismissal once the card is off screen", 0, dismissed)
        assertNull("The entrance must finish before a flight takes ownership", controller.morph)
        controller.undoCard()
        assertEquals("Undo lands the flight, and the dismissal reaches the store before the undo", 1, dismissed)
        assertEquals(1, undone)
        controller.updateCatchUp(null, returnsToDrawer = false, animated = true)
        // Re-enter and leave before the exit morph completes, too.
        controller.updateCatchUp(stage, returnsToDrawer = false, animated = false)
        controller.updateCatchUp(null, returnsToDrawer = false, animated = false)
        controller.catchUpStack.end()
        assertNull(controller.morph)
        assertNull("Fades must release the card's picture", controller.leavingCard)
        assertEquals(0f, controller.stageAlpha.floatValue)
    }

    @Test
    fun catchingCardMidSettleTakesOverFromDrawnPositionAndFlickCommits() = runTest {
        val controller = controller(isPresented = false)
        val stack = controller.catchUpStack
        val dismissed = mutableListOf<CatchUpDisposition>()
        val stage = CatchUpStage(queue = listOf("first", "second"), onDismiss = { _, disposition -> dismissed += disposition })
        controller.updateCatchUp(stage, returnsToDrawer = false, animated = false)
        stack.begin(stage)

        // 1. Short slow drag (68 dp < 105.6 dp) snaps back.
        stack.panBegan(Offset.Zero)
        stack.panChanged(Offset(68f, 6f))
        stack.panEnded(Offset(68f, 6f), velocity = Offset.Zero)
        assertTrue("Short slow drag snaps back instead of committing", dismissed.isEmpty())

        // 2. Let the snap-back spring run ~60 ms, then catch the card mid-flight.
        advanceTimeBy(60)
        val midSettleX = stack.dragOffset.x
        assertTrue("Card is mid-flight toward zero: $midSettleX", midSettleX > 5f && midSettleX < 68f)

        stack.panBegan(Offset.Zero)
        assertTrue("Catching mid-flight starts from the exact drawn position", abs(stack.dragOffset.x - midSettleX) < 0.01f)

        // 3. Flick from the caught position (short additional drag + 680 dp/s velocity) commits.
        stack.panChanged(Offset(24f, 0f))
        assertTrue(abs(stack.dragOffset.x - (midSettleX + 24f)) < 0.01f)
        stack.panEnded(Offset(24f, 0f), velocity = Offset(680f, 0f))
        assertTrue("The store hears the dismissal once the card is off screen", dismissed.isEmpty())
        advanceTimeBy(1_000)
        assertEquals("Fast flick commits via projected landing", listOf(CatchUpDisposition.markRead), dismissed)
    }
}

class SessionDrawerSettleTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Harness(val test: TestScope, reducedMotion: Boolean = false, isPresented: Boolean = false) {
        val scope = test.motionScope()
        val motion = DrawerMotionState(scope, isPresented, 330f)
        val stack = CatchUpStackController().also {
            it.scope = scope
            it.containerWidth = 440f
            it.cardHeight = 800f
            it.reducedMotion = reducedMotion
        }
        val controller = SessionDrawerController(scope, motion, stack)
        val settled = mutableListOf<Boolean>()
        var commits = 0
        val presentations = mutableListOf<Boolean>()
        var focusCleared = 0

        init {
            controller.drawerWidth = 330f
            setReducedMotion(reducedMotion)
            controller.onArrived = { settled += it }
            controller.onCommitted = { commits += 1 }
            controller.onPresentationChanged = { presentations += it }
            controller.clearFocus = { focusCleared += 1 }
        }

        fun setReducedMotion(reduced: Boolean) {
            controller.reducedMotion = reduced
            motion.reducedMotion = reduced
        }

        /** The container's pan wiring for the card (`SessionDrawerContainer`'s `drawerDirectionalPan`). */
        fun beginDrag() {
            controller.panBegan()
            motion.beginDrag()
        }

        fun drag(translation: Float) = motion.drag(translation)
        fun endDrag(velocity: Float) = motion.endDrag(velocity)
        fun cancelDrag() = motion.cancelDrag()

        /** Longer than the 0.3 s settle. */
        fun waitForSettle() {
            test.advanceTimeBy(600)
            test.runCurrent()
        }

        fun sleep(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
    }

    private fun stage(queue: List<String> = listOf("a", "b")) = CatchUpStage(queue = queue)

    @Test
    fun eachOpenAndCloseArrivesOnceAndACancelNever() = runTest {
        val harness = Harness(this)
        harness.controller.setPresented(true, animated = true)
        harness.waitForSettle()
        assertEquals("one arrival as it lands open, none as the spring settles", listOf(true), harness.settled)
        harness.beginDrag()
        harness.drag(-60f)
        harness.endDrag(0f)
        harness.waitForSettle()
        assertEquals("a drag that falls back open never arrives again", listOf(true), harness.settled)
        harness.beginDrag()
        harness.drag(-250f)
        harness.endDrag(-900f)
        harness.waitForSettle()
        assertEquals(listOf(true, false), harness.settled)
    }

    @Test
    fun hapticPlaysAtReleaseBeforeTheSpringAndNeverAgainAtArrival() = runTest {
        val harness = Harness(this)
        harness.beginDrag()
        harness.drag(45f)
        assertEquals(0, harness.commits)
        harness.endDrag(900f)
        assertEquals("The release, not a later display frame, plays the tick", 1, harness.commits)
        assertTrue(harness.settled.isEmpty())
        harness.waitForSettle()
        assertEquals(1, harness.commits)
        harness.beginDrag()
        harness.drag(-220f)
        harness.endDrag(0f)
        assertEquals(2, harness.commits)
        harness.waitForSettle()
        assertEquals(2, harness.commits)
    }

    @Test
    fun cancelledAndReturningDragsStaySilentButACommittedReversalTicks() = runTest {
        val harness = Harness(this)
        harness.beginDrag()
        harness.drag(80f)
        harness.endDrag(0f)
        assertEquals(0, harness.commits)
        harness.waitForSettle()
        harness.beginDrag()
        harness.drag(250f)
        harness.cancelDrag()
        assertEquals(0, harness.commits)
        harness.waitForSettle()
        harness.controller.setPresented(true, animated = true)
        assertEquals(1, harness.commits)
        harness.sleep(80)
        harness.beginDrag()
        harness.endDrag(-900f)
        assertEquals("Catching and reversing a committed opening is a new decision", 2, harness.commits)
        harness.waitForSettle()
        assertEquals(2, harness.commits)
    }

    @Test
    fun drawerDepthFollowsTheFingerAndResetsForReducedMotionAndSidebar() = runTest {
        for (reducesMotion in listOf(false, true)) {
            val harness = Harness(this, reducedMotion = reducesMotion)
            val motion = harness.motion
            fun scale() = DrawerDepth.scale(motion.progress, motion.reducedMotion || !motion.recedes)
            harness.beginDrag()
            for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 0.5f)) {
                harness.drag(330f * fraction)
                assertEquals(if (reducesMotion) 1f else 0.96f + 0.04f * fraction, scale(), 0.0001f)
            }
            harness.setReducedMotion(!reducesMotion)
            assertEquals("A live Reduce Motion change applies without resizing", if (reducesMotion) 0.98f else 1f, scale(), 0.0001f)
            if (!reducesMotion) continue
            assertTrue("Recedes mid-drag before the sidebar takes over", scale() < 1f)
            harness.controller.setSidebar(true, animated = false)
            assertEquals("A wide window's sidebar never recedes", 1f, scale())
        }
    }

    @Test
    fun cardKeepsTheDisplayCornersFromTheFirstFrameUntilItRestsShut() = runTest {
        val harness = Harness(this)
        val display = 62f
        fun radius() = DrawerCorner.radius(harness.motion.isCardMoving, harness.motion.offset, display)
        assertEquals("square at rest shut", 0f, radius())
        harness.beginDrag()
        harness.drag(0f)
        assertEquals("rounded as soon as the drag starts", display, radius())
        for (translation in listOf(20f, 165f, 330f, 4f)) {
            harness.drag(translation)
            assertEquals("constant through the slide", display, radius())
        }
        harness.endDrag(0f)
        assertEquals("still rounded while it settles", display, radius())
        harness.waitForSettle()
        assertEquals("square again once it rests shut", 0f, radius())

        harness.controller.setPresented(true, animated = true)
        assertEquals("an opening rounds from its first frame", display, radius())
        harness.waitForSettle()
        assertEquals(display, radius())
    }

    @Test
    fun reducedMotionStillRoundsTheDraggedCard() = runTest {
        val harness = Harness(this, reducedMotion = true)
        fun radius() = DrawerCorner.radius(harness.motion.isCardMoving, harness.motion.offset, 62f)
        harness.beginDrag()
        harness.drag(40f)
        assertTrue(radius() > 0f)
        harness.endDrag(0f)
        assertEquals("settles shut at once, square", 0f, radius())
    }

    @Test
    fun sidebarDimFollowsTheCard() = runTest {
        val harness = Harness(this)
        val maximum = DrawerDim.maximum(isDark = false)
        harness.beginDrag()
        for (fraction in listOf(0f, 0.3f, 0.6f, 1f)) {
            harness.drag(330f * fraction)
            assertEquals(DrawerDim.alpha(fraction, maximum), DrawerDim.alpha(harness.motion.progress, maximum), 0.001f)
        }
        harness.cancelDrag()

        // Caught mid-settle, it picks up from where the card is, with no jump.
        harness.controller.setPresented(true, animated = true)
        harness.sleep(80)
        val drawn = harness.motion.offset
        harness.beginDrag()
        harness.drag(0f)
        assertEquals(DrawerDim.alpha(drawn / 330f, maximum), DrawerDim.alpha(harness.motion.progress, maximum), 0.001f)
        harness.drag(-20f)
        assertEquals(DrawerDim.alpha((drawn - 20f) / 330f, maximum), DrawerDim.alpha(harness.motion.progress, maximum), 0.001f)
        harness.cancelDrag()
    }

    @Test
    fun buttonOpenAndCloseEachSettleOnce() = runTest {
        val harness = Harness(this)
        harness.controller.setPresented(true, animated = true)
        assertTrue("State acknowledgement waits for the drawer to arrive", harness.settled.isEmpty())
        assertEquals("The tap gets its feedback immediately", 1, harness.commits)
        harness.waitForSettle()
        assertEquals(listOf(true), harness.settled)
        harness.controller.setPresented(false, animated = true)
        assertEquals("Choosing a session or New Session closes it quietly: that tap is navigation", 1, harness.commits)
        harness.waitForSettle()
        assertEquals(listOf(true, false), harness.settled)
        harness.controller.setPresented(true, animated = true)
        assertEquals(2, harness.commits)
        assertTrue(harness.controller.closeDrawer())
        assertEquals("Closing it by hand (back, a tap on the card) plays the snap", 3, harness.commits)
    }

    @Test
    fun dragsThatFallBackNeverSettle() = runTest {
        val harness = Harness(this)
        harness.beginDrag()
        harness.drag(60f)
        harness.endDrag(0f)
        harness.waitForSettle()
        assertTrue(harness.settled.isEmpty())

        harness.controller.setPresented(true, animated = false)
        assertEquals(listOf(true), harness.settled)
        harness.beginDrag()
        harness.drag(-60f)
        harness.cancelDrag()
        harness.waitForSettle()
        assertEquals(listOf(true), harness.settled)
    }

    @Test
    fun dragsAndFlicksSettleOnceEachWay() = runTest {
        val harness = Harness(this)
        harness.beginDrag()
        harness.drag(30f)
        harness.endDrag(900f)
        harness.waitForSettle()
        assertEquals(listOf(true), harness.settled)
        harness.beginDrag()
        harness.drag(-250f)
        harness.endDrag(0f)
        harness.waitForSettle()
        assertEquals(listOf(true, false), harness.settled)
    }

    /** Android only: predictive back on the shut card pulls it part way open; the commit opens it from there. */
    @Test
    fun aBackGestureOpensTheShutDrawerFromWhereItLeftTheCard() = runTest {
        val harness = Harness(this)
        harness.beginDrag()
        harness.drag(330f * DrawerMotionTunables.BACK_PREVIEW_TRAVEL)
        assertEquals(0, harness.commits)
        harness.motion.endBackOpen()
        assertEquals("The commit plays the snap once, short of halfway or not", 1, harness.commits)
        assertEquals(listOf(true), harness.presentations)
        harness.waitForSettle()
        assertTrue(harness.motion.isPresented)
        assertEquals(330f, harness.motion.offset)
        assertEquals(listOf(true), harness.settled)
    }

    @Test
    fun rapidReversalsNeverDoubleFire() = runTest {
        val harness = Harness(this)
        // Opened, then caught and flung shut before it arrived.
        harness.controller.setPresented(true, animated = true)
        harness.sleep(80)
        harness.beginDrag()
        harness.endDrag(-1200f)
        harness.waitForSettle()
        assertTrue(harness.settled.isEmpty())

        // Opened and closed again by taps faster than the settle.
        harness.controller.setPresented(true, animated = true)
        harness.controller.setPresented(false, animated = true)
        harness.waitForSettle()
        assertTrue(harness.settled.isEmpty())

        // Reversed twice, ending open: one arrival.
        harness.controller.setPresented(true, animated = true)
        harness.controller.setPresented(false, animated = true)
        harness.controller.setPresented(true, animated = true)
        harness.waitForSettle()
        assertEquals(listOf(true), harness.settled)
    }

    @Test
    fun reducedMotionSettlesImmediately() = runTest {
        val harness = Harness(this, reducedMotion = true)
        harness.controller.setPresented(true, animated = true)
        assertEquals(listOf(true), harness.settled)
        harness.beginDrag()
        harness.drag(-40f)
        harness.endDrag(0f)
        assertEquals(listOf(true), harness.settled)
        harness.beginDrag()
        harness.drag(-300f)
        harness.endDrag(0f)
        assertEquals(listOf(true, false), harness.settled)
    }

    @Test
    fun catchUpTakingOverAnOpenDrawerIsNotAClose() = runTest {
        val harness = Harness(this, isPresented = true)
        harness.controller.updateCatchUp(stage(listOf("a")), returnsToDrawer = false, animated = false)
        harness.controller.updateCatchUp(null, returnsToDrawer = false, animated = false)
        assertTrue(harness.settled.isEmpty())
        harness.controller.setPresented(true, animated = false)
        assertEquals(listOf(true), harness.settled)
    }

    @Test
    fun theReturnToTheDrawerTakesTheOpenDrawersPoseFromItsFirstFrame() = runTest {
        val harness = Harness(this, isPresented = true)
        val motion = harness.motion
        harness.controller.updateCatchUp(stage(), returnsToDrawer = false, animated = false)
        harness.sleep(100)
        val clearedBefore = harness.focusCleared

        harness.controller.updateCatchUp(null, returnsToDrawer = true, animated = true)
        // Mid-flight, before the 0.45 s motion lands: the open drawer's pose for touches and TalkBack at once.
        assertEquals(CatchUpModeMorph.ReturnToDrawer, harness.controller.morph)
        assertTrue(motion.isPresented)
        assertTrue("the list takes touches at once; only its picture fades", motion.isDrawerAvailable)
        assertFalse(motion.isDrawerHiddenFromAccessibility.value)
        assertTrue("the session waits under the drawer", motion.isContentSuspended)
        assertTrue(motion.isContentHiddenFromAccessibility.value)
        assertTrue("a tap on the card closes the drawer", motion.isScrimInteractive.value)
        assertTrue(motion.isModal.value)
        assertTrue("the keyboard goes as the drawer returns", harness.focusCleared > clearedBefore)
        harness.sleep(30)
        assertEquals("still returning", CatchUpModeMorph.ReturnToDrawer, harness.controller.morph)
    }

    @Test
    fun catchUpOpeningAgainMidReturnNeverArrivesOpen() = runTest {
        val harness = Harness(this, isPresented = true)
        harness.controller.updateCatchUp(stage(), returnsToDrawer = false, animated = false)
        harness.controller.updateCatchUp(null, returnsToDrawer = true, animated = true)
        harness.presentations.clear()
        harness.controller.updateCatchUp(stage(), returnsToDrawer = false, animated = false)
        assertTrue("the drawer never arrived, so Home never reads it as open", harness.settled.isEmpty())
        assertEquals(listOf(false), harness.presentations)
        assertEquals("the stage shows again", 1f, harness.controller.stageAlpha.floatValue)
        assertEquals(0, harness.commits)
    }

    @Test
    fun becomingASidebarMidReturnNeverArrivesOpen() = runTest {
        val harness = Harness(this, isPresented = true)
        harness.controller.updateCatchUp(stage(), returnsToDrawer = false, animated = false)
        harness.controller.updateCatchUp(null, returnsToDrawer = true, animated = true)
        harness.controller.setSidebar(true, animated = false)
        assertTrue(harness.settled.isEmpty())
        assertNull(harness.controller.morph)
    }

    /**
     * On Android Catch Up's stack runs its own swipe ([CatchUpStackController]); the drawer's pan is
     * off while catching up. A card drag cut short by leaving neither moves nor settles the drawer,
     * and the next drag is the drawer's again.
     */
    @Test
    fun leavingMidCardDragShowsTheCardAndDropsTheRestOfTheDrag() = runTest {
        val harness = Harness(this, isPresented = true)
        val controller = harness.controller
        val stack = harness.stack
        val dismissed = mutableListOf<CatchUpDisposition>()
        val stage = CatchUpStage(queue = listOf("a", "b"), onDismiss = { _, disposition -> dismissed += disposition })
        controller.updateCatchUp(stage, returnsToDrawer = false, animated = false)
        stack.begin(stage)
        harness.sleep(100)
        assertTrue(stack.panBegan(Offset.Zero))

        stack.panChanged(Offset(20f, 0f))
        assertEquals(20f, stack.dragOffset.x)

        controller.updateCatchUp(null, returnsToDrawer = true, animated = true)
        assertEquals("the card stays where the drag left it while the stage fades", Offset(20f, 0f), stack.dragOffset)
        stack.panChanged(Offset(400f, 0f))
        stack.panEnded(Offset(400f, 0f), velocity = Offset(2_000f, 0f))
        harness.sleep(100)
        assertTrue("the rest of the drag dismisses nothing", dismissed.isEmpty())
        assertEquals("the old drag neither moved nor settled the drawer", CatchUpModeMorph.ReturnToDrawer, controller.morph)
        assertEquals("the rest of the drag doesn't move the card", Offset(20f, 0f), stack.dragOffset)
        assertTrue(harness.settled.isEmpty())

        // The next drag is the drawer's again.
        assertTrue(controller.acceptsPan(DrawerPanEvent.Began(isRevealingTimes = false, fingerTranslation = 0f, translation = 0f)))
        harness.beginDrag()
        assertNull("a new drag takes over from the return", controller.morph)
    }

    @Test
    fun leavingCatchUpReturnsToTheOpenDrawerWithoutAHaptic() = runTest {
        val harness = Harness(this, isPresented = true)
        harness.controller.updateCatchUp(stage(listOf("a")), returnsToDrawer = false, animated = false)
        harness.controller.updateCatchUp(null, returnsToDrawer = true, animated = false)
        // Home asks for the open drawer in the same update: already there.
        harness.controller.setPresented(true, animated = true)
        assertEquals("arrives open once", listOf(true), harness.settled)
        assertEquals("navigation stays quiet", 0, harness.commits)
        assertEquals(330f, harness.motion.offset)
        assertEquals(1f, harness.motion.progress)
        assertTrue("a tap on the card closes the drawer", harness.motion.isScrimInteractive.value)
    }

    @Test
    fun theAnimatedReturnToTheDrawerReleasesItsMaskAndSnapshots() = runTest {
        val harness = Harness(this, isPresented = true)
        val controller = harness.controller
        harness.controller.updateCatchUp(stage(), returnsToDrawer = false, animated = false)
        harness.controller.updateCatchUp(null, returnsToDrawer = true, animated = true)
        assertEquals("the stage fades over the open drawer", CatchUpModeMorph.ReturnToDrawer, controller.morph)
        harness.sleep(800)
        assertNull(controller.morph)
        assertNull(controller.leavingCard)
        assertEquals(0f, controller.stageAlpha.floatValue)
        assertEquals(330f, harness.motion.offset)
        assertEquals(listOf(true), harness.settled)
        assertEquals(0, harness.commits)
    }

    @Test
    fun aDragDuringTheReturnToTheDrawerTakesOverFromIt() = runTest {
        val harness = Harness(this, isPresented = true)
        harness.controller.updateCatchUp(stage(listOf("a")), returnsToDrawer = false, animated = false)
        harness.controller.updateCatchUp(null, returnsToDrawer = true, animated = true)
        harness.beginDrag()
        assertNull("the return lands before the finger takes the card", harness.controller.morph)
        harness.drag(-100f)
        assertEquals(230f, harness.motion.offset)
    }

    @Test
    fun scrimFollowsTheDragAndHidesTheCardFromAccessibility() = runTest {
        val harness = Harness(this)
        val motion = harness.motion
        // The scrim draws at alpha = progress (`DrawerScrim`).
        assertEquals(0f, motion.progress)
        assertFalse(motion.isScrimInteractive.value)
        assertFalse(motion.isContentHiddenFromAccessibility.value)

        harness.beginDrag()
        harness.drag(165f)
        assertEquals(0.5f, motion.progress, 0.001f)
        harness.drag(330f)
        assertEquals(1f, motion.progress)
        assertTrue("A tap on the open card closes the drawer", motion.isScrimInteractive.value)
        assertTrue(motion.isContentHiddenFromAccessibility.value)
        harness.drag(0f)
        assertEquals(0f, motion.progress)
        assertFalse(motion.isContentHiddenFromAccessibility.value)
    }

    @Test
    fun scrimMutesTheCardInBothAppearances() {
        fun resolved(isDark: Boolean, highContrast: Boolean = false): Pair<Float, Float> {
            val color = DrawerScrim.color(isDark, highContrast)
            return color.red to color.alpha
        }
        // Over a white card the background darkens; over a black card it lifts, so it always
        // separates from the drawer, and white text always recedes.
        val (lightWhite, lightAlpha) = resolved(isDark = false)
        val (darkWhite, darkAlpha) = resolved(isDark = true)
        assertTrue(1 - lightAlpha + lightWhite * lightAlpha < 0.75f)
        assertTrue(darkWhite * darkAlpha > 0.1f)
        assertTrue(1 - darkAlpha + darkWhite * darkAlpha < 0.6f)
        assertTrue(resolved(isDark = false, highContrast = true).second > lightAlpha)
        assertTrue(resolved(isDark = true, highContrast = true).second > darkAlpha)
    }
}
