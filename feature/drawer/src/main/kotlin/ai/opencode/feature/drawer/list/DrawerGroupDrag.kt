package ai.opencode.feature.drawer.list

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.retarget
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.feature.drawer.list.DrawerPinDrag.ItemKey
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

// A group header held and dragged to reorder the groups. The header rides the finger and its rows
// fade and fold under it, so the group travels as one header between the others, which make room
// on `MotionRole.Layout`. Once it lands, the store takes the new order and the rows unfold under it
// in the same frame ([DrawerPinDragHost.flushNow]). Each item's spring holds where the item shows
// (dp, the list's coordinates) rather than an offset, so the fold, the new order and the unfold all
// move from where the rows were. Pinned, New Group and Recents keep their places. It shares the
// list's translations and alphas with [DrawerPinDrag], which owns it; one drag runs at a time.

/** Where the landed group's header showed, so the list keeps it there through the new order. */
data class DrawerGroupDragAnchor(val groupID: String, val top: Float)

@Stable
class DrawerGroupDrag internal constructor(private val pinDrag: DrawerPinDrag) {
    private enum class Phase { Folding, Dragging, Landing, Settling }

    /** The dragged group, from the lift until its rows have come back. */
    var draggingID: String? by mutableStateOf(null)
        private set

    /** The group whose rows the list leaves out while its header is lifted. */
    var foldedID: String? by mutableStateOf(null)
        private set

    /** How lifted the header is, 0…1, on `MotionRole.MicroIn` as for a lifted row. */
    var liftProgress: Float by mutableFloatStateOf(0f)
        private set

    /** The dragged group's rows' opacity while they fold and come back. */
    internal val alphas = mutableStateMapOf<ItemKey, Float>()

    val isActive: Boolean get() = draggingID != null

    private var phase = Phase.Folding
    private var order: List<String> = emptyList()
    private var rowsOf: () -> Map<String, List<String>> = { emptyMap() }
    private var foldingRows: List<ItemKey> = emptyList()
    private var origin = 0
    /** The slot the header would land in, its index among the groups. */
    internal var slot = 0
        private set
    private var grabOffset = 0f
    private var headerHeight = 0f
    private var lastFingerY = 0f
    private var reducedMotion = false
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private val springs = HashMap<ItemKey, SpringSolver>()
    private var liftSpring = SpringSolver(MotionRole.MicroIn, value = 0.0, target = 0.0)
    /** The rows' fade, 0 gone … 1 shown, linear in time. */
    private var rowFade = 1.0
    private var rowFadeTarget = 1.0
    private var shownAtDrop: Map<ItemKey, Float>? = null
    private var commitRequested = false
    private var frameJob: Job? = null
    private var landingFallback: Job? = null
    private var anchor: DrawerGroupDragAnchor? = null

    private var onSlotChanged: (() -> Unit)? = null
    private var onDropReleased: ((String, Int) -> Unit)? = null
    private var onDrop: ((String, Int) -> Unit)? = null

    private val headerKey: ItemKey? get() = draggingID?.let { ItemKey.GroupHeader(it) }

    /**
     * Lifts [groupID]'s header under the finger at [fingerY]. [order] is the groups as the list
     * shows them; [rows] reads the sessions showing under each.
     */
    fun begin(
        groupID: String,
        order: List<String>,
        rows: () -> Map<String, List<String>>,
        fingerY: Float,
        reducedMotion: Boolean,
        onSlotChanged: () -> Unit,
        onDropReleased: (String, Int) -> Unit,
        onDrop: (String, Int) -> Unit,
    ) {
        pinDrag.resetAllCellsAndCleanup()
        val host = pinDrag.host ?: return
        val origin = order.indexOf(groupID)
        val header = host.visibleItems().firstOrNull { it.key == ItemKey.GroupHeader(groupID) }
        if (origin < 0 || header == null) return

        draggingID = groupID
        this.order = order
        rowsOf = rows
        this.origin = origin
        slot = origin
        this.reducedMotion = reducedMotion
        this.onSlotChanged = onSlotChanged
        this.onDropReleased = onDropReleased
        this.onDrop = onDrop
        grabOffset = fingerY - header.minY
        headerHeight = header.height
        lastFingerY = fingerY
        samples.clear()
        commitRequested = false
        shownAtDrop = null
        anchor = null

        foldingRows = rows()[groupID].orEmpty().map { ItemKey.Row(it) }
        rowFade = 1.0
        rowFadeTarget = 0.0
        if (foldingRows.isEmpty()) {
            foldedID = groupID
            phase = Phase.Dragging
        } else {
            phase = Phase.Folding
        }
        liftSpring = if (reducedMotion) {
            SpringSolver(MotionRole.MicroIn, value = 1.0, target = 1.0)
        } else {
            SpringSolver(MotionRole.MicroIn, value = 0.0, target = 1.0, positionEpsilon = 0.002, velocityEpsilon = 0.02)
        }
        liftProgress = liftSpring.value.toFloat()
        springs.clear()
        for (item in host.visibleItems()) springs[item.key] = makeSpring(item.minY, item.minY)
        startFrameLoop()
    }

    /** The finger moved to [fingerY] (dp, list coordinates) at [uptimeMillis]. */
    fun update(fingerY: Float, uptimeMillis: Long? = null) {
        if (phase != Phase.Folding && phase != Phase.Dragging || draggingID == null) return
        lastFingerY = fingerY
        if (uptimeMillis != null) {
            samples.addLast(uptimeMillis to fingerY)
            while (samples.isNotEmpty() && uptimeMillis - samples.first().first > 100) samples.removeFirst()
        }
    }

    /**
     * The finger lifted at [fingerX]/[fingerY]: over the list's body, or its header or footer
     * ([DrawerPinDrag.landsOnCancel]), the group lands in the slot it's over; anywhere else it goes
     * back to where it was.
     */
    fun end(fingerX: Float, fingerY: Float) {
        if (draggingID == null || phase == Phase.Landing || phase == Phase.Settling) return
        val host = pinDrag.host
        val list = Rect(0f, 0f, host?.width?.takeIf { it > 0f } ?: Float.MAX_VALUE, host?.height?.takeIf { it > 0f } ?: Float.MAX_VALUE)
        val headerBottom = host?.headerBottom ?: 0f
        val footerTop = host?.footerTop ?: Float.MAX_VALUE
        val overBody = fingerX in list.left..list.right && fingerY >= headerBottom && fingerY <= footerTop
        val window = host?.window ?: list
        if (overBody || DrawerPinDrag.landsOnCancel(Offset(fingerX, fingerY), list, headerBottom, footerTop, window)) {
            lastFingerY = fingerY
            if (phase == Phase.Dragging) host?.let { refreshSlot(it.visibleItems()) }
        } else {
            slot = origin
        }
        beginLanding()
    }

    /** The finger was taken away: the group goes back to where it was. */
    fun cancel() {
        if (draggingID == null || phase == Phase.Landing || phase == Phase.Settling) return
        slot = origin
        beginLanding()
    }

    /** Ends at once, leaving no offset, fade or fold behind (another drag begins, or the list goes). */
    fun cancelNow() {
        frameJob?.cancel()
        frameJob = null
        landingFallback?.cancel()
        landingFallback = null
        for (key in springs.keys) pinDrag.setTranslation(key, 0f)
        springs.clear()
        alphas.clear()
        samples.clear()
        shownAtDrop = null
        anchor = null
        onSlotChanged = null
        onDropReleased = null
        onDrop = null
        commitRequested = false
        liftProgress = 0f
        if (foldedID != null) foldedID = null
        if (draggingID != null) draggingID = null
    }

    /** The place to keep the landed header at, once, as the list lays out the new order. */
    fun takeAnchor(): DrawerGroupDragAnchor? = anchor.also { anchor = null }

    private fun beginLanding() {
        val id = draggingID ?: return
        phase = Phase.Landing
        rowFadeTarget = 1.0
        onDropReleased?.invoke(id, slot)
        headerKey?.let { key ->
            val header = springs[key]
            if (header != null) {
                springs[key] = SpringSolver(
                    MotionRole.Release,
                    value = header.value,
                    velocity = releaseVelocity(),
                    target = header.target,
                    reduceMotion = reducedMotion,
                    positionEpsilon = 0.3,
                    velocityEpsilon = 12.0,
                )
            }
        }
        liftSpring.retarget(0.0, MotionRole.MicroIn, MotionPhase.Exit, reduceMotion = reducedMotion)
        landingFallback?.cancel()
        landingFallback = pinDrag.host?.scope?.launch {
            delay((DrawerPinDrag.LANDING_FALLBACK_MILLIS / max(0.1, AnimationTimeScale.shared.scale)).toLong())
            landingFallback = null
            commitRequested = true
        }
        startFrameLoop()
    }

    /** The header has landed: the store takes the order, and the rows come back under it. */
    private fun commit(items: List<DrawerPinDragItem>) {
        val id = draggingID ?: return
        landingFallback?.cancel()
        landingFallback = null
        phase = Phase.Settling
        val shown = items.associate { it.key to it.minY + pinDrag.translation(it.key) }
        shownAtDrop = shown
        springs.clear()
        // Only a change the list lays out anew reads the anchor.
        if (slot != origin || foldedID != null) shown[ItemKey.GroupHeader(id)]?.let { anchor = DrawerGroupDragAnchor(id, it) }
        if (foldedID != null) {
            foldedID = null
            rowFade = 0.0
        }
        foldingRows.forEach { alphas[it] = rowAlpha() }
        onDrop?.invoke(id, slot)
    }

    /**
     * The list laid out (the fold, the new order, or the unfold): each item shows where its spring
     * is, measured from its new place, before the frame draws. Items new to the list start where
     * they showed before the drop, or move with the item above them.
     */
    fun relayout() {
        if (draggingID == null) return
        val items = pinDrag.host?.visibleItems() ?: return
        val shown = shownAtDrop
        shownAtDrop = null
        publish(items, shown)
    }

    private fun startFrameLoop() {
        if (frameJob?.isActive == true) return
        val host = pinDrag.host ?: return
        frameJob = host.scope.launch {
            motionFrameLoop(frameDurationSeconds = host.frameDurationSeconds) { tick -> step(tick.dt) }
        }
    }

    // ---- Frame step ----

    internal fun step(dt: Double): MotionFrameAction {
        val host = pinDrag.host
        val id = draggingID
        if (host == null || id == null) {
            frameJob = null
            cancelNow()
            return MotionFrameAction.Stop
        }
        var items = host.visibleItems()
        val following = phase == Phase.Folding || phase == Phase.Dragging
        if (following && autoScroll(host, items, dt)) items = host.visibleItems()

        stepRowFade(dt)
        if (phase == Phase.Dragging) refreshSlot(items)

        val shown = shownAtDrop
        shownAtDrop = null
        publish(items, shown)
        // Once landed, every item settles where the new order lays it out.
        val geometry = if (phase == Phase.Settling) null else geometry(items)
        val header = ItemKey.GroupHeader(id)
        for (item in items) {
            val spring = springs[item.key] ?: continue
            val target = (item.minY + (geometry?.shift(item.key) ?: 0f)).toDouble()
            if (item.key == header && following) {
                spring.target = target
            } else {
                if (spring.target != target) spring.retarget(target)
                spring.step(dt)
            }
        }
        publish(items, null)

        liftSpring.step(dt)
        liftProgress = liftSpring.value.toFloat().coerceAtLeast(0f)

        if (phase == Phase.Landing) {
            val landed = springs[header]?.isSettled ?: true
            if (commitRequested || landed) commit(items)
            return MotionFrameAction.Continue
        }
        if (phase == Phase.Settling && rowFade >= rowFadeTarget && liftSpring.isSettled && springs.values.all { it.isSettled }) {
            frameJob = null
            cancelNow()
            return MotionFrameAction.Stop
        }
        return MotionFrameAction.Continue
    }

    /**
     * Shows each item in view where its spring is; one new to the view starts where it showed before
     * the drop ([shown]) or moves with the item above it, and the unfolding rows start under their
     * header, so they and what follows them slide down together. The lifted header stays under the
     * finger.
     */
    private fun publish(items: List<DrawerPinDragItem>, shown: Map<ItemKey, Float>?) {
        val header = headerKey
        val following = phase == Phase.Folding || phase == Phase.Dragging
        val unfolding = if (shown == null) 0f else items.filter { it.key in foldingRows && it.key !in shown }.sumOf { it.height.toDouble() }.toFloat()
        var carried = 0f
        val visible = HashSet<ItemKey>(items.size)
        for (item in items) {
            visible += item.key
            val spring = springs.getOrPut(item.key) {
                val start = shown?.get(item.key)
                    ?: if (unfolding > 0f && item.key in foldingRows) item.minY - unfolding else item.minY + carried
                makeSpring(start, item.minY)
            }
            if (item.key == header && following) {
                spring.value = (lastFingerY - grabOffset).toDouble()
                spring.velocity = 0.0
            }
            val translation = (spring.value - item.minY).toFloat()
            pinDrag.setTranslation(item.key, translation)
            if (item.key != header || phase == Phase.Settling) carried = translation
        }
        // Items scrolled or folded out of the list keep neither offset nor spring.
        if (springs.keys.any { it !in visible }) {
            springs.keys.filter { it !in visible }.forEach {
                springs.remove(it)
                pinDrag.setTranslation(it, 0f)
            }
        }
    }

    /** The rows fade out on `MotionRole.Swap`'s exit, then fold; they fade back in on its entrance. */
    private fun stepRowFade(dt: Double) {
        if (foldingRows.isEmpty()) rowFade = rowFadeTarget
        if (rowFade == rowFadeTarget) return
        val leaving = rowFadeTarget < rowFade
        val duration = MotionRole.Swap.curve(if (leaving) MotionPhase.Exit else MotionPhase.Enter, reducedMotion).duration
        val delta = if (duration > 0) dt / duration else 1.0
        rowFade = if (leaving) max(rowFade - delta, rowFadeTarget) else minOf(rowFade + delta, rowFadeTarget)
        val alpha = rowAlpha()
        if (alpha >= 1f) foldingRows.forEach { alphas.remove(it) } else foldingRows.forEach { alphas[it] = alpha }
        if (phase == Phase.Folding && rowFade <= 0.0) {
            foldedID = draggingID
            phase = Phase.Dragging
        }
    }

    private fun rowAlpha(): Float = Motion.EaseOut.transform(rowFade.toFloat().coerceIn(0f, 1f))

    // ---- Geometry ----

    /** The group section as laid out now: each group's top and height (header and rows), in dp. */
    private inner class Geometry(
        private val top: Map<String, Float>,
        private val height: Map<String, Float>,
        sectionTop: Float,
        rows: Map<String, List<String>>,
    ) {
        private val dragged = draggingID.orEmpty()
        private val others = order.filter { it != dragged }
        private val draggedHeight = height[dragged] ?: headerHeight

        /** Where the dragged header lands in each slot: slot k follows the first k other groups. */
        val slotTops: List<Float> = others.runningFold(sectionTop) { y, id -> y + (height[id] ?: headerHeight) }

        private val groupOf: Map<ItemKey, String> = buildMap {
            for (id in order) {
                put(ItemKey.GroupHeader(id), id)
                rows[id].orEmpty().forEach { put(ItemKey.Row(it), id) }
            }
            foldingRows.forEach { put(it, dragged) }
        }

        /** How far [key] moves from where it's laid out: the other groups make room for the dragged one in [slot]. */
        fun shift(key: ItemKey): Float {
            val id = groupOf[key] ?: return 0f
            if (id == dragged) return if (key == ItemKey.GroupHeader(dragged)) slotTops[slot] - (top[dragged] ?: 0f) else 0f
            val index = others.indexOf(id)
            val newTop = slotTops[index] + if (index >= slot) draggedHeight else 0f
            return newTop - (top[id] ?: newTop)
        }
    }

    /**
     * Reads the section's layout from the items in view: a group's header, the rows showing under it
     * (the dragged group's only while they're laid out), measured where they show and at their
     * usual height where they don't.
     */
    private fun geometry(items: List<DrawerPinDragItem>): Geometry? {
        val dragged = draggingID ?: return null
        val byKey = items.associateBy { it.key }
        val rows = rowsOf()
        var y = 0f
        var sectionTop: Float? = null
        val tops = HashMap<String, Float>()
        val heights = HashMap<String, Float>()
        for (id in order) {
            val start = y
            val members = if (id == dragged) foldingRows.filter { it in byKey } else rows[id].orEmpty().map { ItemKey.Row(it) }
            for (key in listOf(ItemKey.GroupHeader(id)) + members) {
                val item = byKey[key]
                if (sectionTop == null && item != null) sectionTop = item.minY - y
                y += item?.height ?: if (key is ItemKey.GroupHeader) headerHeight else pinDrag.rowStride
            }
            tops[id] = start
            heights[id] = y - start
        }
        val origin = sectionTop ?: return null
        return Geometry(tops.mapValues { it.value + origin }, heights, origin, rows)
    }

    /** The finger's slot: the one nearest the header's top, held until another is clearly nearer. */
    private fun refreshSlot(items: List<DrawerPinDragItem>) {
        val tops = geometry(items)?.slotTops ?: return
        val y = lastFingerY - grabOffset
        val current = slot.coerceIn(tops.indices)
        val nearest = tops.indices.minBy { abs(y - tops[it]) }
        if (nearest != current && abs(y - tops[nearest]) + SLOT_HYSTERESIS < abs(y - tops[current])) {
            slot = nearest
            onSlotChanged?.invoke()
        }
    }

    // ---- Springs and scrolling ----

    private fun makeSpring(value: Float, target: Float): SpringSolver = SpringSolver(
        MotionRole.Layout,
        value = value.toDouble(),
        target = target.toDouble(),
        reduceMotion = reducedMotion,
        positionEpsilon = 0.08,
        velocityEpsilon = 0.5,
    )

    /** The edge bands scroll the list as for a lifted row; what shows moves with the content. */
    private fun autoScroll(host: DrawerPinDragHost, items: List<DrawerPinDragItem>, dt: Double): Boolean {
        val header = headerKey?.let { key -> items.firstOrNull { it.key == key } } ?: return false
        val delta = host.edgeScrollStep(lastFingerY, header, dt)
        if (abs(delta) < 0.01f) return false
        host.scrollBy(delta)
        val moved = (host.visibleItems().firstOrNull { it.key == header.key }?.minY ?: return false) - header.minY
        if (abs(moved) < 0.01f) return false
        for (spring in springs.values) {
            spring.value += moved
            spring.target += moved
        }
        return true
    }

    private fun releaseVelocity(): Double {
        val first = samples.firstOrNull() ?: return 0.0
        val last = samples.last()
        val dt = (last.first - first.first) / 1000.0
        if (dt <= 0.008) return 0.0
        return ((last.second - first.second) / dt).coerceIn(-DrawerPinDrag.RELEASE_VELOCITY_LIMIT, DrawerPinDrag.RELEASE_VELOCITY_LIMIT)
    }

    private companion object {
        /** How much nearer another slot has to be before the header takes it, dp. */
        const val SLOT_HYSTERESIS = 5f
    }
}
