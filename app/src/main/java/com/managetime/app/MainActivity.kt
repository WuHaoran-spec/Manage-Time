package com.managetime.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.managetime.app.data.AppEntry
import com.managetime.app.data.AppRepository
import com.managetime.app.data.ContentObservation
import com.managetime.app.data.RecorderPreferences
import com.managetime.app.capture.CapturePolicy
import com.managetime.core.AppUsage
import com.managetime.core.UsageEngine
import com.managetime.core.UsageSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFF183F35)
private val Mint = Color(0xFFBDF2CE)
private val Paper = Color(0xFFF6F8F5)
private val Muted = Color(0xFF718178)
private val Palette = listOf(Color(0xFF287A61), Color(0xFF729AEE), Color(0xFFE5B25F), Color(0xFFB095CF), Color(0xFFE18F88), Color(0xFF71B8B6))
private fun appColor(name: String) = Palette[(name.hashCode().toLong().let { if (it < 0) -it else it } % Palette.size).toInt()]
private fun duration(ms: Long): String {
    val m = ms.coerceAtLeast(0) / 60_000
    return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else if (m > 0) "${m} 分钟" else "${ms.coerceAtLeast(0) / 1000} 秒"
}
private fun shortDuration(ms: Long) = if (ms >= 3_600_000) String.format(Locale.ROOT, "%.1fh", ms / 3_600_000.0) else "${ms / 60_000}m"
private fun dayStart(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun clock(ts: Long) = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

class MainActivity : ComponentActivity() {
    private var revision by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UsageSyncWorker.schedule(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, onPrimary = Color.White, secondary = Color(0xFF287A61), background = Paper, surface = Color.White, onSurface = Ink, surfaceVariant = Color(0xFFEAF0E9))) {
                ManageTime(this, revision)
            }
        }
    }
    override fun onResume() { super.onResume(); revision++ }
}

private data class Dashboard(
    val sessions: List<UsageSession> = emptyList(),
    val observations: List<ContentObservation> = emptyList(),
    val week: List<UsageSession> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val access: Boolean = false,
    val accessibility: Boolean = false,
    val notifications: Boolean = false,
)

@Composable
private fun ManageTime(activity: MainActivity, revision: Int) {
    val repository = remember { AppRepository(activity.applicationContext) }
    val prefs = remember { RecorderPreferences(activity.applicationContext) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf(Dashboard()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var selectedApp by remember { mutableStateOf<String?>(null) }
    var disclosure by remember { mutableStateOf(false) }
    var erase by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val label: (String) -> String = { data.labels[it] ?: it }
    val totals = remember(data.sessions) { UsageEngine.totals(data.sessions) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val csv = repository.exportCsv(dayStart(date), dayStart(date.plusDays(1)))
                    checkNotNull(activity.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use { it.write('\uFEFF'.code); it.write(csv) }
                }
                snackbar.showSnackbar("已导出所选日期的使用记录")
            } catch (_: Exception) { snackbar.showSnackbar("导出失败，请选择可写入的位置") }
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    LaunchedEffect(Unit) { while (true) { delay(60_000); refresh++ } }
    LaunchedEffect(date, revision, refresh) {
        loading = true
        try {
            data = withContext(Dispatchers.IO) {
                val access = repository.hasUsageAccess()
                if (access) repository.syncUsage()
                repository.prune()
                val sessions = repository.sessions(dayStart(date), dayStart(date.plusDays(1)))
                val observations = repository.observations(dayStart(date), dayStart(date.plusDays(1)))
                val week = repository.sessions(dayStart(date.minusDays(6)), dayStart(date.plusDays(1)))
                val apps = repository.installedApps().filter { CapturePolicy.canCapture(it.packageName) }
                val packages = (sessions.map { it.packageName } + week.map { it.packageName } + observations.map { it.packageName } + apps.map { it.packageName }).toSet()
                val manager = activity.getSystemService(AccessibilityManager::class.java)
                val enabled = manager.getEnabledAccessibilityServiceList(-1).any { it.resolveInfo.serviceInfo.packageName == activity.packageName }
                val notificationManager = activity.getSystemService(NotificationManager::class.java)
                val notificationsAllowed = notificationManager.areNotificationsEnabled() && notificationManager.getNotificationChannel("content_capture")?.importance != NotificationManager.IMPORTANCE_NONE
                Dashboard(sessions, observations, week, apps, packages.associateWith { repository.appLabel(it) }, access, enabled, notificationsAllowed)
            }
            status = null
        } catch (_: Exception) { status = "暂时无法读取记录，请检查权限后刷新。" }
        loading = false
    }
    fun openSettings(action: String) {
        try { activity.startActivity(Intent(action)) } catch (_: Exception) { scope.launch { snackbar.showSnackbar("此设备未提供该设置入口，请从系统设置中打开") } }
    }
    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                listOf("今日", "时间轴", "统计", "设置").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { NavGlyph(index, tab == index) }, label = { Text(title, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Mint, selectedIconColor = Ink, selectedTextColor = Ink, unselectedTextColor = Muted))
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(Ink, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("m", color = Mint, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text("Manage Time", fontSize = 19.sp, fontWeight = FontWeight.Bold); Text("把时间，留给在意的事", color = Muted, fontSize = 10.sp) }
                Surface(color = if (prefs.paused) Color(0xFFFFEBD0) else Color(0xFFE0F1E4), shape = RoundedCornerShape(20.dp)) {
                    Text(if (prefs.paused) "已暂停" else "本机记录", color = Ink, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            if (tab != 3) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { date = date.minusDays(1) }) { Text("‹", fontSize = 26.sp) }
                TextButton(onClick = {
                    DatePickerDialog(activity, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                }, modifier = Modifier.weight(1f)) { Text(if (date == LocalDate.now()) "今天 · ${date.monthValue}月${date.dayOfMonth}日" else date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), fontWeight = FontWeight.Medium) }
                TextButton(enabled = date < LocalDate.now(), onClick = { date = date.plusDays(1) }) { Text("›", fontSize = 26.sp) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF287A61), trackColor = Paper)
            status?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp), fontSize = 12.sp) }
            when (tab) {
                0 -> TodayPage(data, totals, date, prefs, label, onPermission = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }, onApp = { selectedApp = it }, onTimeline = { tab = 1 }, onRefresh = { refresh++ })
                1 -> TimelinePage(data, date, label)
                2 -> StatisticsPage(data, date, prefs, label, onApp = { selectedApp = it }, onExport = { export.launch("Manage-Time-$date.csv") })
                3 -> SettingsPage(data, prefs, onChange = { refresh++ }, onUsage = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }, onAccessibility = { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, onEnable = { disclosure = true }, onNotifications = {
                    if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else try { activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.packageName)) } catch (_: Exception) { if(Build.VERSION.SDK_INT>=33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }, onExport = { export.launch("Manage-Time-$date.csv") }, onErase = { erase = true }, exportDate = date)
            }
        }
    }
    selectedApp?.let { pkg -> AppDialog(pkg, label(pkg), prefs, onDismiss = { selectedApp = null }, onSave = { refresh++; selectedApp = null }) }
    if (disclosure) AlertDialog(
        onDismissRequest = { disclosure = false }, title = { Text("开启屏幕观察？") },
        text = { Text("开启后，无障碍服务会读取你所选应用公开的可见标题和页面文字，并把带时间戳的观察保存到手机。可能包含你正在浏览的个人内容。\n\n不录屏、不录音，不读取输入框或密码，不上传数据。默认没有选中任何应用；你需要随后选择应用并在系统中授权。标题可能缺失或识别不准，观察也不能证明你一直在观看。\n\n可随时暂停、关闭或删除。", fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = { prefs.contentEnabled = true; disclosure = false; refresh++ }) { Text("同意并选择应用") } }, dismissButton = { TextButton(onClick = { disclosure = false }) { Text("暂不开启") } })
    if (erase) AlertDialog(onDismissRequest = { erase = false }, title = { Text("删除所有本机记录？") }, text = { Text("删除使用时长和屏幕观察，已删除的历史不会重新导入。已导出的文件需要你自行删除。") }, confirmButton = { TextButton(onClick = { erase = false; scope.launch { withContext(Dispatchers.IO) { repository.clearAll() }; refresh++; snackbar.showSnackbar("本机记录已删除") } }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { erase = false }) { Text("取消") } })
}

@Composable
private fun NavGlyph(index: Int, selected: Boolean) {
    val color = if (selected) Ink else Muted
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height; val stroke = 1.8.dp.toPx()
        when (index) {
            0 -> { drawCircle(color, w * .39f, style = Stroke(stroke)); drawLine(color, Offset(w*.5f,h*.25f),Offset(w*.5f,h*.52f),stroke,StrokeCap.Round);drawLine(color,Offset(w*.5f,h*.52f),Offset(w*.67f,h*.61f),stroke,StrokeCap.Round) }
            1 -> repeat(3) { i -> val y = h*(.2f+i*.3f); drawCircle(color,stroke,Offset(w*.12f,y));drawLine(color,Offset(w*.35f,y),Offset(w*.92f,y),stroke,StrokeCap.Round) }
            2 -> repeat(3) { i -> val x=w*(.18f+i*.31f); drawLine(color,Offset(x,h*.9f),Offset(x,h*(.6f-i*.22f)),stroke*2,StrokeCap.Round) }
            3 -> { drawCircle(color,w*.39f,style=Stroke(stroke));drawCircle(color,w*.13f,style=Stroke(stroke));repeat(4) { i -> val a=i*Math.PI/2;drawLine(color,Offset(w*.5f+(kotlin.math.cos(a)*w*.3).toFloat(),h*.5f+(kotlin.math.sin(a)*h*.3).toFloat()),Offset(w*.5f+(kotlin.math.cos(a)*w*.48).toFloat(),h*.5f+(kotlin.math.sin(a)*h*.48).toFloat()),stroke) } }
        }
    }
}

@Composable
private fun SectionTitle(title: String, aside: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
        aside?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = if (onClick != null) Modifier.clickable(onClick = onClick).padding(8.dp) else Modifier) }
    }
}

@Composable
private fun CardBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content) }
}

@Composable
private fun TodayPage(data: Dashboard, totals: List<AppUsage>, date: LocalDate, prefs: RecorderPreferences, label: (String) -> String, onPermission: () -> Unit, onApp: (String) -> Unit, onTimeline: () -> Unit, onRefresh: () -> Unit) {
    val total = totals.sumOf { it.durationMs }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (!data.access) item { CardBlock { Text("先连接你的时间", fontWeight = FontWeight.Bold, fontSize = 19.sp); Text("允许“使用情况访问”后，即可统计各应用的前台使用时间。屏幕内容记录可另行选择开启。", color = Muted, fontSize = 13.sp); Button(onClick = onPermission, shape = RoundedCornerShape(14.dp)) { Text("授予使用情况权限") } } }
        item {
            Surface(color = Ink, shape = RoundedCornerShape(28.dp)) {
                Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${if(date == LocalDate.now()) "今日" else "当日"}屏幕使用", color = Mint, fontSize = 12.sp)
                        Text(duration(total), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, lineHeight = 35.sp)
                        Text("${totals.size} 个应用 · ${totals.sumOf { it.launches }} 次使用", color = Color(0xFFC1D2C8), fontSize = 11.sp)
                    }
                    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val width = 9.dp.toPx()
                            val diameter = size.width - width
                            drawArc(Color(0xFF386052), -90f, 360f, false, topLeft = Offset(width/2,width/2), size = Size(diameter,diameter), style = Stroke(width))
                            var angle = -90f
                            totals.take(8).forEach { item -> val sweep = if(total>0) item.durationMs.toFloat()/total*360f else 0f; drawArc(appColor(item.packageName), angle, (sweep-3f).coerceAtLeast(0f),false,topLeft=Offset(width/2,width/2),size=Size(diameter,diameter),style=Stroke(width,cap=StrokeCap.Round));angle+=sweep }
                        }
                        Text("${totals.size}", color = Mint, fontSize = 25.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallMetric("最长连续使用", duration(data.sessions.maxOfOrNull { it.durationMs } ?: 0), Modifier.weight(1f))
            SmallMetric("屏幕观察", "${data.observations.size} 条", Modifier.weight(1f))
        } }
        item { CardBlock { SectionTitle("一天的节奏", "分钟详情 ›", onTimeline); DayStrip(data.sessions, date); Text("色块代表不同应用；空白表示没有使用记录。", color = Muted, fontSize = 11.sp) } }
        item { SectionTitle("时间花在哪里", "刷新", onRefresh) }
        if (totals.isEmpty()) item { EmptyState("还没有使用记录", if (data.access) "使用其他应用后回来刷新。系统可提供的历史范围因设备而异。" else "开启使用情况权限后，这里会出现真实数据。") }
        items(totals, key = { it.packageName }) { usage -> AppUsageRow(usage, total, label(usage.packageName), prefs, onClick = { onApp(usage.packageName) }) }
        item { FocusCard() }
        item { Text("统计采用前台应用会话，分屏和画中画只计当前前台应用。多次进入页面也可能形成多次使用。", fontSize = 11.sp, color = Muted, lineHeight = 17.sp) }
    }
}

@Composable
private fun SmallMetric(title: String, value: String, modifier: Modifier) {
    Surface(modifier, color = Color(0xFFE9EFE7), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, color = Muted, fontSize = 11.sp); Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) } }
}

@Composable
private fun DayStrip(sessions: List<UsageSession>, date: LocalDate) {
    val start = dayStart(date); val span = dayStart(date.plusDays(1)) - start
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        drawRoundRect(Color(0xFFF0F3EE), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        sessions.forEach { session ->
            val x = ((session.start-start).toFloat()/span*size.width).coerceIn(0f,size.width)
            val end = ((session.end-start).toFloat()/span*size.width).coerceIn(0f,size.width)
            drawRect(appColor(session.packageName), topLeft=Offset(x,4.dp.toPx()),size=Size((end-x).coerceAtLeast(1f),size.height-8.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { (0..4).map { if(it==4) "24:00" else clock(start+span*it/4) }.forEach { Text(it, color = Muted, fontSize = 9.sp) } }
}

@Composable
private fun AppUsageRow(item: AppUsage, total: Long, name: String, prefs: RecorderPreferences, onClick: () -> Unit) {
    val tag = prefs.tag(item.packageName)
    val budget = prefs.dailyBudgetMinutes(item.packageName)
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppAvatar(name, item.packageName)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Medium, fontSize = 14.sp); Text(listOfNotNull(tag.takeIf { it.isNotBlank() }, "${item.launches} 次使用").joinToString(" · "), fontSize = 10.sp, color = Muted) }
                Text(duration(item.durationMs), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            LinearProgressIndicator(progress = { if(total > 0) (item.durationMs.toFloat()/total).coerceIn(0f,1f) else 0f }, modifier = Modifier.fillMaxWidth().height(4.dp), color = appColor(item.packageName), trackColor = Paper)
            if (budget > 0) Text("每日预算 ${budget} 分钟 · ${if (item.durationMs >= budget*60_000L) "已达到" else "剩余 ${duration(budget*60_000L-item.durationMs)}"}", fontSize = 10.sp, color = if(item.durationMs >= budget*60_000L) Color(0xFFAA6136) else Muted)
        }
    }
}

@Composable
private fun AppAvatar(name: String, pkg: String) {
    Box(Modifier.size(38.dp).background(appColor(pkg).copy(alpha = .14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), color = appColor(pkg), fontWeight = FontWeight.Bold, fontSize = 18.sp) }
}

@Composable
private fun EmptyState(title: String, description: String) {
    CardBlock { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium); Text(description, fontSize = 13.sp, color = Muted, lineHeight = 21.sp) }
}

@Composable
private fun FocusCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val storage = remember { context.getSharedPreferences("focus", 0) }
    var end by remember { mutableLongStateOf(storage.getLong("end", 0)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var minutes by remember { mutableIntStateOf(25) }
    LaunchedEffect(end) { while(end > System.currentTimeMillis()) { now = System.currentTimeMillis(); delay(1000) }; now=System.currentTimeMillis() }
    val left = ((end-now)/1000).coerceAtLeast(0)
    CardBlock {
        SectionTitle("留一段专注时间", "FOCUS")
        Text(if(end>0 && left==0L) "这一段专注已结束。休息一下吧。" else "选一件事，把注意力还给自己。", fontSize = 12.sp, color = Muted)
        if (left > 0) Text(String.format(Locale.ROOT,"%02d:%02d",left/60,left%60), fontSize = 44.sp, fontWeight = FontWeight.Light)
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15,25,45).forEach { value -> FilterChip(selected=minutes==value,onClick={minutes=value},label={Text("${value}分钟")}) } }
        Button(onClick = { end = if(left>0) 0 else System.currentTimeMillis()+minutes*60_000L; now=System.currentTimeMillis();storage.edit().putLong("end",end).apply() }, shape = RoundedCornerShape(14.dp)) { Text(if(left>0) "结束专注" else "开始专注") }
        Text("计时在离开后继续，返回可查看；当前版本不在后台响铃或屏蔽应用。", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun TimelinePage(data: Dashboard, date: LocalDate, label: (String) -> String) {
    var query by remember { mutableStateOf("") }
    var hour by remember(date) { mutableIntStateOf(-1) }
    var allMinutes by remember { mutableStateOf(false) }
    val end = minOf(dayStart(date.plusDays(1)),System.currentTimeMillis())
    val minutes = remember(data.sessions,date,end) { UsageEngine.minutes(data.sessions,dayStart(date),end).groupBy { it.start } }
    val observations = remember(data.observations) { data.observations.groupBy { Math.floorDiv(it.timestamp,60_000)*60_000 } }
    val keys = remember(minutes,observations,allMinutes,date,end) {
        if(allMinutes) generateSequence(Math.floorDiv(dayStart(date),60_000)*60_000) { it+60_000 }.takeWhile { it<end }.toList() else (minutes.keys+observations.keys).sorted()
    }
    val filtered = keys.filter { time -> (hour<0 || Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).hour==hour) && (query.isBlank() || minutes[time].orEmpty().any { label(it.packageName).contains(query,true) } || observations[time].orEmpty().any { it.title.contains(query,true) || label(it.packageName).contains(query,true) }) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("每一分钟，都有迹可循") }
        item { Text("应用用时与屏幕观察按分钟并列。未识别标题表示没有可用观察，不能推断具体内容。", color=Muted,fontSize=12.sp,lineHeight=19.sp) }
        item { OutlinedTextField(value=query,onValueChange={query=it},placeholder={Text("搜索应用、视频标题、帖子…",fontSize=13.sp)},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=hour==-1,onClick={hour=-1},label={Text("全天")}); (0..23).forEach { h -> FilterChip(selected=hour==h,onClick={hour=h},label={Text(String.format(Locale.ROOT,"%02d时",h))}) } } }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Text("显示没有记录的分钟",fontSize=12.sp,modifier=Modifier.weight(1f));Switch(checked=allMinutes,onCheckedChange={allMinutes=it}) } }
        if(filtered.isEmpty()) item { EmptyState("这一段时间还没有记录", "启用权限后开始记录，或切换日期、清除搜索条件。屏幕观察只从主动开启时开始。") }
        items(filtered,key={it}) { timestamp ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
                Column(Modifier.width(55.dp).padding(top=16.dp)) { Text(clock(timestamp),fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text("${Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).offset}",fontSize=8.sp,color=Muted) }
                Surface(Modifier.weight(1f),color=Color.White,shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        val entries = minutes[timestamp].orEmpty()
                        val captures = observations[timestamp].orEmpty()
                        if(entries.isEmpty() && captures.isEmpty()) Text("没有记录",fontSize=12.sp,color=Muted)
                        entries.forEach { entry -> Row(verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(7.dp).background(appColor(entry.packageName),CircleShape));Spacer(Modifier.width(7.dp));Text(label(entry.packageName),fontSize=13.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f));Text("${entry.durationMs/1000}秒",fontSize=11.sp,color=Muted) } }
                        captures.forEach { observation ->
                            HorizontalDivider(color=Paper)
                            Text(observation.title,fontSize=13.sp,lineHeight=20.sp)
                            Text("${clock(observation.timestamp)} · ${label(observation.packageName)} · 屏幕观察",fontSize=9.sp,color=Muted)
                            Text(if(observation.kind=="video_title_candidate") "疑似视频标题 · 无障碍可见文字，未验证" else "页面文字线索 · 无障碍可见文字，未验证",fontSize=9.sp,color=Muted)
                        }
                        if(entries.isNotEmpty() && captures.isEmpty()) Text("未识别到视频或帖子标题",fontSize=10.sp,color=Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsPage(data: Dashboard, date: LocalDate, prefs: RecorderPreferences, label: (String) -> String, onApp:(String)->Unit,onExport:()->Unit) {
    var weekly by remember { mutableStateOf(true) }
    val periodSessions = if(weekly) data.week else data.sessions
    val totals = UsageEngine.totals(periodSessions)
    val total = totals.sumOf { it.durationMs }
    val daily = (6 downTo 0).map { ago -> val d=date.minusDays(ago.toLong()); d to data.week.sumOf { (minOf(it.end,dayStart(d.plusDays(1)))-maxOf(it.start,dayStart(d))).coerceAtLeast(0) } }
    val tags = totals.groupBy { prefs.tag(it.packageName).ifBlank { "未分类" } }.mapValues { it.value.sumOf { usage->usage.durationMs } }.toList().sortedByDescending { it.second }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=!weekly,onClick={weekly=false},label={Text("当天")});FilterChip(selected=weekly,onClick={weekly=true},label={Text("最近7天")}) } }
        item { CardBlock {
            SectionTitle(if(weekly) "过去一周" else "当天统计", "${date.monthValue}.${date.dayOfMonth}")
            Text(duration(total),fontSize=30.sp,fontWeight=FontWeight.SemiBold)
            Text(if(weekly) "日均 ${duration(total/7)} · ${date.minusDays(6)} 至 $date" else "${totals.size} 个应用 · ${totals.sumOf { it.launches }} 次使用",fontSize=11.sp,color=Muted)
            val max = daily.maxOf { it.second }.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(150.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Bottom) {
                daily.forEach { (day,value) -> Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(shortDuration(value),fontSize=9.sp,color=Muted)
                    Box(Modifier.fillMaxWidth().height((value.toFloat()/max*100f).coerceAtLeast(3f).dp).background(if(day==date) Ink else Mint,RoundedCornerShape(7.dp)))
                    Text("${day.monthValue}/${day.dayOfMonth}",fontSize=9.sp,color=Muted)
                } }
            }
            Text("记录不足的日期按已有记录计算，空白天计为 0。",fontSize=10.sp,color=Muted)
        } }
        item { CardBlock { SectionTitle("按标签分配");if(tags.isEmpty()) Text("点击应用可添加学习、工作、娱乐等标签。",fontSize=12.sp,color=Muted);tags.forEach { (tag,value)->Row(Modifier.fillMaxWidth()) { Text(tag,fontSize=13.sp,modifier=Modifier.weight(1f));Text(duration(value),fontSize=13.sp) } } } }
        item { SectionTitle("应用排行",if(weekly) "7天累计" else "当天") }
        items(totals,key={it.packageName}) { usage->
            // Budgets are daily, so only show the daily progress on the daily view.
            if(!weekly) AppUsageRow(usage,total,label(usage.packageName),prefs){onApp(usage.packageName)}
            else Surface(color=Color.White,shape=RoundedCornerShape(18.dp),modifier=Modifier.clickable{onApp(usage.packageName)}) { Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) { AppAvatar(label(usage.packageName),usage.packageName);Spacer(Modifier.width(12.dp));Text(label(usage.packageName),fontSize=13.sp,modifier=Modifier.weight(1f));Text(duration(usage.durationMs),fontSize=12.sp) } }
        }
        item { OutlinedButton(onClick=onExport,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)) { Text("导出 $date 的 CSV") } }
    }
}

@Composable
private fun SettingsPage(data: Dashboard,prefs:RecorderPreferences,onChange:()->Unit,onUsage:()->Unit,onAccessibility:()->Unit,onEnable:()->Unit,onNotifications:()->Unit,onExport:()->Unit,onErase:()->Unit,exportDate:LocalDate) {
    var appQuery by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { SectionTitle("记录，由你掌控") }
        item { CardBlock {
            SettingSwitch("暂停全部记录","暂停期间不导入用时、不读取屏幕内容。",prefs.paused){prefs.paused=it;onChange()}
            HorizontalDivider(color=Paper)
            SettingAction("应用使用情况",if(data.access) "权限已开启" else "需要系统授权",onUsage)
            SettingAction("通知权限",if(data.notifications) "已允许 · 显示记录状态和预算提醒" else "需要开启 · 关闭时屏幕观察也会停止",onNotifications)
        } }
        item { CardBlock {
            SettingSwitch("屏幕观察（可选）","记录所选应用公开的标题或可见文字，仅保存在本机。",prefs.contentEnabled){if(it)onEnable() else {prefs.contentEnabled=false;onChange()}}
            Text("不会自动知道所有帖子或视频。隐藏标题、图片和受保护页面无法识别，版本变化也可能影响结果。",fontSize=11.sp,color=Muted,lineHeight=18.sp)
            if(prefs.contentEnabled) {
                if(!data.notifications) Text("屏幕观察尚不能运行：请开启通知权限及“内容记录状态”通知渠道。",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
                SettingAction("系统无障碍权限",if(data.accessibility) "服务已开启" else "选择应用后，前往系统开启",onAccessibility)
                Text("已选择 ${prefs.allowedPackages.size} 个应用。关闭选择即停止该应用的新观察。",fontSize=11.sp,color=Muted)
                OutlinedTextField(value=appQuery,onValueChange={appQuery=it},placeholder={Text("搜索要记录的应用",fontSize=12.sp)},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))
            }
        } }
        if(prefs.contentEnabled) {
            val apps = data.apps.filter { it.label.contains(appQuery,true) || it.packageName.contains(appQuery,true) }
            if(apps.isEmpty()) item { Text("没有匹配的可选应用。聊天、密码、支付及系统敏感页面不采集。",fontSize=12.sp,color=Muted) }
            items(apps,key={it.packageName}) { app ->
                Surface(color=Color.White,shape=RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(app.label,fontSize=13.sp);Text(app.packageName,fontSize=9.sp,color=Muted) }
                    Checkbox(checked=app.packageName in prefs.allowedPackages,onCheckedChange={checked->prefs.allowedPackages=if(checked)prefs.allowedPackages+app.packageName else prefs.allowedPackages-app.packageName;onChange()})
                } }
            }
        }
        item { CardBlock {
            SectionTitle("数据与隐私")
            Text("本应用没有网络权限。记录保存在应用私有目录，关闭云备份和设备迁移备份。",fontSize=12.sp,color=Muted,lineHeight=20.sp)
            Text("保留时长",fontSize=13.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(7,30,90).forEach { days -> FilterChip(selected=prefs.retentionDays==days,onClick={prefs.retentionDays=days;onChange()},label={Text("${days}天")}) } }
            Text("缩短保留时长会在刷新或下次同步时清理更早的记录。",fontSize=10.sp,color=Muted)
            SettingAction("导出 CSV","导出所选日期 $exportDate 的用时和观察",onExport)
            TextButton(onClick=onErase) { Text("删除所有本机记录",color=MaterialTheme.colorScheme.error) }
        } }
        item { CardBlock { Text("Manage Time · 0.1.0",fontWeight=FontWeight.Bold);Text("开源 · 本机优先 · 无广告",fontSize=12.sp,color=Muted);Text("用时由系统使用事件计算，后台约每15分钟归档一次；省电策略可能延迟归档和预算提醒。屏幕观察需要服务运行和页面公开可读文字。",fontSize=11.sp,color=Muted,lineHeight=18.sp) } }
    }
}

@Composable
private fun SettingSwitch(title:String,description:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f).padding(end=8.dp)) { Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.height(5.dp));Text(description,fontSize=11.sp,color=Muted,lineHeight=17.sp) };Switch(checked=checked,onCheckedChange=onChange) }
}

@Composable
private fun SettingAction(title:String,description:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.height(5.dp));Text(description,fontSize=11.sp,color=Muted) };Text("›",fontSize=24.sp,color=Muted) }
}

@Composable
private fun AppDialog(pkg:String,label:String,prefs:RecorderPreferences,onDismiss:()->Unit,onSave:()->Unit) {
    var tag by remember(pkg) { mutableStateOf(prefs.tag(pkg)) }
    var budget by remember(pkg) { mutableStateOf(prefs.dailyBudgetMinutes(pkg).takeIf{it>0}?.toString()?:"") }
    val valid = budget.isBlank() || (budget.toIntOrNull()?.let { it in 1..1440 } == true)
    AlertDialog(onDismissRequest=onDismiss,title={Text(label)},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text(pkg,fontSize=10.sp,color=Muted)
        OutlinedTextField(value=tag,onValueChange={tag=it.take(24)},label={Text("标签，例如：学习 / 娱乐")},singleLine=true)
        OutlinedTextField(value=budget,onValueChange={budget=it.filter(Char::isDigit).take(4)},label={Text("每日预算（分钟）")},supportingText={Text(if(valid) "留空表示不限制。提醒可能受系统省电影响。" else "请输入 1–1440 分钟")},isError=!valid,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
        Text("预算达到时提醒，不强制阻止使用。通知权限需要开启。",fontSize=11.sp,color=Muted)
    }},confirmButton={TextButton(enabled=valid,onClick={prefs.setTag(pkg,tag.trim());prefs.setDailyBudgetMinutes(pkg,budget.toIntOrNull()?:0);onSave()}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
