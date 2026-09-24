package com.managetime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UsageEngineTest {
    private fun resume(time: Long, app: String = "video") = UsageEvent(time, app, EventKind.RESUME)
    private fun pause(time: Long, app: String = "video") = UsageEvent(time, app, EventKind.PAUSE)

    @Test fun `carry-in is clipped and an open session stops at query end`() {
        assertEquals(
            listOf(UsageSession("video", 100, 200)),
            UsageEngine.sessions(listOf(resume(50)), 100, 200)
        )
    }

    @Test fun `a pause before query start leaves no carry-in`() {
        assertTrue(UsageEngine.sessions(listOf(resume(10), pause(50)), 100, 200).isEmpty())
    }

    @Test fun `late pause from previous app does not close the new foreground app`() {
        val events = listOf(resume(10, "a"), resume(40, "b"), pause(50, "a"), pause(90, "b"))
        assertEquals(
            listOf(UsageSession("a", 10, 40), UsageSession("b", 40, 90)),
            UsageEngine.sessions(events, 0, 100)
        )
    }

    @Test fun `duplicate resumes neither split nor double-count a session`() {
        val events = listOf(resume(20), resume(20), resume(30), pause(80), pause(80))
        val sessions = UsageEngine.sessions(events, 0, 100)
        assertEquals(listOf(UsageSession("video", 20, 80)), sessions)
        assertEquals(listOf(AppUsage("video", 60, 1)), UsageEngine.totals(sessions))
    }

    @Test fun `screen off closes foreground even without a matching pause`() {
        val events = listOf(resume(10), UsageEvent(40, "android", EventKind.SCREEN_OFF), resume(80))
        assertEquals(
            listOf(UsageSession("video", 10, 40), UsageSession("video", 80, 100)),
            UsageEngine.sessions(events, 0, 100)
        )
    }

    @Test fun `shutdown prevents a session from spanning powered-off time`() {
        val events = listOf(resume(10), UsageEvent(40, "", EventKind.SHUTDOWN), resume(90, "browser"))
        assertEquals(
            listOf(UsageSession("video", 10, 40), UsageSession("browser", 90, 100)),
            UsageEngine.sessions(events, 0, 100)
        )
    }

    @Test fun `events may arrive unsorted`() {
        assertEquals(
            listOf(UsageSession("video", 20, 80)),
            UsageEngine.sessions(listOf(pause(80), resume(20)), 0, 100)
        )
    }

    @Test fun `equal timestamp events preserve source order and omit zero duration`() {
        val events = listOf(resume(10, "a"), pause(10, "a"), resume(10, "b"), resume(40, "a"))
        assertEquals(
            listOf(UsageSession("b", 10, 40), UsageSession("a", 40, 100)),
            UsageEngine.sessions(events, 0, 100)
        )
    }

    @Test fun `end boundary is exclusive and an exact start pause records no usage`() {
        val events = listOf(resume(0), pause(100), resume(200, "browser"))
        assertTrue(UsageEngine.sessions(events, 100, 200).isEmpty())
    }

    @Test fun `blank packages and unmatched pauses create no phantom activity`() {
        val events = listOf(pause(10), resume(20, " "), resume(30), pause(40, "browser"))
        assertEquals(listOf(UsageSession("video", 30, 100)), UsageEngine.sessions(events, 0, 100))
    }

    @Test fun `invalid query bounds produce no sessions or minute rows`() {
        for ((start, end) in listOf(100L to 100L, 200L to 100L)) {
            assertTrue(UsageEngine.sessions(listOf(resume(0)), start, end).isEmpty())
            assertTrue(UsageEngine.minutes(listOf(UsageSession("a", 0, 300)), start, end).isEmpty())
        }
    }

    @Test fun `totals aggregate separate visits and sort by duration then package`() {
        val sessions = listOf(
            UsageSession("b", 0, 20), UsageSession("a", 20, 30),
            UsageSession("a", 50, 60), UsageSession("c", 60, 70),
            UsageSession("c", 90, 80), UsageSession("", 0, 100)
        )
        assertEquals(
            listOf(AppUsage("a", 20, 2), AppUsage("b", 20, 1), AppUsage("c", 10, 1)),
            UsageEngine.totals(sessions)
        )
    }

    @Test fun `minute splitting keeps partial seconds and does not add an empty end minute`() {
        val sessions = listOf(UsageSession("video", 10_000, 180_000))
        assertEquals(
            listOf(MinuteUsage(0, "video", 50_000), MinuteUsage(60_000, "video", 60_000),
                MinuteUsage(120_000, "video", 60_000)),
            UsageEngine.minutes(sessions, 0, 180_000)
        )
    }

    @Test fun `a minute can contain several apps and repeated visits are combined`() {
        val sessions = listOf(
            UsageSession("a", 0, 10_000), UsageSession("b", 10_000, 50_000),
            UsageSession("a", 50_000, 60_000)
        )
        assertEquals(
            listOf(MinuteUsage(0, "a", 20_000), MinuteUsage(0, "b", 40_000)),
            UsageEngine.minutes(sessions, 0, 60_000)
        )
    }

    @Test fun `minute query clips both range edges`() {
        assertEquals(
            listOf(MinuteUsage(0, "video", 45_000), MinuteUsage(60_000, "video", 20_000)),
            UsageEngine.minutes(listOf(UsageSession("video", 0, 120_000)), 15_000, 80_000)
        )
    }

    @Test fun `negative epoch timestamps use floor division`() {
        assertEquals(
            listOf(MinuteUsage(-60_000, "video", 10_000), MinuteUsage(0, "video", 10_000)),
            UsageEngine.minutes(listOf(UsageSession("video", -10_000, 10_000)), -10_000, 10_000)
        )
    }

    @Test fun `midnight sessions are clipped independently for each local day`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val midnight = LocalDate.of(2026, 9, 25).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = listOf(resume(midnight - 30_000), pause(midnight + 90_000))
        val before = UsageEngine.sessions(events, midnight - 86_400_000, midnight)
        val after = UsageEngine.sessions(events, midnight, midnight + 86_400_000)
        assertEquals(30_000, before.sumOf { it.durationMs })
        assertEquals(90_000, after.sumOf { it.durationMs })
        assertEquals(listOf(MinuteUsage(midnight, "video", 60_000),
            MinuteUsage(midnight + 60_000, "video", 30_000)),
            UsageEngine.minutes(after, midnight, midnight + 86_400_000))
    }

    @Test fun `spring DST clock jump never creates phantom hour`() {
        val zone = ZoneId.of("America/New_York")
        val start = Instant.parse("2026-03-08T06:59:30Z").toEpochMilli()
        val end = Instant.parse("2026-03-08T07:01:30Z").toEpochMilli()
        val minutes = UsageEngine.minutes(listOf(UsageSession("video", start, end)), start, end)
        val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        assertEquals(listOf("01:59", "03:00", "03:01"), minutes.map { clock.format(Instant.ofEpochMilli(it.start)) })
        assertEquals(120_000, minutes.sumOf { it.durationMs })
    }

    @Test fun `fall DST repeated clock minute is kept as two distinct epoch minutes`() {
        val zone = ZoneId.of("America/New_York")
        val start = Instant.parse("2026-11-01T05:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-01T06:01:00Z").toEpochMilli()
        val minutes = UsageEngine.minutes(listOf(UsageSession("video", start, end)), start, end)
        val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val repeated = minutes.filter { clock.format(Instant.ofEpochMilli(it.start)) == "01:00" }
        assertEquals(61, minutes.size)
        assertEquals(2, repeated.size)
        assertEquals(3_600_000, repeated[1].start - repeated[0].start)
        assertEquals(end - start, minutes.sumOf { it.durationMs })
    }

    @Test fun `rapid app switching never exceeds wall-clock duration`() {
        val events = (0..999).flatMap { index ->
            val time = index * 100L
            listOf(resume(time, "app${index % 5}"), pause(time + 10, "app${(index + 4) % 5}"))
        }
        val sessions = UsageEngine.sessions(events, 0, 100_000)
        assertEquals(100_000, sessions.sumOf { it.durationMs })
        assertTrue(sessions.zipWithNext().all { (a, b) -> a.end <= b.start })
        val minutes = UsageEngine.minutes(sessions, 0, 100_000)
        assertEquals(100_000, minutes.sumOf { it.durationMs })
        assertTrue(minutes.groupBy { it.start }.values.all { rows -> rows.sumOf { it.durationMs } <= 60_000 })
    }
}
