package com.managetime.core

/** Events used to reconstruct one foreground application at a time. All times are epoch milliseconds. */
enum class EventKind { RESUME, PAUSE, SCREEN_OFF, SHUTDOWN }

data class UsageEvent(val timestamp: Long, val packageName: String, val kind: EventKind)

/** A half-open interval [start, end). Zero-length/invalid intervals are ignored by the engine. */
data class UsageSession(val packageName: String, val start: Long, val end: Long) {
    val durationMs: Long get() = (end - start).coerceAtLeast(0)
}

/** launches counts observed foreground sessions, including a session carried in from before the range. */
data class AppUsage(val packageName: String, val durationMs: Long, val launches: Int)

/** start is the epoch-aligned start of a minute, not a local-clock label. */
data class MinuteUsage(val start: Long, val packageName: String, val durationMs: Long)

/**
 * Pure screen-use reconstruction. The latest resumed app owns foreground time until a matching
 * pause, another app's resume, screen off, shutdown, or the query end. A late pause from another
 * package cannot close the current app. Repeated resumes do not create additional sessions.
 *
 * Supply events preceding [start] when available: this allows a session already running at the
 * beginning of the query to be carried in. Missing system history is not guessed or backfilled.
 * This deliberately measures one foreground app; simultaneous split-screen/PiP playback is not
 * summed into multiple hours for the same hour of wall-clock time.
 */
object UsageEngine {
    fun sessions(events: List<UsageEvent>, start: Long, end: Long): List<UsageSession> {
        if (end <= start) return emptyList()
        val result = mutableListOf<UsageSession>()
        var activePackage: String? = null
        var activeStart = 0L

        fun close(at: Long) {
            val packageName = activePackage ?: return
            val clippedStart = maxOf(start, activeStart)
            val clippedEnd = minOf(end, at)
            if (clippedEnd > clippedStart) {
                result += UsageSession(packageName, clippedStart, clippedEnd)
            }
            activePackage = null
        }

        // Kotlin's stable sort preserves source order when Android reports equal timestamps.
        for (event in events.sortedBy { it.timestamp }) {
            if (event.timestamp >= end) break
            when (event.kind) {
                EventKind.RESUME -> {
                    if (event.packageName.isBlank() || event.packageName == activePackage) continue
                    close(event.timestamp)
                    activePackage = event.packageName
                    activeStart = event.timestamp
                }
                EventKind.PAUSE -> if (event.packageName == activePackage) close(event.timestamp)
                EventKind.SCREEN_OFF, EventKind.SHUTDOWN -> close(event.timestamp)
            }
        }
        close(end)
        return result
    }

    /** Aggregate reconstructed sessions. Session count is not the same as an app process launch. */
    fun totals(sessions: List<UsageSession>): List<AppUsage> = sessions
        .filter { it.packageName.isNotBlank() && it.end > it.start }
        .groupBy { it.packageName }
        .map { (packageName, values) ->
            AppUsage(packageName, values.sumOf { it.durationMs }, values.size)
        }
        .sortedWith(compareByDescending<AppUsage> { it.durationMs }.thenBy { it.packageName })

    /**
     * Split reconstructed sessions into actual elapsed minutes. A local DST jump does not create
     * phantom time or merge two different minutes that happen to share the same local-clock label.
     * Multiple apps used within the same minute get separate rows, preserving sub-minute usage.
     */
    fun minutes(sessions: List<UsageSession>, start: Long, end: Long): List<MinuteUsage> {
        if (end <= start) return emptyList()
        val buckets = mutableMapOf<Pair<Long, String>, Long>()
        for (session in sessions) {
            if (session.packageName.isBlank()) continue
            var cursor = maxOf(session.start, start)
            val clippedEnd = minOf(session.end, end)
            while (cursor < clippedEnd) {
                val minuteStart = Math.floorDiv(cursor, 60_000L) * 60_000L
                val boundary = if (minuteStart > Long.MAX_VALUE - 60_000L) Long.MAX_VALUE
                    else minuteStart + 60_000L
                val next = minOf(boundary, clippedEnd)
                val key = minuteStart to session.packageName
                buckets[key] = (buckets[key] ?: 0L) + next - cursor
                cursor = next
            }
        }
        return buckets.map { (key, duration) -> MinuteUsage(key.first, key.second, duration) }
            .sortedWith(compareBy<MinuteUsage> { it.start }.thenBy { it.packageName })
    }
}
