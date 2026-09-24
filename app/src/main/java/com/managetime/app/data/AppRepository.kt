package com.managetime.app.data

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Process
import com.managetime.core.Csv
import com.managetime.core.EventKind
import com.managetime.core.UsageEngine
import com.managetime.core.UsageEvent
import com.managetime.core.UsageSession
import java.time.Instant
import java.time.ZoneId

data class AppEntry(val packageName: String, val label: String)
data class ContentObservation(val id: Long, val timestamp: Long, val packageName: String, val title: String, val kind: String, val source: String)

/** No network dependencies; all imported usage and observed titles remain in private app storage. */
class AppRepository(context: Context) {
    private val context = context.applicationContext
    private val preferences = RecorderPreferences(this.context)
    private val helper = database(this.context)
    private val db get() = helper.writableDatabase

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Rebuilds a bounded interval atomically; old sessions outside it are preserved. */
    fun syncUsage(now: Long = System.currentTimeMillis()) = synchronized(DATA_LOCK) {
        if (!hasUsageAccess()) return@synchronized
        val cutoff = maxOf(preferences.collectionCutoff, now - preferences.retentionDays * DAY)
        val previousSync = preferences.store.getLong("last_usage_sync", now - 2 * DAY)
        val replaceFrom = maxOf(cutoff, minOf(now - 2 * DAY, previousSync))
        if (replaceFrom >= now) return@synchronized
        // Read an extra day to reconstruct a session already open at the replacement boundary.
        val queryFrom = maxOf(0, replaceFrom - DAY)
        val service = context.getSystemService(UsageStatsManager::class.java)
        val stream = service.queryEvents(queryFrom, now) ?: return@synchronized
        val raw = UsageEvents.Event()
        val events = ArrayList<UsageEvent>()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(raw)
            val kind = when (raw.eventType) {
                1 -> EventKind.RESUME // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                2 -> EventKind.PAUSE  // MOVE_TO_BACKGROUND / ACTIVITY_PAUSED
                16, 17 -> EventKind.SCREEN_OFF // Screen off or keyguard shown.
                26 -> EventKind.SHUTDOWN
                else -> null
            } ?: continue
            events.add(UsageEvent(raw.timeStamp, raw.packageName ?: "", kind))
        }
        if (!hasUsageAccess()) return@synchronized
        // Locked devices, OEM restrictions, or expired system history can return no usable data.
        // In that case preserve the last known local records instead of treating silence as deletion.
        if (events.isEmpty()) { prune(now); return@synchronized }
        // Recheck after querying: clearing data or changing pause can happen from another thread.
        // Never erase locally retained history older than the first event Android still exposes.
        val from = maxOf(replaceFrom, preferences.collectionCutoff, events.minOf { it.timestamp })
        val intervals = preferences.pauseIntervals(now)
        val sessions = UsageEngine.sessions(events, from, now)
            .flatMap { removeIntervals(it, intervals) }
            .filter { it.packageName != context.packageName && it.end > it.start }
        val database = db
        val crossing = database.query("sessions", arrayOf("package", "start", "end"), "start < ? AND end > ?", arrayOf(from.toString(), from.toString()), null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(UsageSession(cursor.getString(0), cursor.getLong(1), cursor.getLong(2))) }
        }
        val replacement = SessionReplacement.plan(crossing, sessions, from)
        database.beginTransaction()
        try {
            database.delete("sessions", "end > ?", arrayOf(from.toString()))
            replacement.forEach { session ->
                val values = ContentValues().apply {
                    put("package", session.packageName); put("start", session.start); put("end", session.end)
                }
                database.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        preferences.store.edit().putLong("last_usage_sync", now).apply()
        prune(now)
        remindBudgets(now)
    }

    fun sessions(start: Long, end: Long): List<UsageSession> {
        if (end <= start) return emptyList()
        val pauses = preferences.pauseIntervals(System.currentTimeMillis())
        return db.query("sessions", arrayOf("package", "start", "end"), "end > ? AND start < ?", arrayOf(start.toString(), end.toString()), null, null, "start ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val session = UsageSession(cursor.getString(0), maxOf(start, cursor.getLong(1)), minOf(end, cursor.getLong(2)))
                    addAll(removeIntervals(session, pauses))
                }
            }
        }
    }

    fun observations(start: Long, end: Long): List<ContentObservation> = db.query(
        "observations", arrayOf("id", "timestamp", "package", "title", "kind", "source"),
        "timestamp >= ? AND timestamp < ?", arrayOf(start.toString(), end.toString()), null, null, "timestamp ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(ContentObservation(cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5)))
        }
    }

    fun addObservation(packageName: String, title: String, kind: String, source: String, timestamp: Long = System.currentTimeMillis()) = synchronized(DATA_LOCK) {
        if (!preferences.contentEnabled || preferences.paused || packageName !in preferences.allowedPackages || timestamp < preferences.collectionCutoff) return@synchronized
        val cleaned = title.trim().take(240)
        if (cleaned.isEmpty()) return@synchronized
        val values = ContentValues().apply {
            put("timestamp", timestamp); put("minute", timestamp / 60_000L); put("package", packageName)
            put("title", cleaned); put("kind", kind); put("source", source)
        }
        db.insertWithOnConflict("observations", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun appLabel(packageName: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    fun installedApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0).map { info ->
            AppEntry(info.activityInfo.packageName, info.loadLabel(context.packageManager).toString())
        }.distinctBy { it.packageName }.filter { it.packageName != context.packageName }.sortedBy { it.label.lowercase() }
    }

    fun prune() = prune(System.currentTimeMillis())
    private fun prune(now: Long) = synchronized(DATA_LOCK) {
        val cutoff = now - preferences.retentionDays * DAY
        db.delete("sessions", "end <= ?", arrayOf(cutoff.toString()))
        db.execSQL("UPDATE sessions SET start = ? WHERE start < ?", arrayOf(cutoff, cutoff))
        db.delete("observations", "timestamp < ?", arrayOf(cutoff.toString()))
        preferences.prunePauseHistory(cutoff)
    }

    fun clearAll() = synchronized(DATA_LOCK) {
        preferences.collectionCutoff = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete("sessions", null, null); db.delete("observations", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // secure_delete is enabled, and no WAL is used, to avoid retaining deleted titles in WAL pages.
        db.execSQL("VACUUM")
    }

    /** RFC 4180 quoting plus spreadsheet formula-injection protection for user/app supplied text. */
    fun exportCsv(start: Long, end: Long): String = buildString {
        append("record_type,start,end,package,app,title,kind,source,tag\r\n")
        for (session in sessions(start, end)) {
            append(listOf("usage", Instant.ofEpochMilli(session.start).toString(), Instant.ofEpochMilli(session.end).toString(), session.packageName, appLabel(session.packageName), "", "foreground_session", "android_usage_events", preferences.tag(session.packageName)).joinToString(",", transform = Csv::cell)); append("\r\n")
        }
        for (entry in observations(start, end)) {
            append(listOf("observation", Instant.ofEpochMilli(entry.timestamp).toString(), "", entry.packageName, appLabel(entry.packageName), entry.title, entry.kind, entry.source, preferences.tag(entry.packageName)).joinToString(",", transform = Csv::cell)); append("\r\n")
        }
    }

    private fun remindBudgets(now: Long) {
        if (preferences.paused || (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel("budgets", "每日使用预算", NotificationManager.IMPORTANCE_DEFAULT))
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        for (usage in UsageEngine.totals(sessions(start, now))) {
            val limit = preferences.dailyBudgetMinutes(usage.packageName)
            if (limit <= 0 || usage.durationMs < limit * 60_000L) continue
            val key = "budget_notified:${usage.packageName}"
            if (preferences.store.getString(key, "") == day.toString()) continue
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: continue
            val content = PendingIntent.getActivity(context, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(context, "budgets")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("${appLabel(usage.packageName)} 已到每日预算")
                .setContentText("今天已使用 ${usage.durationMs / 60_000} 分钟，预算 $limit 分钟")
                .setContentIntent(content).setAutoCancel(true).build()
            runCatching { manager.notify(usage.packageName.hashCode(), notification) }.onSuccess {
                preferences.store.edit().putString(key, day.toString()).apply()
            }
        }
    }

    private fun removeIntervals(session: UsageSession, excluded: List<Pair<Long, Long>>): List<UsageSession> {
        var pieces = listOf(session)
        excluded.forEach { (start, end) ->
            pieces = pieces.flatMap { part ->
                if (end <= part.start || start >= part.end) listOf(part) else buildList {
                    if (part.start < start) add(UsageSession(part.packageName, part.start, start))
                    if (part.end > end) add(UsageSession(part.packageName, end, part.end))
                }
            }
        }
        return pieces
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, "manage_time.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() } }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions (package TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL CHECK(end > start), PRIMARY KEY(package, start))")
            db.execSQL("CREATE INDEX sessions_time ON sessions(start, end)")
            db.execSQL("CREATE TABLE observations (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, minute INTEGER NOT NULL, package TEXT NOT NULL, title TEXT NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL, UNIQUE(minute, package, title))")
            db.execSQL("CREATE INDEX observations_time ON observations(timestamp)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private const val DAY = 86_400_000L
        private val DATA_LOCK = Any()
        @Volatile private var instance: Database? = null
        private fun database(context: Context): Database = instance ?: synchronized(DATA_LOCK) {
            instance ?: Database(context).also { instance = it }
        }
    }
}
