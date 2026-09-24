package com.managetime.app.capture

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.managetime.app.data.AppRepository
import com.managetime.app.data.RecorderPreferences
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional, visible, on-device title observations. A sampled title is NOT proof of watching/reading.
 * Never reads AccessibilityEvent.text and never subscribes to edit, key, or notification events.
 */
class ActivityCaptureService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val sampling = AtomicBoolean(false)
    private lateinit var preferences: RecorderPreferences
    private lateinit var repository: AppRepository
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var connected = false
    private var receiverRegistered = false

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { if (connected) updateNotification() }
    }
    private val pauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PAUSE) preferences.paused = true
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!connected) return
            // Permission/channel settings can change without a preference callback; Android 14+
            // can also let the user dismiss an ongoing notification. Restore visibility first.
            if (preferences.contentEnabled && !statusNotificationPresent()) updateNotification()
            sampleIfAllowed()
            handler.postDelayed(this, SAMPLE_INTERVAL)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferences = RecorderPreferences(this)
        repository = AppRepository(this)
        connected = true
        foregroundPackage = null
        preferences.store.registerOnSharedPreferenceChangeListener(settingsListener)
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(pauseReceiver, IntentFilter(ACTION_PAUSE), RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(pauseReceiver, IntentFilter(ACTION_PAUSE))
            }
            receiverRegistered = true
        }
        createNotificationChannel()
        updateNotification()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, SAMPLE_INTERVAL)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null) return
        // Package metadata is needed to stop sampling immediately when the user leaves an allowed app.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundPackage = event.packageName?.toString()
        }
        // Do not access event.text, event.contentDescription, or event.source here.
    }

    private fun sampleIfAllowed() {
        val expectedPackage = foregroundPackage ?: return
        if (!eligible(expectedPackage) || !sampling.compareAndSet(false, true)) return
        executor.execute {
            try {
                if (!eligible(expectedPackage)) return@execute
                val root = rootInActiveWindow ?: return@execute
                try {
                    // A window may change between the package event and this query. Do not traverse it.
                    if (root.packageName?.toString() != expectedPackage || !eligible(expectedPackage)) return@execute
                    val title = visibleTitle(root, expectedPackage) ?: return@execute
                    if (!eligible(expectedPackage)) return@execute
                    repository.addObservation(
                        packageName = expectedPackage,
                        title = title,
                        kind = if (isBilibili(expectedPackage)) "video_title_candidate" else "screen_title_candidate",
                        source = "accessibility_visible_text_unverified"
                    )
                } finally { recycle(root) }
            } catch (_: SecurityException) {
                // The user may revoke accessibility/usage permissions during an in-flight read.
            } catch (_: IllegalStateException) {
                // A detached accessibility window provides no reliable observation.
            } finally { sampling.set(false) }
        }
    }

    private fun eligible(packageName: String): Boolean = connected &&
        foregroundPackage == packageName && preferences.contentEnabled && !preferences.paused &&
        packageName in preferences.allowedPackages && CapturePolicy.canCapture(packageName) &&
        getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked && notificationAvailable() &&
        statusNotificationPresent()

    private fun visibleTitle(root: AccessibilityNodeInfo, packageName: String): String? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val candidates = ArrayList<Pair<Int, String>>()
        pending.add(root)
        var visited = 0
        try {
            while (pending.isNotEmpty() && visited++ < 350) {
                val node = pending.removeFirst()
                try {
                    val viewId = node.viewIdResourceName.orEmpty()
                    val className = node.className?.toString().orEmpty()
                    if (node.packageName?.toString() != packageName || !node.isVisibleToUser || node.isPassword || node.isEditable ||
                        className.contains("EditText", true) || CapturePolicy.sensitiveViewId(viewId)) continue
                    val text = node.text?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                    if (CapturePolicy.safeCandidate(text)) {
                        val hasTitleId = listOf("title", "video_name", "archive_name", "tv_desc").any { viewId.contains(it, true) }
                        val isHeading = Build.VERSION.SDK_INT >= 28 && node.isHeading
                        val score = (if (hasTitleId) 1000 else 0) + (if (isHeading) 600 else 0) + text.length.coerceAtMost(120)
                        // Bilibili's title is best-effort; without a semantic title/heading, don't guess from comments.
                        if (!isBilibili(packageName) || hasTitleId || isHeading) candidates.add(score to text)
                    }
                    for (index in 0 until node.childCount.coerceAtMost(80)) {
                        if (pending.size + visited >= 350) break
                        node.getChild(index)?.let(pending::addLast)
                    }
                } finally { if (node !== root) recycle(node) }
            }
        } finally { while (pending.isNotEmpty()) pending.removeFirst().let { if (it !== root) recycle(it) } }
        return candidates.maxByOrNull { it.first }?.second
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "内容记录状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示可选内容记录是否启用，并提供暂停入口"
                setShowBadge(false)
            }
        )
    }

    private fun notificationAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun statusNotificationPresent(): Boolean = runCatching {
        getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.id == NOTIFICATION_ID && it.notification.channelId == CHANNEL
        }
    }.getOrDefault(false)

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (!preferences.contentEnabled) { manager.cancel(NOTIFICATION_ID); return }
        if (!notificationAvailable()) return
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        val content = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getBroadcast(this, 2, Intent(ACTION_PAUSE).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            preferences.paused -> "已暂停应用时长导入和内容记录；点按打开设置"
            preferences.allowedPackages.isEmpty() -> "尚未选择应用；点按设置允许记录的应用"
            else -> "仅记录所选应用的可见标题线索，每 15 秒最多一次"
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Manage Time · ${if (preferences.paused) "已暂停" else "内容记录已开启"}")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
        if (!preferences.paused) builder.addAction(Notification.Action.Builder(null, "暂停记录", pause).build())
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    override fun onInterrupt() { foregroundPackage = null }
    override fun onDestroy() {
        connected = false
        foregroundPackage = null
        handler.removeCallbacksAndMessages(null)
        if (::preferences.isInitialized) preferences.store.unregisterOnSharedPreferenceChangeListener(settingsListener)
        if (receiverRegistered) { unregisterReceiver(pauseReceiver); receiverRegistered = false }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        executor.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION") private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }
    private fun isBilibili(packageName: String): Boolean = packageName == "tv.danmaku.bili" || packageName == "com.bilibili.app.in"

    private companion object {
        const val SAMPLE_INTERVAL = 15_000L
        const val CHANNEL = "content_capture"
        const val NOTIFICATION_ID = 7001
        const val ACTION_PAUSE = "com.managetime.app.PAUSE_RECORDING"
    }
}
