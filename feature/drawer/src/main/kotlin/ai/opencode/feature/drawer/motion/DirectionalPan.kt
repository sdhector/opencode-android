package ai.opencode.feature.drawer.motion

import ai.opencode.core.ui.BackSwipeRules
import ai.opencode.core.ui.DrawerPanExclusion
import ai.opencode.core.ui.LocalDrawerPanExclusion
import ai.opencode.core.ui.excludedFromDrawerPan
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/**
 * The session drawer's pan. It watches every touch in the `Initial` pass, waits for 8 dp of travel
 * ([BackSwipeRules.decisionDistance]), then picks once: vertical or leftward on a shut drawer stays
 * with the content; rightward on the open drawer's list goes back a level inside a group and
 * reveals the rows' times elsewhere; rightward on a
 * scroller that can still scroll back stays with it except from the leading 28 dp edge; a finger that rested 0.45 s keeps its hold. Once chosen it
 * consumes the pointer. Touches are tested against the layout nodes registered in
 * [DrawerPanRegions].
 */
object DirectionalPan {
    enum class Decision {
        Begin,
        /** Slide the open drawer's rows to show their times. */
        Reveal,
        /** Go back one level from the group the open drawer's list shows. */
        LeaveGroup,
        Fail,
        Wait,
    }

    /** Where a touch began. */
    sealed interface TouchOrigin {
        data object DrawerList : TouchOrigin
        /** The open drawer's list while it shows a group. */
        data object DrawerGroupList : TouchOrigin
        data object DrawerChrome : TouchOrigin
        data object ExposedCard : TouchOrigin
        data class Content(val horizontalScrollerOwnsRightwardDrag: Boolean) : TouchOrigin
    }

    data class CardSwipeOrigin(
        val onCard: Boolean,
        val scrollerOwnsRightwardDrag: Boolean = false,
        val scrollerOwnsLeftwardDrag: Boolean = false,
    )

    /** A card swipes either way, except while a horizontal scroller under the finger can still move that way. */
    fun cardSwipeDecision(origin: CardSwipeOrigin, dx: Float, dy: Float, decisionDistance: Float): Decision {
        if (dx * dx + dy * dy < decisionDistance * decisionDistance) return Decision.Wait
        if (!origin.onCard || abs(dx) <= abs(dy)) return Decision.Fail
        val scrollerOwnsDrag = if (dx > 0) origin.scrollerOwnsRightwardDrag else origin.scrollerOwnsLeftwardDrag
        return if (scrollerOwnsDrag) Decision.Fail else Decision.Begin
    }

    /** [dx] is toward the trailing side (mirror it in RTL); distances in one unit with [decisionDistance]. */
    fun decision(origin: TouchOrigin, presented: Boolean, dx: Float, dy: Float, decisionDistance: Float): Decision {
        if (dx * dx + dy * dy < decisionDistance * decisionDistance) return Decision.Wait
        if (abs(dx) <= abs(dy)) return Decision.Fail
        if (dx <= 0) return if (presented) Decision.Begin else Decision.Fail
        if (presented && origin == TouchOrigin.DrawerGroupList) return Decision.LeaveGroup
        if (presented && origin == TouchOrigin.DrawerList) return Decision.Reveal
        if (!presented && origin == TouchOrigin.Content(horizontalScrollerOwnsRightwardDrag = true)) return Decision.Fail
        return Decision.Begin
    }

    /**
     * A touch that rested before moving (a text selection, a pin's lift, a context menu) keeps
     * doing what the hold began.
     */
    fun yieldsToHold(heldForSeconds: Double, holdBegan: Boolean): Boolean =
        BackSwipeRules.yieldsToHold(heldForSeconds, holdBegan)
}

/** One phase of the drawer pan. Distances in dp, toward the trailing side positive (mirrored in RTL). */
sealed interface DrawerPanEvent {
    /**
     * The pan chose this touch. [isRevealingTimes]: it slides the drawer rows' times rather
     * than the card; [isLeavingGroup]: it takes the list back a level ([DrawerPanRegions.groupBack]).
     * [fingerTranslation] is the travel since touch-down (the card keeps the distance the decision
     * would swallow); [translation] since the pan was chosen.
     */
    data class Began(val isRevealingTimes: Boolean, val fingerTranslation: Float, val translation: Float, val isLeavingGroup: Boolean = false) : DrawerPanEvent
    data class Changed(val isRevealingTimes: Boolean, val fingerTranslation: Float, val translation: Float, val isLeavingGroup: Boolean = false) : DrawerPanEvent
    /** Lifted at [velocity] dp/s. */
    data class Ended(val isRevealingTimes: Boolean, val velocity: Float, val isLeavingGroup: Boolean = false) : DrawerPanEvent
    data class Cancelled(val isRevealingTimes: Boolean, val isLeavingGroup: Boolean = false) : DrawerPanEvent
}

/**
 * The layout nodes the drawer pan tests touches against. Register them with [drawerPanDrawer],
 * [drawerPanList] and [drawerPanCard];
 * provide it as `LocalDrawerPanExclusion` so the card's controls can use
 * `Modifier.excludedFromDrawerPan()`.
 */
@Stable
class DrawerPanRegions : DrawerPanExclusion {
    internal class Scroller(val coordinates: LayoutCoordinates, val canScrollBackward: () -> Boolean, val canScrollForward: () -> Boolean)
    internal class SelectionHandles(val coordinates: LayoutCoordinates, val handleClaims: (Offset) -> Boolean)

    internal var drawer: LayoutCoordinates? = null
    internal var card: LayoutCoordinates? = null
    internal val lists = HashMap<Any, LayoutCoordinates>()
    internal val scrollers = HashMap<Any, Scroller>()
    internal val exclusions = HashMap<Any, LayoutCoordinates>()
    internal val selectionHandles = HashMap<Any, SelectionHandles>()

    /** Set while the open drawer's list shows a group: a rightward drag on the list goes back through it. */
    var groupBack: DrawerGroupBackPan? = null

    override fun updateExclusion(id: Any, coordinates: LayoutCoordinates?) {
        if (coordinates == null) exclusions.remove(id) else exclusions[id] = coordinates
    }

    override fun updateSelectionHandles(id: Any, coordinates: LayoutCoordinates?, handleClaims: (Offset) -> Boolean) {
        if (coordinates == null) selectionHandles.remove(id) else selectionHandles[id] = SelectionHandles(coordinates, handleClaims)
    }

    override fun updateHorizontalScroller(
        id: Any,
        coordinates: LayoutCoordinates?,
        canScrollBackward: () -> Boolean,
        canScrollForward: () -> Boolean,
    ) {
        if (coordinates == null) scrollers.remove(id) else scrollers[id] = Scroller(coordinates, canScrollBackward, canScrollForward)
    }

    /** The horizontal scrollers under [point] (px in [pan]). */
    internal fun scrollersAt(pan: LayoutCoordinates, point: Offset): List<Scroller> =
        scrollers.values.filter { contains(it.coordinates, pan, point) }

    internal fun isExcluded(pan: LayoutCoordinates, point: Offset): Boolean =
        exclusions.values.any { contains(it, pan, point) }

    /** Whether [point] (px in [pan]) lands on a text selection's handle. */
    internal fun selectionHandleClaims(pan: LayoutCoordinates, point: Offset): Boolean {
        for (handles in selectionHandles.values) {
            if (!contains(handles.coordinates, pan, point)) continue
            if (handles.handleClaims(handles.coordinates.localPositionOf(pan, point))) return true
        }
        return false
    }

    internal fun contains(region: LayoutCoordinates?, pan: LayoutCoordinates, point: Offset): Boolean {
        if (region == null || !region.isAttached || !pan.isAttached) return false
        val local = region.localPositionOf(pan, point)
        return local.x >= 0 && local.y >= 0 && local.x < region.size.width && local.y < region.size.height
    }
}

private fun Modifier.region(regions: DrawerPanRegions, set: (Any, LayoutCoordinates?) -> Unit): Modifier = composed {
    val id = remember { Any() }
    DisposableEffect(regions) { onDispose { set(id, null) } }
    onGloballyPositioned { set(id, it) }
}

/** The drawer. */
fun Modifier.drawerPanDrawer(regions: DrawerPanRegions): Modifier =
    region(regions) { _, c -> regions.drawer = c }

/** The drawer's session list: a rightward drag on the open drawer reveals times. */
fun Modifier.drawerPanList(regions: DrawerPanRegions): Modifier =
    region(regions) { id, c -> if (c == null) regions.lists.remove(id) else regions.lists[id] = c }

/** The session card. Put it on the translated card itself. */
fun Modifier.drawerPanCard(regions: DrawerPanRegions): Modifier =
    region(regions) { _, c -> regions.card = c }

/**
 * The drawer's pan over the whole drawer container (put it on the untransformed root that holds
 * the drawer and the card). Reads its inputs as lambdas so it never restarts mid-gesture.
 *
 * @param isContentPushed a subagent is pushed over the session: the back pan has the drag, and
 *   only the root session's drag opens the drawer.
 * @param onTouchDown a touch that may move the drawer began (not in Catch Up).
 */
fun Modifier.drawerDirectionalPan(
    regions: DrawerPanRegions,
    isDrawerPresented: () -> Boolean,
    isContentPushed: () -> Boolean = { false },
    enabled: () -> Boolean = { true },
    onTouchDown: () -> Unit = {},
    onPan: (DrawerPanEvent) -> Unit,
): Modifier = composed {
    val panCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val layoutDirection = LocalLayoutDirection.current
    Modifier
        .onGloballyPositioned { panCoordinates[0] = it }
        .pointerInput(regions, layoutDirection) {
            val decisionDistance = BackSwipeRules.decisionDistance.toPx()
            val edgeWidth = BackSwipeRules.edgeWidth.toPx()
            val rtl = layoutDirection == LayoutDirection.Rtl
            val sign = if (rtl) -1f else 1f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val pan = panCoordinates[0] ?: return@awaitEachGesture
                if (!enabled()) return@awaitEachGesture
                val presented = isDrawerPresented()
                val point = down.position
                // Android's modal drawer lies over the card: a touch on the open drawer is the drawer's.
                val onExposedCard = regions.contains(regions.card, pan, point) &&
                    !(presented && regions.contains(regions.drawer, pan, point))
                // Controls that own a sideways drag (the + menu, the held mic) keep it.
                if (!presented && onExposedCard && regions.isExcluded(pan, point)) {
                    return@awaitEachGesture
                }
                // A text selection's handle drags the handle, even at the edge.
                if (!presented && regions.selectionHandleClaims(pan, point)) return@awaitEachGesture
                if (!presented && isContentPushed()) return@awaitEachGesture
                val startedAtEdge = !presented &&
                    (if (rtl) point.x >= size.width - edgeWidth else point.x <= edgeWidth)
                onTouchDown()

                // Every registered region under the finger.
                val scrollersHere = regions.scrollers.values.filter { regions.contains(it.coordinates, pan, point) }
                val ownsRightward = scrollersHere.any { if (rtl) it.canScrollForward() else it.canScrollBackward() }
                val hitDrawer = regions.contains(regions.drawer, pan, point)
                val isDrawerList = regions.lists.values.any { regions.contains(it, pan, point) }
                val touchOrigin: DirectionalPan.TouchOrigin = if (presented) {
                    when {
                        isDrawerList && hitDrawer && regions.groupBack != null -> DirectionalPan.TouchOrigin.DrawerGroupList
                        isDrawerList && hitDrawer -> DirectionalPan.TouchOrigin.DrawerList
                        onExposedCard -> DirectionalPan.TouchOrigin.ExposedCard
                        else -> DirectionalPan.TouchOrigin.DrawerChrome
                    }
                } else {
                    // At the edge the drag is the drawer's; elsewhere a scroller keeps it.
                    DirectionalPan.TouchOrigin.Content(horizontalScrollerOwnsRightwardDrag = ownsRightward && !startedAtEdge)
                }

                val pointerId = down.id
                val downTime = down.uptimeMillis
                val tracker = VelocityTracker()
                tracker.addPointerInputChange(down)
                var holdBegan = false
                var chosen = false
                var revealing = false
                var leaving = false
                var chosenAt = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    if (change == null || !change.pressed) {
                        if (chosen) {
                            if (change != null) {
                                tracker.addPointerInputChange(change)
                                change.consume()
                                val velocity = tracker.calculateVelocity().x * sign / density
                                onPan(DrawerPanEvent.Ended(revealing, velocity, leaving))
                            } else {
                                onPan(DrawerPanEvent.Cancelled(revealing, leaving))
                            }
                        }
                        return@awaitEachGesture
                    }
                    tracker.addPointerInputChange(change)
                    val delta = change.position - point
                    val dx = delta.x * sign
                    if (chosen) {
                        change.consume()
                        val finger = dx / density
                        onPan(DrawerPanEvent.Changed(revealing, finger, finger - chosenAt, leaving))
                        continue
                    }
                    val decision = DirectionalPan.decision(touchOrigin, presented, dx, delta.y, decisionDistance)
                    when (decision) {
                        DirectionalPan.Decision.Wait -> {
                            // Children see this move next; one that consumes it before the pan chose
                            // (a long press's drag, a text selection) began a hold.
                            val final = awaitPointerEvent(PointerEventPass.Final)
                            if (final.changes.any { it.id == pointerId && it.positionChangeIgnoreConsumed() != Offset.Zero && it.isConsumed }) {
                                holdBegan = true
                            }
                        }
                        DirectionalPan.Decision.Fail -> return@awaitEachGesture
                        DirectionalPan.Decision.Begin, DirectionalPan.Decision.Reveal, DirectionalPan.Decision.LeaveGroup -> {
                            val heldFor = (change.uptimeMillis - downTime) / 1000.0
                            if (!startedAtEdge && DirectionalPan.yieldsToHold(heldFor, holdBegan)) {
                                return@awaitEachGesture
                            }
                            chosen = true
                            revealing = decision == DirectionalPan.Decision.Reveal
                            leaving = decision == DirectionalPan.Decision.LeaveGroup
                            change.consume()
                            val finger = dx / density
                            chosenAt = finger
                            onPan(DrawerPanEvent.Began(revealing, finger, 0f, leaving))
                        }
                    }
                }
            }
        }
}
