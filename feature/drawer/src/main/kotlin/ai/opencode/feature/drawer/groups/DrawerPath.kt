package ai.opencode.feature.drawer.groups

/**
 * Where the drawer's list is: its root (no groups), or inside the last of [groupIDs]. Each level is
 * one entry, so Back leaves one level and a level inside a group can follow without a new shape.
 * View navigation only: it never changes a group or its order.
 */
data class DrawerPath(val groupIDs: List<String> = emptyList()) {
    val isRoot: Boolean get() = groupIDs.isEmpty()

    /** The group the list shows, or null at the root. */
    val groupID: String? get() = groupIDs.lastOrNull()

    fun opening(groupID: String): DrawerPath = if (this.groupID == groupID) this else DrawerPath(groupIDs + groupID)

    fun back(): DrawerPath = if (isRoot) this else DrawerPath(groupIDs.dropLast(1))

    /** The levels that still exist: a gone group ends the path there. */
    fun resolved(exists: (String) -> Boolean): DrawerPath {
        val kept = groupIDs.takeWhile(exists)
        return if (kept.size == groupIDs.size) this else DrawerPath(kept)
    }

    fun renamingGroup(from: String, to: String): DrawerPath =
        if (from !in groupIDs) this else DrawerPath(groupIDs.map { if (it == from) to else it })
}
