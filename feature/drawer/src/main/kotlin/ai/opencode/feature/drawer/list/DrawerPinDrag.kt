package ai.opencode.feature.drawer.list

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.retarget
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Spring motion across Pinned and Recents while a session row is dragged to pin, unpin, or reorder.
// Each visible header and row reads its entry of [translations] in its own `graphicsLayer`, so a
// drag never recomposes the list. On drop, `completeDrop` mutates the store and the list applies it
// in the same frame ([DrawerPinDragHost.flushNow]), so nothing jumps. Positions are dp in the
// list's own coordinates (content padding included). The drag consumes the finger, so the list only
// scrolls under it through [autoScroll].

/** What [DrawerPinDrag] reads from and asks of the list it moves. */
interface DrawerPinDragHost {
    /** The visible headers and rows, untransformed. */
    fun visibleItems(): List<DrawerPinDragItem>

    fun groupAt(y: Float): String? = null

    fun dropInGroup(sessionID: String, groupID: String) {}

    /** The list's width, dp. */
    val width: Float

    /** The top of the list's visible content, dp. */
    val viewportTop: Float

    /** The list's height, dp. */
    val height: Float get() = Float.MAX_VALUE

    /** The bottom of the drawer's header over the list, dp from the list's top. */
    val headerBottom: Float get() = 0f

    /** The top of the drawer's footer over the list, dp from the list's top. */
    val footerTop: Float get() = Float.MAX_VALUE

    /** The window, in the list's dp coordinates. */
    val window: Rect get() = Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)

    /** Applies the store's change now, so this frame lays out the rows in their new places. */
    fun flushNow()

    /** Keeps the scroll position by index across the drop's reorder. */
    fun preserveScroll()

    /** Scrolls the list by [delta] dp (positive moves its content up), as far as it goes. */
    fun scrollBy(delta: Float) {}

    /** A scope on the composition's frame clock, and its frame duration. */
    val scope: CoroutineScope
    val frameDurationSeconds: Double
}

/** One visible header or row: its key, untransformed top and height, in dp. */
data class DrawerPinDragItem(val key: DrawerPinDrag.ItemKey, val minY: Float, val height: Float) {
    val maxY: Float get() = minY + height
}

/** The empty-pins drop zone while it shows: where, how open, how hovered. */
data class DrawerPinDropZone(
    val top: Float,
    val height: Float,
    val openProgress: Float,
    val hoverProgress: Float,
    val headerHeight: Float,
    val reducedMotion: Boolean,
)

@Stable
class DrawerPinDrag {
    sealed interface HoverTarget {
        data class Pinned(val index: Int) : HoverTarget
        data object Recent : HoverTarget
    }

    sealed interface ItemKey {
        data object PinnedHeader : ItemKey
        data object RecentsHeader : ItemKey
        data class Row(val id: String) : ItemKey
    }

    companion object {
        /** A release over the chrome this far beside the list still lands, dp. */
        const val CANCEL_MARGIN = 24f

        /**
         * Whether a release outside the list's body still lands the row in its slot: over the
         * header (above [headerBottom]) or the footer (below [footerTop]), within the list's column
         * (± [CANCEL_MARGIN]) and the [window]. Anywhere else the row springs back. All in dp.
         */
        fun landsOnCancel(finger: Offset, list: Rect, headerBottom: Float, footerTop: Float, window: Rect): Boolean {
            if (!window.contains(finger) || finger.x < list.left - CANCEL_MARGIN || finger.x > list.right + CANCEL_MARGIN) return false
            return finger.y < headerBottom || finger.y > footerTop
        }

        /** The finger's speed over its last 100 ms, clamped to this, dp/s. */
        const val RELEASE_VELOCITY_LIMIT = 4000.0

        /** The system's drop animation, at most 0.55 s. */
        const val LANDING_FALLBACK_MILLIS = 550L
    }

    // ---- Output, read by the rows in their layers ----

    /** Each row's and header's vertical translation in dp. */
    val translations = mutableStateMapOf<ItemKey, Float>()

    /** The Pinned header's opacity while its only row is dragged out. */
    var pinnedHeaderAlpha: Float by mutableFloatStateOf(1f)
        internal set

    /** The empty-pins drop zone, while it shows. */
    var dropZone: DrawerPinDropZone? by mutableStateOf(null)
        internal set

    var hoveredGroupID: String? by mutableStateOf(null)
        private set

    internal fun updateGroupHover(y: Float) {
        hoveredGroupID = host?.let { if (y >= it.headerBottom && y < it.footerTop) it.groupAt(y) else null }
    }

    /** The dragged row, while a drag or its landing runs. */
    var draggingID: String? by mutableStateOf(null)
        internal set

    /**
     * How lifted the dragged row is, 0…1: it grows by [DrawerTunables.pinLiftScale] on
     * `MotionRole.MicroIn` as it lifts, and settles back as it lands.
     */
    var liftProgress: Float by mutableFloatStateOf(0f)
        internal set

    fun translation(key: ItemKey): Float = translations[key] ?: 0f

    /** Pin, Unpin and Move outside a drag: see [DrawerPinMove]. */
    internal val pinMove = DrawerPinMove(this)

    /** A row's opacity while a Pin or Unpin cross-fades it in under Reduce Motion. */
    fun alpha(key: ItemKey): Float = pinMove.alphas[key] ?: 1f

    // ---- Drag state ----

    internal var host: DrawerPinDragHost? = null
    private var frameJob: Job? = null
    private var landingFallback: Job? = null

    var activeSessionID: String? = null
        internal set
    internal var startedInPinned = false
    internal var pinnedIDs: List<String> = emptyList()
    internal var otherPinnedIDs: List<String> = emptyList()
    internal var recentIDs: List<String> = emptyList()
    internal var recentInsertionIndex = 0
    internal var showsShortcuts = false
    internal var reducedMotion = false

    internal var hoverTarget: HoverTarget = HoverTarget.Recent
    internal var initialFingerY = 0f
    internal var lastFingerY = 0f

    /** The finger's last 100 ms, (uptime ms, y dp), for the release velocity. */
    private val samples = ArrayDeque<Pair<Long, Float>>()

    /** The lift's spring. */
    private var liftSpring = SpringSolver(MotionRole.MicroIn, value = 0.0, target = 0.0)
    /**
     * Where the finger holds the lifted row, from its centre. Android shows the row itself, so it
     * keeps the grip.
     */
    internal var grabOffset = 0f
    internal var hasRevealedEmptyZone = false
    internal var hasCommittedDrop = false
    internal var isLandingPlatter = false
    internal var isCancellingEmptyZone = false
    private var hasNotifiedDropReleased = false
    /** The landing fallback fired: commit in the next frame (see [scheduleLandingFallback]). */
    private var commitRequested = false
    /** The Pinned header's start after the first pin lands, applied once it's laid out. */
    internal var pendingPinnedHeaderOffset: Float? = null

    internal var cachedTopMinY = 0f
    internal var cachedFirstPinnedMinY = 0f
    internal var cachedFirstRecentMinY = 0f
    internal var cachedHeaderHeight = 56f
    /** The drag has scrolled the list ([autoScroll]): rows coming into view start in their slots. */
    internal var hasAutoScrolled = false

    internal val cellSprings = HashMap<ItemKey, SpringSolver>()
    internal var emptyZoneSpring = SpringSolver(MotionRole.Layout, value = 0.0, target = 0.0)
    internal var hoverSpring = SpringSolver(MotionRole.Swap, value = 0.0, target = 0.0)

    private var onZoneCrossed: ((Boolean) -> Unit)? = null
    private var onSlotChanged: (() -> Unit)? = null
    private var onDropReleased: ((String, Int?) -> Unit)? = null
    private var onDrop: ((String, Int?) -> Unit)? = null
    private var onSetLifted: ((String?) -> Unit)? = null

    val isDisplayLinkRunning: Boolean get() = frameJob?.isActive == true

    val rowStride: Float get() = DrawerMetrics.rowHeight.value + DrawerTunables.rowGap.value

    /** Rows and the empty zone make room on `MotionRole.Layout`. */
    internal fun makeSpring(value: Float, target: Float): SpringSolver = SpringSolver(
        MotionRole.Layout,
        value = value.toDouble(),
        target = target.toDouble(),
        reduceMotion = reducedMotion,
        positionEpsilon = 0.08,
        velocityEpsilon = 0.5,
    )

    /** The hovered section's highlight changes on `MotionRole.Swap`. */
    internal fun makeHoverSpring(value: Double): SpringSolver =
        SpringSolver(MotionRole.Swap, value = value, target = value, reduceMotion = reducedMotion)

    /** The list this drag moves. */
    fun attach(host: DrawerPinDragHost) {
        this.host = host
    }

    fun detach(host: DrawerPinDragHost) {
        if (this.host !== host) return
        // The list is gone: end the drag and leave no spring, translation or drop zone behind for
        // the next list.
        cancelDrag()
        resetAllCellsAndCleanup()
        this.host = null
    }

    fun beginDrag(
        sessionID: String,
        isPinnedSection: Boolean,
        pinnedIDs: List<String>,
        recentIDs: List<String>,
        unpinnedRecentsIndex: Int,
        showsShortcuts: Boolean,
        reducedMotion: Boolean,
        fingerY: Float,
        onZoneCrossed: (Boolean) -> Unit,
        onSlotChanged: () -> Unit,
        onDropReleased: (String, Int?) -> Unit = { _, _ -> },
        onDrop: (String, Int?) -> Unit,
        onSetLifted: (String?) -> Unit,
    ) {
        resetAllCellsAndCleanup()
        val host = host ?: return

        activeSessionID = sessionID
        draggingID = sessionID
        startedInPinned = isPinnedSection
        this.pinnedIDs = pinnedIDs
        otherPinnedIDs = pinnedIDs.filter { it != sessionID }
        this.recentIDs = recentIDs
        recentInsertionIndex = unpinnedRecentsIndex.coerceIn(0, recentIDs.size)
        this.showsShortcuts = showsShortcuts
        this.reducedMotion = reducedMotion
        this.onZoneCrossed = onZoneCrossed
        this.onSlotChanged = onSlotChanged
        this.onDropReleased = onDropReleased
        this.onDrop = onDrop
        this.onSetLifted = onSetLifted
        onSetLifted(sessionID)

        val initialPinIdx = pinnedIDs.indexOf(sessionID).coerceAtLeast(0)
        hoverTarget = if (isPinnedSection) HoverTarget.Pinned(initialPinIdx) else HoverTarget.Recent
        hasRevealedEmptyZone = false
        hasCommittedDrop = false
        isLandingPlatter = false
        isCancellingEmptyZone = false
        hasNotifiedDropReleased = false
        commitRequested = false
        pendingPinnedHeaderOffset = null

        initialFingerY = fingerY
        lastFingerY = fingerY
        hasAutoScrolled = false
        samples.clear()
        // It lifts out on `MotionRole.MicroIn`; under Reduce Motion it's lifted at once.
        liftSpring = if (reducedMotion) {
            SpringSolver(MotionRole.MicroIn, value = 1.0, target = 1.0)
        } else {
            SpringSolver(MotionRole.MicroIn, value = 0.0, target = 1.0, positionEpsilon = 0.002, velocityEpsilon = 0.02)
        }
        liftProgress = liftSpring.value.toFloat()

        captureInitialGeometry()
        grabOffset = visibleItem(ItemKey.Row(sessionID))?.let { fingerY - (it.minY + it.height / 2) } ?: 0f
        emptyZoneSpring = makeSpring(0f, 0f)
        hoverSpring = makeHoverSpring(if (isPinnedSection) 1.0 else 0.0)
        startFrameLoop(host)
    }

    private fun startFrameLoop(host: DrawerPinDragHost) {
        if (isDisplayLinkRunning) return
        frameJob = host.scope.launch {
            motionFrameLoop(frameDurationSeconds = host.frameDurationSeconds) { tick -> step(tick.dt) }
        }
    }

    /** The finger moved to [fingerY] (dp, list coordinates) at [uptimeMillis] (the pointer event's). */
    fun updateDrag(fingerY: Float, uptimeMillis: Long? = null) {
        if (activeSessionID == null || hasCommittedDrop || isLandingPlatter) return
        lastFingerY = fingerY
        if (uptimeMillis != null) {
            samples.addLast(uptimeMillis to fingerY)
            while (samples.isNotEmpty() && uptimeMillis - samples.first().first > 100) samples.removeFirst()
        }
        positionLiftedRowUnderFinger()
        updateGroupHover(fingerY)
        evaluateHover(fingerY)
        retargetSprings()
    }

    private fun positionLiftedRowUnderFinger() {
        val draggedID = activeSessionID ?: return
        val item = visibleItem(ItemKey.Row(draggedID)) ?: return
        val targetMinY = lastFingerY - grabOffset - item.height / 2
        cellSprings[item.key]?.let {
            it.value = targetMinY.toDouble()
            it.velocity = 0.0
        }
        translations[item.key] = targetMinY - item.minY
    }

    /**
     * The finger lifted at [fingerX]/[fingerY]: over the list's body it drops (lands the row in its
     * slot, then commits); elsewhere it lands over the header or footer ([landsOnCancel]) and
     * springs back anywhere else.
     */
    fun endDrag(fingerX: Float, fingerY: Float) {
        val sessionID = activeSessionID ?: return
        if (hasCommittedDrop) return
        val host = host
        val listWidth = host?.width?.takeIf { it > 0f } ?: Float.MAX_VALUE
        val listHeight = host?.height?.takeIf { it > 0f } ?: Float.MAX_VALUE
        val headerBottom = host?.headerBottom ?: 0f
        val footerTop = host?.footerTop ?: Float.MAX_VALUE
        val overBody = fingerX in 0f..listWidth && fingerY >= headerBottom && fingerY <= footerTop
        val groupID = if (overBody) host?.groupAt(fingerY) else null
        if (groupID != null) {
            resetAllCellsAndCleanup()
            host?.dropInGroup(sessionID, groupID)
            return
        }
        val window = host?.window ?: Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val list = Rect(0f, 0f, listWidth, listHeight)
        if (!overBody && !landsOnCancel(Offset(fingerX, fingerY), list, headerBottom, footerTop, window)) {
            cancelDrag(deferLiftedClear = true)
            return
        }
        updateDrag(fingerY)
        beginLanding()
    }

    /** The finger's speed over its last 100 ms, dp/s. */
    private fun releaseVelocity(): Double {
        val first = samples.firstOrNull() ?: return 0.0
        val last = samples.last()
        val dt = (last.first - first.first) / 1000.0
        if (dt <= 0.008) return 0.0
        return ((last.second - first.second) / dt).coerceIn(-RELEASE_VELOCITY_LIMIT, RELEASE_VELOCITY_LIMIT)
    }

    /** The slot the lifted row lands in on drop, as its top in dp. */
    fun landingSlotMinY(): Float? {
        val draggedID = activeSessionID ?: return null
        val stride = rowStride
        return when (val hover = hoverTarget) {
            is HoverTarget.Pinned ->
                if (pinnedIDs.isEmpty()) cachedTopMinY + cachedHeaderHeight else cachedFirstPinnedMinY + hover.index * stride
            HoverTarget.Recent -> if (!startedInPinned) {
                visibleItem(ItemKey.Row(draggedID))?.minY ?: run {
                    val r = recentIDs.indexOf(draggedID).takeIf { it >= 0 } ?: recentInsertionIndex
                    recentSlotMinY(r, shiftBeforeR = 0f)
                }
            } else {
                val shiftBeforeR = if (otherPinnedIDs.isEmpty()) -(cachedHeaderHeight + stride) else -stride
                recentSlotMinY(recentInsertionIndex, shiftBeforeR)
            }
        }
    }

    /**
     * Cancels an active or landing drag without mutating pins or playing a commit haptic,
     * springing shifted rows (the lifted one included) and any open empty-pin zone back to rest.
     */
    fun cancelDrag(deferLiftedClear: Boolean = false) {
        hoveredGroupID = null
        landingFallback?.cancel()
        landingFallback = null

        val hadActiveDrag = activeSessionID != null || isDisplayLinkRunning || cellSprings.isNotEmpty()
        if (!hadActiveDrag) return

        val clearLifted = onSetLifted

        onDrop = null
        onDropReleased = null
        onZoneCrossed = null
        onSlotChanged = null
        hasNotifiedDropReleased = true
        activeSessionID = null
        hasCommittedDrop = true
        isLandingPlatter = false
        hasRevealedEmptyZone = false

        val host = host
        if (host == null) {
            clearLifted?.invoke(null)
            resetAllCellsAndCleanup()
            return
        }

        isCancellingEmptyZone = emptyZoneSpring.value > 0.5
        emptyZoneSpring.retarget(0.0)
        liftSpring.retarget(0.0, MotionRole.MicroIn, MotionPhase.Exit, reduceMotion = reducedMotion)
        hoverSpring.retarget(if (startedInPinned) 1.0 else 0.0)
        retargetSprings()

        if (!deferLiftedClear) {
            onSetLifted = null
            clearLifted?.invoke(null)
        } else {
            scheduleLandingFallback()
        }

        if (!deferLiftedClear && allSpringsSettled()) {
            resetAllCellsAndCleanup()
        } else {
            startFrameLoop(host)
        }
    }

    private val targetPinnedIndex: Int?
        get() = (hoverTarget as? HoverTarget.Pinned)?.index

    private fun willMutateStore(draggedID: String): Boolean = when (val hover = hoverTarget) {
        is HoverTarget.Pinned -> !startedInPinned || pinnedIDs.indexOf(draggedID) != hover.index
        HoverTarget.Recent -> startedInPinned
    }

    private fun notifyDropReleasedIfNeeded(draggedID: String) {
        if (hasNotifiedDropReleased) return
        hasNotifiedDropReleased = true
        onDropReleased?.invoke(draggedID, targetPinnedIndex)
    }

    /**
     * Freezes the hover target when the finger lifts (the reducer plays the commit haptic for a
     * drop that changes pins), and closes the drop zone if the drag returned to Recents with no
     * pins. The store mutation runs in [completeDrop] once the row has landed.
     */
    fun beginLanding() {
        val draggedID = activeSessionID
        if (hasCommittedDrop || draggedID == null) return
        isLandingPlatter = true
        notifyDropReleasedIfNeeded(draggedID)
        if (!willMutateStore(draggedID) && pinnedIDs.isEmpty() && emptyZoneSpring.value > 0.5) {
            isCancellingEmptyZone = true
            emptyZoneSpring.retarget(0.0)
            hoverSpring.retarget(0.0)
        }
        retargetSprings()
        // The row springs from where it is, at the finger's speed, into its slot on
        // `MotionRole.Release`, and settles its lift on the way.
        cellSprings[ItemKey.Row(draggedID)]?.let { row ->
            cellSprings[ItemKey.Row(draggedID)] = SpringSolver(
                MotionRole.Release,
                value = row.value,
                velocity = releaseVelocity(),
                target = row.target,
                reduceMotion = reducedMotion,
                positionEpsilon = 0.3,
                velocityEpsilon = 12.0,
            )
        }
        liftSpring.retarget(0.0, MotionRole.MicroIn, MotionPhase.Exit, reduceMotion = reducedMotion)
        scheduleLandingFallback()
    }

    /**
     * Commits the pin/unpin/reorder once the row has landed, publishing each row's translation
     * against its new place so no row jumps by even a fraction of a dp.
     */
    fun completeDrop() {
        landingFallback?.cancel()
        landingFallback = null

        val draggedID = activeSessionID
        if (hasCommittedDrop || draggedID == null) {
            finishLanding()
            return
        }
        hasCommittedDrop = true
        isLandingPlatter = false
        commitRequested = false

        val host = host
        if (host == null) {
            resetAllCellsAndCleanup()
            return
        }

        val targetPinnedIndex = targetPinnedIndex
        notifyDropReleasedIfNeeded(draggedID)

        if (!willMutateStore(draggedID)) {
            onSetLifted?.invoke(null)
            retargetSprings()
            if (allSpringsSettled()) resetAllCellsAndCleanup()
            return
        }

        val wasEmptyPins = pinnedIDs.isEmpty() && targetPinnedIndex != null
        val openZoneDelta = if (wasEmptyPins) emptyZoneSpring.value.toFloat() - (cachedHeaderHeight + rowStride) else 0f

        // Each row lands where its target was (the targets are its slot after the drop): after the
        // store change this frame lays it out there, so its translation is what's left of its
        // spring, the lifted row included.
        val visible = host.visibleItems()
        for (item in visible) {
            val spring = cellSprings[item.key] ?: continue
            val residual = (spring.value - spring.target).toFloat()
            setTranslation(item.key, residual)
        }
        if (wasEmptyPins) {
            val start = openZoneDelta * (cachedHeaderHeight / max(cachedHeaderHeight + rowStride, 1f))
            pendingPinnedHeaderOffset = start
            setTranslation(ItemKey.PinnedHeader, start)
            dropZone = null
        }
        // The rows' bases change with the drop: start their springs again from where they show.
        val residuals = visible.associate { it.key to (translations[it.key] ?: 0f) }
        cellSprings.clear()
        pendingResiduals = residuals

        host.preserveScroll()
        onDrop?.invoke(draggedID, targetPinnedIndex)
        onSetLifted?.invoke(null)
        startFrameLoop(host)
    }

    /** Translations carried across the drop, until the rows' new places are laid out. */
    private var pendingResiduals: Map<ItemKey, Float>? = null

    fun finishLanding() {
        landingFallback?.cancel()
        landingFallback = null
        isLandingPlatter = false
        onSetLifted?.invoke(null)
        onSetLifted = null
        if (host == null || allSpringsSettled()) resetAllCellsAndCleanup()
    }

    private fun scheduleLandingFallback() {
        landingFallback?.cancel()
        val host = host ?: return
        landingFallback = host.scope.launch {
            // 0.55 s of the system's drop animation, slowed with the lab time scale.
            delay((LANDING_FALLBACK_MILLIS / max(0.1, AnimationTimeScale.shared.scale)).toLong())
            landingFallback = null
            if (activeSessionID != null && !hasCommittedDrop) {
                // Commit from the frame loop, so the store change is laid out in the frame that
                // publishes the rows' carried-over translations (a commit between frames would
                // let the next step read the old layout as the new one).
                if (isDisplayLinkRunning) commitRequested = true else completeDrop()
            } else {
                finishLanding()
            }
        }
    }

    fun resetAllCellsAndCleanup() {
        hoveredGroupID = null
        pinMove.cancel()
        landingFallback?.cancel()
        landingFallback = null
        frameJob?.cancel()
        frameJob = null
        translations.clear()
        pinnedHeaderAlpha = 1f
        liftProgress = 0f
        samples.clear()
        dropZone = null
        cellSprings.clear()
        pendingResiduals = null
        pendingPinnedHeaderOffset = null
        if (activeSessionID != null || onSetLifted != null) onSetLifted?.invoke(null)
        activeSessionID = null
        draggingID = null
        onDrop = null
        onDropReleased = null
        onZoneCrossed = null
        onSlotChanged = null
        onSetLifted = null
        hasCommittedDrop = false
        isLandingPlatter = false
        isCancellingEmptyZone = false
        hasNotifiedDropReleased = false
        commitRequested = false
    }

    internal fun setTranslation(key: ItemKey, value: Float) {
        val shown = if (kotlin.math.abs(value) > 0.02f) value else 0f
        if (shown == 0f) {
            if (translations.containsKey(key)) translations.remove(key)
        } else if (translations[key] != shown) {
            translations[key] = shown
        }
    }

    // ---- Frame step ----

    internal fun step(dt: Double): MotionFrameAction {
        val host = host
        if (host == null) {
            resetAllCellsAndCleanup()
            return MotionFrameAction.Stop
        }
        var keyed = host.visibleItems()

        // After a drop: the rows' new places are laid out; carry over what showed.
        pendingResiduals?.let { residuals ->
            pendingResiduals = null
            for (item in keyed) {
                val shown = residuals[item.key]
                    ?: if (item.key == ItemKey.PinnedHeader) pendingPinnedHeaderOffset ?: 0f else 0f
                cellSprings[item.key] = makeSpring(item.minY + shown, item.minY)
            }
            pendingPinnedHeaderOffset = null
        }

        if (autoScroll(dt)) {
            keyed = host.visibleItems()
            updateGroupHover(lastFingerY)
            evaluateHover(lastFingerY)
        }

        retargetSprings(keyed)
        emptyZoneSpring.step(dt)
        hoverSpring.step(dt)
        liftSpring.step(dt)
        liftProgress = liftSpring.value.toFloat().coerceAtLeast(0f)

        val visibleKeys = HashSet<ItemKey>(keyed.size)
        for (item in keyed) {
            visibleKeys += item.key
            val spring = cellSprings.getOrPut(item.key) { makeSpring(item.minY, item.minY) }
            val key = item.key
            // The lifted row follows the finger while dragging.
            if (key is ItemKey.Row && key.id == activeSessionID && !hasCommittedDrop && !isLandingPlatter) {
                spring.value = (lastFingerY - grabOffset - item.height / 2).toDouble()
                spring.velocity = 0.0
            } else {
                spring.step(dt)
            }
            setTranslation(key, (spring.value - item.minY).toFloat())
        }
        // Rows scrolled out of the list keep no translation.
        if (translations.keys.any { it !in visibleKeys }) {
            translations.keys.filter { it !in visibleKeys }.forEach { translations.remove(it) }
        }
        pinnedHeaderAlpha = if (startedInPinned && otherPinnedIDs.isEmpty() && !hasCommittedDrop) {
            hoverSpring.value.toFloat().coerceIn(0f, 1f)
        } else {
            1f
        }

        updateEmptyZone()

        // The lifted row has landed in its slot: commit.
        if (isLandingPlatter && !hasCommittedDrop) {
            val draggedID = activeSessionID
            val dragged = draggedID?.let { cellSprings[ItemKey.Row(it)] }
            if (commitRequested || dragged == null || dragged.isSettled) completeDrop()
            return MotionFrameAction.Continue
        }
        if (hasCommittedDrop && !isLandingPlatter && pendingResiduals == null && allSpringsSettled(keyed)) {
            // A cancelled drag's lifted row has landed back: let it go.
            landingFallback?.cancel()
            landingFallback = null
            frameJob = null
            resetAllCellsAndCleanup()
            return MotionFrameAction.Stop
        }
        return MotionFrameAction.Continue
    }

    /**
     * A finger held within [DrawerTunables.pinEdgeBand] of the list's top or bottom (inside the
     * header and footer) scrolls it, faster the deeper it goes (`pinEdgeSpeed · depth · |depth|` dp
     * a second), and the slots follow the finger through the content. The lifted row is the list's
     * own cell, so the scroll stops before the dragged row's slot would leave the list and take the
     * row with it. Returns whether the rows moved.
     */
    internal fun autoScroll(dt: Double): Boolean {
        val host = host ?: return false
        val draggedID = activeSessionID ?: return false
        if (hasCommittedDrop || isLandingPlatter || dt <= 0) return false
        val top = host.viewportTop
        val bottom = min(host.footerTop, host.height)
        val band = max(DrawerTunables.pinEdgeBand.value, 1f)
        val y = lastFingerY
        val depth = when {
            y < top + band -> -min((top + band - y) / band, 1f)
            y > bottom - band -> min((y - (bottom - band)) / band, 1f)
            else -> return false
        }
        val dragged = visibleItem(ItemKey.Row(draggedID)) ?: return false
        val speed = DrawerTunables.pinEdgeSpeed.value * depth * abs(depth)
        val step = (speed * dt).toFloat()
        val delta = if (step > 0) min(step, max(dragged.minY, 0f)) else max(step, -max(host.height - dragged.maxY, 0f))
        if (abs(delta) < 0.01f) return false
        host.scrollBy(delta)
        val moved = (visibleItem(dragged.key)?.minY ?: return false) - dragged.minY
        if (abs(moved) < 0.01f) return false
        shiftGeometry(moved)
        return true
    }

    /** The rows moved [by] dp under a scroll: what was measured against them moves with them. */
    private fun shiftGeometry(by: Float) {
        hasAutoScrolled = true
        cachedTopMinY += by
        cachedFirstPinnedMinY += by
        cachedFirstRecentMinY += by
        initialFingerY += by
        for (spring in cellSprings.values) {
            spring.value += by
            spring.target += by
        }
    }

    internal fun allSpringsSettled(keyed: List<DrawerPinDragItem>? = null): Boolean {
        if (!emptyZoneSpring.isSettled || !liftSpring.isSettled) return false
        for (item in keyed ?: host?.visibleItems().orEmpty()) {
            val spring = cellSprings[item.key] ?: continue
            if (!spring.isSettled) return false
        }
        return true
    }

    private fun updateEmptyZone() {
        val shouldShow = pinnedIDs.isEmpty() && !startedInPinned &&
            (!hasCommittedDrop || isCancellingEmptyZone) &&
            (emptyZoneSpring.value > 0.5 || emptyZoneSpring.target > 0.5)
        if (!shouldShow) {
            if (dropZone != null) dropZone = null
            return
        }
        val fullHeight = max(cachedHeaderHeight + rowStride, 1f)
        dropZone = DrawerPinDropZone(
            top = cachedTopMinY,
            height = max(emptyZoneSpring.value.toFloat(), 1f),
            openProgress = (emptyZoneSpring.value.toFloat() / fullHeight).coerceIn(0f, 1f),
            hoverProgress = hoverSpring.value.toFloat().coerceIn(0f, 1f),
            headerHeight = cachedHeaderHeight,
            reducedMotion = reducedMotion,
        )
    }

    internal fun notifyZoneCrossed(entered: Boolean) {
        onZoneCrossed?.invoke(entered)
    }

    internal fun notifySlotChanged() {
        onSlotChanged?.invoke()
    }
}
