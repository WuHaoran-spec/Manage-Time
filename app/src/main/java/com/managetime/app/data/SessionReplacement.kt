package com.managetime.app.data

import com.managetime.core.UsageSession

/**
 * Re-importing a sliding interval must not turn one foreground visit into many visits.
 * Only join a previously crossing session to its reconstructed continuation at this boundary.
 * Real gaps or a change of foreground app are preserved.
 */
internal object SessionReplacement {
    fun plan(crossing: List<UsageSession>, reconstructed: List<UsageSession>, boundary: Long): List<UsageSession> {
        val replacement = reconstructed.toMutableList()
        for (previous in crossing) {
            if (previous.start >= boundary || previous.end <= boundary) continue
            val continuation = replacement.indexOfFirst { it.packageName == previous.packageName && it.start == boundary }
            if (continuation >= 0) {
                replacement[continuation] = replacement[continuation].copy(start = previous.start)
            } else {
                replacement.add(previous.copy(end = boundary))
            }
        }
        return replacement.sortedBy { it.start }
    }
}
