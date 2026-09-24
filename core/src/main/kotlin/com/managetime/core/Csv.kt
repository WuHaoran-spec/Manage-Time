package com.managetime.core

/** RFC 4180 quoting, plus spreadsheet formula protection for user/application supplied text. */
object Csv {
    fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    fun row(vararg values: String): String = values.joinToString(",") { cell(it) } + "\r\n"
}
