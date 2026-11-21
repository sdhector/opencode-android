package ai.opencode.feature.drawer.list

import ai.opencode.feature.drawer.list.DrawerPinDrag.HoverTarget
import ai.opencode.feature.drawer.list.DrawerPinDrag.ItemKey
import ai.opencode.feature.drawer.style.DrawerSectionBreak
import ai.opencode.feature.drawer.style.DrawerTunables
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Where the rows and headers are, which slot the finger hovers, how far each item shifts for it,
// and the springs that carry them there. All in dp, in the list's coordinates (see
// [DrawerPinDragHost]).

internal fun DrawerPinDrag.visibleItems(): List<DrawerPinDragItem> = host?.visibleItems().orEmpty()

internal fun DrawerPinDrag.visibleItem(key: ItemKey): DrawerPinDragItem? = visibleItems().firstOrNull { it.key == key }

internal fun DrawerPinDrag.captureInitialGeometry() {
    val keyed = visibleItems()
    val stride = rowStride

    val pinnedHeader = keyed.firstOrNull { it.key == ItemKey.PinnedHeader }
    val recentsHeader = keyed.firstOrNull { it.key == ItemKey.RecentsHeader }
    cachedHeaderHeight = pinnedHeader?.height ?: recentsHeader?.height
        ?: if (DrawerTunables.sectionBreak == DrawerSectionBreak.Header) {
            DrawerTunables.sectionAbove.value + DrawerTunables.sectionHeight.value + DrawerTunables.sectionBelow.value
        } else {
            max(DrawerTunables.sectionGap.value, 1f)
        }

    var visiblePinnedAnchor: Pair<Int, Float>? = null
    var visibleRecentAnchor: Pair<Int, Float>? = null
    for (item in keyed) {
        val key = item.key as? ItemKey.Row ?: continue
        if (visiblePinnedAnchor == null) {
            val p = pinnedIDs.indexOf(key.id)
            if (p >= 0) visiblePinnedAnchor = p to item.minY
        }
        if (visibleRecentAnchor == null) {
            val r = recentIDs.indexOf(key.id)
            if (r >= 0) visibleRecentAnchor = r to item.minY
        }
    }

    // What follows Pinned: the group section when it shows, else Recents.
    val afterPinned = keyed.firstOrNull { it.key == (sectionLead ?: ItemKey.RecentsHeader) }

    if (pinnedIDs.isEmpty()) {
        val hasRecentsHeader = DrawerTunables.sectionBreak == DrawerSectionBreak.Header ||
            showsShortcuts || recentsHeader != null
        val firstRecentMinY = when {
            visibleRecentAnchor != null -> visibleRecentAnchor.second - visibleRecentAnchor.first * stride
            recentsHeader != null -> recentsHeader.maxY
            else -> 0f
        }
        cachedFirstRecentMinY = firstRecentMinY
        cachedTopMinY = when {
            sectionLead != null -> afterPinned?.minY ?: scrolledOutTopMinY()
            recentsHeader != null -> recentsHeader.minY
            hasRecentsHeader -> firstRecentMinY - cachedHeaderHeight
            else -> firstRecentMinY
        }
        cachedFirstPinnedMinY = cachedTopMinY + cachedHeaderHeight
    } else {
        val firstPinnedMinY = when {
            visiblePinnedAnchor != null -> visiblePinnedAnchor.second - visiblePinnedAnchor.first * stride
            pinnedHeader != null -> pinnedHeader.maxY
            afterPinned != null -> afterPinned.minY - pinnedIDs.size * stride
            sectionLead == null && visibleRecentAnchor != null -> {
                val firstRecentMinY = visibleRecentAnchor.second - visibleRecentAnchor.first * stride
                firstRecentMinY - cachedHeaderHeight - pinnedIDs.size * stride
            }
            else -> cachedHeaderHeight
        }
        cachedFirstPinnedMinY = firstPinnedMinY
        cachedTopMinY = pinnedHeader?.minY ?: (firstPinnedMinY - cachedHeaderHeight)
        cachedFirstRecentMinY = when {
            visibleRecentAnchor != null -> visibleRecentAnchor.second - visibleRecentAnchor.first * stride
            recentsHeader != null -> recentsHeader.maxY
            else -> firstPinnedMinY + pinnedIDs.size * stride + cachedHeaderHeight
        }
    }

    cellSprings.clear()
    for (item in keyed) cellSprings[item.key] = makeSpring(item.minY, item.minY)
}

/** How far above the viewport the empty slot's top may sit and still count as in view, dp. */
private const val TOP_VISIBLE_SLACK = 8f

private fun DrawerPinDrag.isTopOfListVisible(): Boolean {
    val visibleTopY = host?.viewportTop ?: 0f
    return cachedTopMinY >= visibleTopY - TOP_VISIBLE_SLACK
}

/** The empty slot's top when the group section that sets it has scrolled out: above the list. */
private fun DrawerPinDrag.scrolledOutTopMinY(): Float =
    (host?.viewportTop ?: 0f) - cachedHeaderHeight - TOP_VISIBLE_SLACK - 1f

/** Scrolling brings the group section's first row into view: the empty slot's top is where it shows. */
internal fun DrawerPinDrag.refreshEmptySlot(keyed: List<DrawerPinDragItem>) {
    if (pinnedIDs.isNotEmpty()) return
    val lead = sectionLead?.let { key -> keyed.firstOrNull { it.key == key } } ?: return
    cachedTopMinY = lead.minY
    cachedFirstPinnedMinY = lead.minY + cachedHeaderHeight
}

private fun DrawerPinDrag.groupOf(key: ItemKey): String? = when (key) {
    is ItemKey.GroupHeader -> key.id
    // The lifted row follows the finger: it is no part of the group it came from.
    is ItemKey.Row -> if (key.id == activeSessionID) null else groupOfSession[key.id]
    else -> null
}

/** Each group's header and sessions as they show now: the layout places plus the drag's shifts. */
private fun DrawerPinDrag.groupSpans(): List<DrawerPinGroupTarget> {
    val tops = LinkedHashMap<String, Float>()
    val bottoms = HashMap<String, Float>()
    for (item in visibleItems()) {
        val id = groupOf(item.key) ?: continue
        val top = item.minY + translation(item.key)
        tops[id] = min(tops[id] ?: top, top)
        bottoms[id] = max(bottoms[id] ?: Float.NEGATIVE_INFINITY, top + item.height)
    }
    return tops.map { (id, top) -> DrawerPinGroupTarget(id, top, bottoms.getValue(id) - top) }
}

private fun DrawerPinDrag.groupTargetAt(y: Float): DrawerPinGroupTarget? {
    val host = host ?: return null
    if (y < host.headerBottom || y >= host.footerTop) return null
    // Dropping a session on the group it is already in changes nothing, so that group is no target.
    val ownGroupID = activeSessionID?.let { groupOfSession[it] }
    return groupSpans().firstOrNull { it.id != ownGroupID && y >= it.top && y < it.top + it.height }
}

/** The group under [y] (list dp): its header, or any session showing under it. */
internal fun DrawerPinDrag.groupAt(y: Float): String? = groupTargetAt(y)?.id

/**
 * The finger is at [fingerY]: over a group it is that group's drop, otherwise it hovers a pin
 * slot. A group and the pin slot are never both targets, so the slot closes under a held finger
 * (a pinned row's own slot stays, so its neighbours don't slide away from it).
 */
internal fun DrawerPinDrag.refreshHover(fingerY: Float) {
    val target = groupTargetAt(fingerY)
    groupTarget = target
    if (target == null) {
        evaluateHover(fingerY)
    } else if (!startedInPinned && hoverTarget != HoverTarget.Recent) {
        hoverTarget = HoverTarget.Recent
        notifyZoneCrossed(false)
    }
}

internal fun DrawerPinDrag.evaluateHover(fingerY: Float) {
    val stride = rowStride
    val sectionHeight = cachedHeaderHeight

    if (pinnedIDs.isEmpty() && !startedInPinned) {
        if (!isTopOfListVisible()) {
            hasRevealedEmptyZone = false
            if (hoverTarget != HoverTarget.Recent) {
                hoverTarget = HoverTarget.Recent
                notifyZoneCrossed(false)
            }
            return
        }
        val topMinY = cachedTopMinY
        val fullZoneHeight = sectionHeight + stride

        if (fingerY < initialFingerY - 6 || fingerY < topMinY + sectionHeight * 0.85f) {
            hasRevealedEmptyZone = true
        } else if (fingerY > initialFingerY + 18 && hoverTarget == HoverTarget.Recent) {
            hasRevealedEmptyZone = false
        }

        val enterY = min(topMinY + fullZoneHeight + 8, initialFingerY - 14)
        val exitY = max(enterY + 18, topMinY + fullZoneHeight + 24)
        when (hoverTarget) {
            HoverTarget.Recent -> if (hasRevealedEmptyZone && fingerY <= enterY) {
                hoverTarget = HoverTarget.Pinned(0)
                notifyZoneCrossed(true)
            }
            is HoverTarget.Pinned -> if (fingerY > exitY) {
                hoverTarget = HoverTarget.Recent
                notifyZoneCrossed(false)
            }
        }
        return
    }

    val firstPinY = cachedFirstPinnedMinY
    val maxK = otherPinnedIDs.size
    val initialPinnedBottomY = firstPinY + pinnedIDs.size * stride
    val visibleTopY = host?.viewportTop ?: 0f
    val isPinnedBandReachable = initialPinnedBottomY + sectionHeight * 0.45f >= visibleTopY

    when (val hover = hoverTarget) {
        HoverTarget.Recent -> {
            val enterY = if (startedInPinned) {
                max(firstPinY + stride * 0.6f, initialPinnedBottomY - stride * 0.15f)
            } else {
                initialPinnedBottomY + sectionHeight * 0.45f
            }
            if (isPinnedBandReachable && fingerY <= enterY) {
                val k = slotIndex(fingerY, firstPinY, stride, maxK, currentK = null)
                hoverTarget = HoverTarget.Pinned(k)
                notifyZoneCrossed(true)
            }
        }
        is HoverTarget.Pinned -> {
            val currentK = hover.index
            val exitY = if (startedInPinned) {
                initialPinnedBottomY + sectionHeight * 0.60f
            } else {
                initialPinnedBottomY + stride + sectionHeight * 0.45f
            }
            if ((!startedInPinned && !isPinnedBandReachable) || fingerY > exitY) {
                hoverTarget = HoverTarget.Recent
                notifyZoneCrossed(false)
            } else {
                val k = slotIndex(fingerY, firstPinY, stride, maxK, currentK)
                if (k != currentK) {
                    hoverTarget = HoverTarget.Pinned(k)
                    notifySlotChanged()
                }
            }
        }
    }
}

private fun slotIndex(fingerY: Float, firstPinY: Float, stride: Float, maxK: Int, currentK: Int?): Int {
    if (maxK <= 0 || stride <= 1) return 0
    val raw = floor((fingerY - firstPinY) / stride).toInt()
    val candidate = raw.coerceIn(0, maxK)
    if (currentK == null || candidate == currentK) return candidate
    val hysteresis = 5f
    if (candidate < currentK) {
        val boundary = firstPinY + currentK * stride
        if (fingerY > boundary - hysteresis) return currentK
    } else {
        val boundary = firstPinY + (currentK + 1) * stride
        if (fingerY < boundary + hysteresis) return currentK
    }
    return candidate
}

// ---- Target shifts & springs ----

private fun DrawerPinDrag.targetEmptyZoneOpenHeight(): Float {
    if (pinnedIDs.isNotEmpty() || startedInPinned || isCancellingEmptyZone || !hasRevealedEmptyZone) return 0f
    if (host != null && !isTopOfListVisible()) return 0f
    return cachedHeaderHeight + rowStride
}

private fun DrawerPinDrag.targetShift(item: DrawerPinDragItem): Float {
    val draggedID = activeSessionID ?: return 0f
    val stride = rowStride
    val sectionHeight = cachedHeaderHeight
    val key = item.key

    // Dragging a row from Recents or a group. It leaves a hole where it was, which what shows
    // below it closes up to fill when it takes a Pinned slot; everything above it, the group
    // section included, makes room.
    if (!startedInPinned) {
        val draggedMinY = visibleItem(ItemKey.Row(draggedID))?.minY ?: return 0f
        val isBelowDragged = item.minY > draggedMinY

        // Scenario B: empty pins.
        if (pinnedIDs.isEmpty()) {
            val zoneTarget = targetEmptyZoneOpenHeight()
            if (key == ItemKey.PinnedHeader) return 0f
            if (key is ItemKey.Row && key.id == draggedID) {
                return if (hoverTarget != HoverTarget.Recent) (cachedTopMinY + sectionHeight) - item.minY else 0f
            }
            return if (hoverTarget == HoverTarget.Recent) {
                zoneTarget
            } else if (isBelowDragged) {
                sectionHeight
            } else {
                sectionHeight + stride
            }
        }

        // Scenario A: existing pins.
        val k = (hoverTarget as? HoverTarget.Pinned)?.index ?: return 0f
        if (key == ItemKey.PinnedHeader) return 0f
        if (key is ItemKey.Row) {
            if (key.id == draggedID) return (cachedFirstPinnedMinY + k * stride) - item.minY
            val pinIdx = otherPinnedIDs.indexOf(key.id)
            if (pinIdx >= 0) return if (pinIdx >= k) stride else 0f
        }
        return if (isBelowDragged) 0f else stride
    }

    // Scenarios C & D: started in Pinned.
    val p = pinnedIDs.indexOf(draggedID).coerceAtLeast(0)
    when (val hover = hoverTarget) {
        is HoverTarget.Pinned -> {
            val k = hover.index
            return when (key) {
                ItemKey.PinnedHeader, ItemKey.RecentsHeader, is ItemKey.GroupHeader, ItemKey.NewGroup -> 0f
                is ItemKey.Row -> {
                    if (key.id == draggedID) return (k - p) * stride
                    val origIdx = pinnedIDs.indexOf(key.id)
                    if (origIdx < 0) return 0f
                    val otherIdx = if (origIdx < p) origIdx else origIdx - 1
                    val newIdx = if (otherIdx < k) otherIdx else otherIdx + 1
                    (newIdx - origIdx) * stride
                }
            }
        }
        HoverTarget.Recent -> {
            if (otherPinnedIDs.isNotEmpty()) {
                // Scenario C: at least one other pin remains.
                return when (key) {
                    ItemKey.PinnedHeader -> 0f
                    ItemKey.RecentsHeader, is ItemKey.GroupHeader, ItemKey.NewGroup -> -stride
                    is ItemKey.Row -> {
                        if (key.id == draggedID) {
                            return recentSlotMinY(recentInsertionIndex, shiftBeforeR = -stride) - item.minY
                        }
                        val origPinIdx = pinnedIDs.indexOf(key.id)
                        if (origPinIdx >= 0) return if (origPinIdx > p) -stride else 0f
                        if (key.id in groupOfSession) return -stride
                        val recIdx = recentIDs.indexOf(key.id)
                        if (recIdx >= 0) return if (recIdx < recentInsertionIndex) -stride else 0f
                        0f
                    }
                }
            }
            // Scenario D: dragging the only pinned row into Recents.
            val shiftBeforeR = -(sectionHeight + stride)
            return when (key) {
                ItemKey.PinnedHeader -> -sectionHeight * 0.25f
                ItemKey.RecentsHeader, is ItemKey.GroupHeader, ItemKey.NewGroup -> shiftBeforeR
                is ItemKey.Row -> {
                    if (key.id == draggedID) {
                        return recentSlotMinY(recentInsertionIndex, shiftBeforeR) - item.minY
                    }
                    if (key.id in groupOfSession) return shiftBeforeR
                    val recIdx = recentIDs.indexOf(key.id)
                    if (recIdx >= 0) return if (recIdx < recentInsertionIndex) shiftBeforeR else -sectionHeight
                    0f
                }
            }
        }
    }
}

internal fun DrawerPinDrag.recentSlotMinY(r: Int, shiftBeforeR: Float): Float {
    val stride = rowStride
    val keyed = visibleItems()
    fun row(id: String) = keyed.firstOrNull { it.key == ItemKey.Row(id) }
    if (r < recentIDs.size) row(recentIDs[r])?.let { return it.minY + shiftBeforeR }
    if (r > 0 && r - 1 < recentIDs.size) row(recentIDs[r - 1])?.let { return it.minY + shiftBeforeR + stride }
    for (item in keyed) {
        val key = item.key as? ItemKey.Row ?: continue
        val j = recentIDs.indexOf(key.id)
        if (j >= 0) return item.minY + shiftBeforeR + (r - j) * stride
    }
    keyed.firstOrNull { it.key == ItemKey.RecentsHeader }?.let { return it.maxY + shiftBeforeR + r * stride }
    return cachedFirstRecentMinY + shiftBeforeR + r * stride
}

internal fun DrawerPinDrag.retargetSprings(keyed: List<DrawerPinDragItem> = visibleItems()) {
    if (!hasCommittedDrop) {
        emptyZoneSpring.retarget(targetEmptyZoneOpenHeight().toDouble())
        hoverSpring.retarget(if (hoverTarget != HoverTarget.Recent) 1.0 else 0.0)
    } else if (isCancellingEmptyZone) {
        emptyZoneSpring.retarget(0.0)
        hoverSpring.retarget(0.0)
    }
    for (item in keyed) {
        val base = item.minY
        val target = if (hasCommittedDrop) base else base + targetShift(item)
        val spring = cellSprings[item.key]
        if (spring != null) {
            if (spring.target != target.toDouble()) spring.retarget(target.toDouble())
        } else {
            // A row the drag scrolled into view starts in its slot.
            cellSprings[item.key] = makeSpring(if (hasAutoScrolled && !hasCommittedDrop) target else base, target)
        }
    }
}
