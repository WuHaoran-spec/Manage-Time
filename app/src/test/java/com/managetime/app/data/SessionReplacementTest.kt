package com.managetime.app.data

import com.managetime.core.UsageEngine
import com.managetime.core.UsageSession
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionReplacementTest {
    @Test fun `sliding replacement boundaries do not inflate session count`() {
        var stored = listOf(UsageSession("app", 0, 1000))
        for (boundary in listOf(100L, 200L, 500L, 900L)) {
            stored = SessionReplacement.plan(stored, listOf(UsageSession("app", boundary, 1000)), boundary)
        }
        assertEquals(listOf(UsageSession("app", 0, 1000)), stored)
        assertEquals(1, UsageEngine.totals(stored).single().launches)
        assertEquals(1000L, stored.single().durationMs)
    }

    @Test fun `a pause at the replacement boundary is retained`() {
        assertEquals(
            listOf(UsageSession("app", 0, 100), UsageSession("app", 150, 300)),
            SessionReplacement.plan(listOf(UsageSession("app", 0, 300)), listOf(UsageSession("app", 150, 300)), 100)
        )
    }

    @Test fun `switching packages preserves the old prefix separately`() {
        assertEquals(
            listOf(UsageSession("first", 0, 100), UsageSession("second", 100, 300)),
            SessionReplacement.plan(listOf(UsageSession("first", 0, 300)), listOf(UsageSession("second", 100, 300)), 100)
        )
    }

    @Test fun `sessions that do not cross boundary are not duplicated`() {
        assertEquals(
            listOf(UsageSession("app", 100, 300)),
            SessionReplacement.plan(listOf(UsageSession("app", 0, 100)), listOf(UsageSession("app", 100, 300)), 100)
        )
    }
}
