package com.managetime.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun `quotes commas newlines and literal quotes without losing content`() {
        assertEquals("\"标题,\"\"你好\"\"\n第二行\"", Csv.cell("标题,\"你好\"\n第二行"))
    }

    @Test fun `uses carriage return line feed for rows`() {
        assertEquals("\"a\",\"b\"\r\n", Csv.row("a", "b"))
    }

    @Test fun `untrusted text cannot become a spreadsheet formula`() {
        assertEquals("\"'=HYPERLINK(\"\"url\"\")\"", Csv.cell("=HYPERLINK(\"url\")"))
        assertEquals("\"'  @SUM(1)\"", Csv.cell("  @SUM(1)"))
        assertEquals("\"ordinary title\"", Csv.cell("ordinary title"))
    }
}
