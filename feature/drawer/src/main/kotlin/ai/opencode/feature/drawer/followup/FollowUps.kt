package ai.opencode.feature.drawer.followup

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

/*
 * Follow Up: a finished agent reply the reader has seen on this phone stays to be followed up until
 * they reply, mark it Done, archive or delete the session. Phone-local and server-scoped
 * (`serverID|sessionID`) like pins. Each record names the reply by its last assistant row and the
 * server's idle watermark for the run that ended with it, never its text, and keeps how it was
 * handled so a later read of the same reply can't bring it back.
 */

@Serializable
data class FollowUp(
    /** The reply's last assistant row (`OpenCodeTranscriptItem.id`). */
    val replyID: String,
    /** The session's idle watermark (`time.idle`, epoch ms) for the run that ended with the reply. */
    val idle: Long,
    /** When this phone showed the reply (epoch ms). */
    val seenAt: Long,
    val status: Status = Status.pending,
    /** While [Status.snoozed]: when it comes back (epoch ms). */
    val snoozedUntil: Long? = null,
) {
    @Serializable
    enum class Status { pending, snoozed, done, replied, archived }

    /** Pending or snoozed: not handled yet. */
    val isOpen: Boolean get() = status == Status.pending || status == Status.snoozed
}

@Serializable
data class FollowUps(val entries: Map<String, FollowUp> = emptyMap()) {
    operator fun get(key: String): FollowUp? = entries[key]

    /**
     * The reader was shown [replyID], ending the run at [idle]. A new reply is pending; the same reply,
     * or an older run's, changes nothing, whatever was done with it.
     */
    fun noting(key: String, replyID: String, idle: Long, now: Instant): FollowUps {
        val existing = entries[key]
        if (existing != null && (existing.replyID == replyID || idle <= existing.idle)) return this
        return setting(key, FollowUp(replyID = replyID, idle = idle, seenAt = now.toEpochMilli())).pruned()
    }

    /** Done, a reply, or an archive: an open follow-up is handled. */
    fun handling(key: String, status: FollowUp.Status): FollowUps {
        val existing = entries[key] ?: return this
        if (!existing.isOpen) return this
        return setting(key, existing.copy(status = status, snoozedUntil = null)).pruned()
    }

    fun snoozing(key: String, until: Instant): FollowUps {
        val existing = entries[key] ?: return this
        if (!existing.isOpen) return this
        return setting(key, existing.copy(status = FollowUp.Status.snoozed, snoozedUntil = until.toEpochMilli()))
    }

    fun bringingBack(key: String): FollowUps {
        val existing = entries[key] ?: return this
        if (existing.status != FollowUp.Status.snoozed) return this
        return setting(key, existing.copy(status = FollowUp.Status.pending, snoozedUntil = null))
    }

    /** Snoozes whose time has come are pending again. */
    fun wakingDue(now: Instant): FollowUps {
        val at = now.toEpochMilli()
        var next: MutableMap<String, FollowUp>? = null
        for ((key, entry) in entries) {
            val until = entry.snoozedUntil ?: continue
            if (entry.status != FollowUp.Status.snoozed || until > at) continue
            if (next == null) next = entries.toMutableMap()
            next[key] = entry.copy(status = FollowUp.Status.pending, snoozedUntil = null)
        }
        return next?.let { FollowUps(it) } ?: this
    }

    /** Undo: puts [restored] back only while the record is still the one the action left. */
    fun restoring(key: String, expected: FollowUp, restored: FollowUp): FollowUps =
        if (entries[key] != expected) this else setting(key, restored)

    fun removing(key: String): FollowUps = if (key !in entries) this else FollowUps(entries - key)

    /** [serverID]'s open follow-ups, by session ID. */
    fun open(serverID: String): Map<String, FollowUp> {
        val prefix = "$serverID|"
        val result = LinkedHashMap<String, FollowUp>()
        for ((key, entry) in entries) {
            if (entry.isOpen && key.startsWith(prefix)) result[key.removePrefix(prefix)] = entry
        }
        return result
    }

    private fun setting(key: String, entry: FollowUp): FollowUps =
        if (entries[key] == entry) this else FollowUps(entries + (key to entry))

    /** Handled records only matter while a reply could be read again: the oldest go past [handledLimit]. Open ones never do. */
    private fun pruned(): FollowUps {
        val handled = entries.filterValues { !it.isOpen }
        if (handled.size <= handledLimit) return this
        val dropped = handled.entries.sortedBy { it.value.seenAt }.take(handled.size - handledLimit).map { it.key }.toSet()
        return FollowUps(entries - dropped)
    }

    companion object {
        const val handledLimit = 500
    }
}

/**
 * The finished reply a transcript ends with: its last assistant text when nothing of the reader's,
 * no tool call and no error follows it, and it isn't still streaming. Null when the run ended any
 * other way.
 */
fun List<OpenCodeTranscriptItem>.completedReplyID(): String? {
    for (index in indices.reversed()) {
        when (val item = this[index]) {
            is OpenCodeTranscriptItem.AssistantMarkdown -> return if (item.inProgress) null else item.id
            is OpenCodeTranscriptItem.Info, is OpenCodeTranscriptItem.ModelSwitch, is OpenCodeTranscriptItem.Compaction,
            is OpenCodeTranscriptItem.Notice, is OpenCodeTranscriptItem.SendFailure,
            -> continue
            else -> return null
        }
    }
    return null
}

/** Snooze's choices: each comes back at [FollowUpSnooze.hour] local time. */
sealed interface FollowUpSnooze {
    data object Tomorrow : FollowUpSnooze
    data object NextWeek : FollowUpSnooze
    data class On(val date: LocalDate) : FollowUpSnooze

    /** When it comes back; null for a time already past. */
    fun until(now: Instant, zone: ZoneId): Instant? {
        val today = now.atZone(zone).toLocalDate()
        val date = when (this) {
            Tomorrow -> today.plusDays(1)
            NextWeek -> today.plusWeeks(1)
            is On -> date
        }
        val until = date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant()
        return until.takeIf { it > now }
    }

    companion object {
        const val hour = 9

        /** The first day a picked date can be: today while its morning hasn't come, else tomorrow. */
        fun firstDate(now: Instant, zone: ZoneId): LocalDate {
            val today = now.atZone(zone).toLocalDate()
            return if (On(today).until(now, zone) != null) today else today.plusDays(1)
        }
    }
}

/** The time zone Snooze's local times are in. */
fun interface FollowUpTimeZone {
    fun zone(): ZoneId

    companion object Key : DependencyKey<FollowUpTimeZone> {
        override val liveValue: FollowUpTimeZone = FollowUpTimeZone { ZoneId.systemDefault() }
        override val testValue: FollowUpTimeZone = FollowUpTimeZone { ZoneId.of("UTC") }
    }
}
