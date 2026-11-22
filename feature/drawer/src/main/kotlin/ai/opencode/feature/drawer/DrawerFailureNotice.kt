package ai.opencode.feature.drawer

import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural

/** An archive, unarchive, delete or shared groups change that failed, shown above New Session for a few seconds. */
data class DrawerFailure(
    val action: Action,
    /** The session that failed last; empty for [Action.groups]. */
    val sessionTitle: String,
    /** Why it failed. */
    val detail: String,
    /** How many sessions failed this way while the notice showed. */
    val count: Int = 1,
) {
    enum class Action { archive, unarchive, delete, groups }

    val title: String
        get() = when (action) {
            Action.archive ->
                if (count == 1) localized(R.string.drawer_archive_error, sessionTitle)
                else localizedPlural(R.plurals.drawer_archive_sessions_error, count, count)
            Action.unarchive ->
                if (count == 1) localized(R.string.drawer_unarchive_error, sessionTitle)
                else localizedPlural(R.plurals.drawer_unarchive_sessions_error, count, count)
            Action.delete ->
                if (count == 1) localized(R.string.drawer_delete_session_error, sessionTitle)
                else localizedPlural(R.plurals.drawer_delete_sessions_error, count, count)
            Action.groups -> localized(R.string.drawer_groups_error)
        }

    /** Counts this failure with [shown] when it failed the same way, so a selection gets one notice. */
    fun joining(shown: DrawerFailure?): DrawerFailure {
        if (shown == null || shown.action != action || shown.detail != detail) return this
        return copy(count = shown.count + 1)
    }
}
