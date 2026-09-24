package com.managetime.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Small user-controlled settings. Pause history also prevents later usage imports filling a pause. */
class RecorderPreferences(context: Context) {
    internal val store: SharedPreferences = context.applicationContext.getSharedPreferences("recorder", Context.MODE_PRIVATE)

    var contentEnabled: Boolean
        get() = store.getBoolean("content_enabled", false)
        set(value) { store.edit().putBoolean("content_enabled", value).apply() }

    var allowedPackages: Set<String>
        get() = store.getStringSet("allowed_packages", emptySet())?.toSet() ?: emptySet()
        set(value) { store.edit().putStringSet("allowed_packages", value.toSet()).apply() }

    var paused: Boolean
        get() = store.getBoolean("paused", false)
        set(value) {
            synchronized(PAUSE_LOCK) {
                if (value == paused) return@synchronized
                val now = System.currentTimeMillis()
                val edit = store.edit().putBoolean("paused", value)
                if (value) {
                    edit.putLong("pause_start", now)
                } else {
                    val intervals = readPauseArray()
                    val start = store.getLong("pause_start", now)
                    intervals.put(JSONArray().put(start).put(maxOf(start, now)))
                    edit.putString("pause_intervals", intervals.toString()).remove("pause_start")
                }
                // Commit the exclusion before another worker is able to import events.
                edit.commit()
            }
        }

    var retentionDays: Int
        get() = store.getInt("retention_days", 30).coerceIn(1, 365)
        set(value) { store.edit().putInt("retention_days", value.coerceIn(1, 365)).apply() }

    fun dailyBudgetMinutes(packageName: String): Int = store.getInt("budget:$packageName", 0).coerceIn(0, 1440)
    fun setDailyBudgetMinutes(packageName: String, minutes: Int) {
        store.edit().putInt("budget:$packageName", minutes.coerceIn(0, 1440)).apply()
    }
    fun tag(packageName: String): String = store.getString("tag:$packageName", "") ?: ""
    fun setTag(packageName: String, tag: String) { store.edit().putString("tag:$packageName", tag.trim().take(40)).apply() }

    internal var collectionCutoff: Long
        get() = store.getLong("collection_cutoff", 0)
        set(value) { store.edit().putLong("collection_cutoff", value).commit() }

    internal fun pauseIntervals(now: Long): List<Pair<Long, Long>> = synchronized(PAUSE_LOCK) {
        val data = readPauseArray()
        buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONArray(index) ?: continue
                val start = item.optLong(0)
                val end = item.optLong(1)
                if (end > start) add(start to end)
            }
            if (paused) add(store.getLong("pause_start", now) to now)
        }.sortedBy { it.first }
    }

    internal fun prunePauseHistory(cutoff: Long) = synchronized(PAUSE_LOCK) {
        val data = readPauseArray()
        val retained = JSONArray()
        for (index in 0 until data.length()) {
            val item = data.optJSONArray(index) ?: continue
            if (item.optLong(1) > cutoff) retained.put(item)
        }
        store.edit().putString("pause_intervals", retained.toString()).apply()
    }

    private fun readPauseArray(): JSONArray = runCatching { JSONArray(store.getString("pause_intervals", "[]")) }.getOrDefault(JSONArray())
    private companion object { val PAUSE_LOCK = Any() }
}
