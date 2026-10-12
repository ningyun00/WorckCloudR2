package com.example.workclockr2

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private val Blue = Color(0xFF1769E0)
private val Ink = Color(0xFF142238)
private val Muted = Color(0xFF758198)

data class R2Config(val accountId: String, val bucket: String, val accessKey: String, val secretKey: String, val allowedSsid: String)
data class Punch(val id: String = UUID.randomUUID().toString(), val employeeId: String, val at: String, val type: String, val day: String)
data class Employee(val employeeId: String, val displayName: String, val role: String, val passwordSalt: String, val passwordHash: String, val mustChangePassword: Boolean, val enabled: Boolean)

class MainActivity : ComponentActivity() {
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        requestPermissions.launch(perms.toTypedArray())
        setContent { WorkClockApp(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkClockApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("workclock", Context.MODE_PRIVATE) }
    var account by remember { mutableStateOf(prefs.getString("account", "") ?: "") }
    val bucket = "workcloudr2"
    var access by remember { mutableStateOf(prefs.getString("access", "") ?: "") }
    var secret by remember { mutableStateOf(prefs.getString("secret", "") ?: "") }
    var ssid by remember { mutableStateOf(prefs.getString("ssid", "") ?: "") }
    var showSettings by remember { mutableStateOf(account.isBlank() || bucket.isBlank() || access.isBlank() || secret.isBlank()) }
    var employeeId by remember { mutableStateOf(prefs.getString("employee", "") ?: "") }
    var employeeName by remember { mutableStateOf(prefs.getString("name", "") ?: "员工") }
    var role by remember { mutableStateOf(prefs.getString("role", "") ?: "") }
    var loggedIn by remember { mutableStateOf(prefs.getBoolean("loggedIn", false)) }
    var punchList by remember { mutableStateOf(listOf<Punch>()) }
    var selectedDay by remember { mutableStateOf(LocalDate.now()) }
    var showMonthCalendar by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("尚未打卡") }
    var message by remember { mutableStateOf("") }
    var showMessageDialog by remember { mutableStateOf(false) }
    var hintExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(message) { if (message.isNotBlank()) showMessageDialog = true }
    var connectionTest by remember { mutableStateOf("") }
    var showAdminConfigAuth by remember { mutableStateOf(false) }
    var adminConfigPassword by remember { mutableStateOf("") }
    var configUnlocked by remember { mutableStateOf(false) }
    var configAuthMessage by remember { mutableStateOf("") }
    var settingsPage by remember { mutableIntStateOf(0) }
    var adminNewPassword by remember { mutableStateOf("") }
    var adminConfirmPassword by remember { mutableStateOf("") }
    var createId by remember { mutableStateOf("") }
    var createName by remember { mutableStateOf("") }
    var createRole by remember { mutableStateOf("employee") }
    var busy by remember { mutableStateOf(false) }
    var testingConnection by remember { mutableStateOf(false) }
    var creatingEmployee by remember { mutableStateOf(false) }
    var loginId by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var needChangePassword by remember { mutableStateOf(false) }
    var showAdminSummary by remember { mutableStateOf(false) }
    var summaryLoading by remember { mutableStateOf(false) }
    var summaryEmployees by remember { mutableStateOf(listOf<Employee>()) }
    var summaryPunches by remember { mutableStateOf(listOf<Punch>()) }
    var summaryError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val configured = account.isNotBlank() && access.isNotBlank() && secret.isNotBlank()
    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                configUnlocked = false
                showAdminConfigAuth = false
                adminConfigPassword = ""
                configAuthMessage = ""
            } else if (event == Lifecycle.Event.ON_RESUME && loggedIn && configured) {
                // Refresh attendance automatically whenever the app returns to the foreground.
                scope.launch {
                    try {
                        val currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
                        val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                        punchList = store.readPunchesAsync(currentMonth).filter { it.employeeId == employeeId }
                        val todayKey = LocalDate.now().toString()
                        val todays = punchList.filter { it.day == todayKey }.sortedBy { it.at }
                        val lastTemp = todays.indexOfLast { it.type == "TEMP_OUT" }
                        status = if (todays.drop(lastTemp + 1).any { it.type == "IN" }) "已上班" else if (todays.isNotEmpty()) "已打卡" else "尚未打卡"
                        if (showAdminSummary && role == "admin") {
                            summaryEmployees = store.readEmployeesAsync().filter { it.enabled }
                            summaryPunches = store.readPunchesAsync(currentMonth)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("WorkClockRefresh", "恢复前台后刷新考勤失败", e)
                        message = "恢复前台后刷新数据失败：" + (e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    val restrictedUserSettings = configured && loggedIn && role != "admin"
    val hideR2Credentials = configured && !configUnlocked
    val today = LocalDate.now()
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    val dayPunches = punchList.filter { it.day == selectedDay.toString() }.sortedByDescending { it.at }
    val monthKey = today.format(DateTimeFormatter.ofPattern("yyyy-MM"))
    val todayPunches = punchList.filter { it.day == today.toString() }.sortedBy { it.at }
    val lastTempOutIndex = todayPunches.indexOfLast { it.type == "TEMP_OUT" }
    // After the first IN, ordinary punches remain OUT punches; only TEMP_OUT resets the next punch to IN.
    val active = todayPunches.drop(lastTempOutIndex + 1).any { it.type == "IN" }
    val hours = calculateHours(dayPunches)

    fun saveConfig() {
        prefs.edit().putString("account", account.trim()).putString("bucket", "workcloudr2")
            .putString("access", access.trim()).putString("secret", secret).putString("ssid", ssid.trim()).apply()
        showSettings = false
        scope.launch {
            if (ssid.isBlank()) {
                message = "R2 连接配置已保存在本机。考勤 Wi-Fi 尚未设置，请管理员填写 Wi-Fi 名称后再次保存。"
                return@launch
            }
            busy = true
            try {
                R2Store(R2Config(account.trim(), "workcloudr2", access.trim(), secret, ssid.trim())).writeAllowedSsidAsync(ssid.trim())
                message = "配置已保存。考勤 Wi-Fi 已写入 R2 的 app-config.json，所有员工设备将统一使用。请确认 employees.json 已上传。"
            } catch (e: Exception) {
                message = "本机 R2 配置已保存，但共享 Wi-Fi 写入失败：" + (e.message ?: e.javaClass.simpleName)
            } finally { busy = false }
        }
    }
    fun doPunch(type: String) {
        if (!configured) { showSettings = true; message = "请先完成 R2 配置"; return }
        if (!loggedIn) { message = "请先登录"; return }
        scope.launch {
            busy = true
            message = "正在读取统一考勤配置…"
            try {
                val baseStore = R2Store(R2Config(account, bucket, access, secret, ""))
                val allowedSsid = baseStore.readAllowedSsidAsync()
                if (allowedSsid.isBlank()) {
                    message = "管理员尚未在 R2 的 app-config.json 中设置考勤 Wi-Fi，请联系管理员。"
                    return@launch
                }
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (!wm.isWifiEnabled) {
                    message = "Wi-Fi 验证失败：手机 Wi-Fi 未开启，请连接指定 Wi-Fi 后重试。"
                    return@launch
                }
                @Suppress("DEPRECATION")
                val current = runCatching { wm.connectionInfo?.ssid?.trim('"') }.getOrNull()
                when {
                    current.isNullOrBlank() || current == "<unknown ssid>" -> {
                        message = "Wi-Fi 验证失败：Android 未向 App 提供当前 Wi-Fi 名称。请检查 App 的附近 Wi-Fi 权限，并开启手机定位后重试。"
                        return@launch
                    }
                    current != allowedSsid.trim() -> {
                        message = "Wi-Fi 名称不匹配：当前读取到“" + current + "”，要求“" + allowedSsid.trim() + "”。请连接指定 Wi-Fi 后重试。"
                        return@launch
                    }
                }
                message = "正在保存打卡记录…"
                val cfg = R2Config(account, bucket, access, secret, allowedSsid)
                val now = ZonedDateTime.now().toString()
                val punch = Punch(employeeId = employeeId, at = now, type = type, day = today.toString())
                val saved = R2Store(cfg).updatePunchesAndVerifyAsync(monthKey, punch)
                punchList = saved.filter { it.employeeId == employeeId }
                status = when(type) { "IN" -> "已上班"; "OUT" -> "已下班"; else -> "临时下班已记录" }
                message = "$status · 已保存并验证 · ${ZonedDateTime.now().format(fmt)}"
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val detail = buildString {
                    append(t.javaClass.name)
                    append(": ")
                    append(t.message ?: "无错误消息")
                    t.cause?.let { append("\\n原因："); append(it.javaClass.name); append(": "); append(it.message ?: "") }
                }
                Log.e("WorkClockPunch", "打卡失败，阶段：R2 读取/创建/写入/回读", t)
                prefs.edit().putString("last_punch_error", detail.take(1800))
                    .putString("last_punch_error_time", ZonedDateTime.now().toString()).apply()
                message = "打卡失败（已保存诊断）：${detail.take(220)}"
            } finally { busy = false }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Color(0xFFF4F7FC), surface = Color.White)) {
        Scaffold(containerColor = Color(0xFFF4F7FC), topBar = {
            TopAppBar(title = {
                Column {
                    Text("上班打卡", fontWeight = FontWeight.Bold, color = Ink)
                    Text(if (loggedIn) "$employeeName · $employeeId" else "考勤工作台", fontSize = 12.sp, color = Muted)
                }
            }, actions = {
                IconButton(onClick = { if (role == "admin" && loggedIn) { configUnlocked = false; showAdminConfigAuth = false; adminConfigPassword = ""; configAuthMessage = "" }; showSettings = true }) { Icon(Icons.Default.Settings, "设置") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF4F7FC)))
        }, bottomBar = {
            if (loggedIn && !needChangePassword && !showAdminSummary && configured) {
                Surface(shadowElevation = 8.dp, color = Color.White) {
                    OutlinedButton(
                        onClick = {
                            loggedIn = false
                            configUnlocked = false
                            showAdminConfigAuth = false
                            adminConfigPassword = ""
                            prefs.edit().putBoolean("loggedIn", false).apply()
                            punchList = emptyList()
                            message = "已退出登录"
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Logout, null)
                        Spacer(Modifier.width(8.dp))
                        Text("退出登录")
                    }
                }
            }
        }) { padding ->
            if (!configured) {
                Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.CloudOff, null, tint = Blue, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("首次使用，请配置 Cloudflare R2", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("未完成配置前，登录和打卡功能均不可用。", color = Muted)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) { Text("配置 R2") }
                }
            } else if (!loggedIn) {
                Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("欢迎回来", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("请使用管理员分配的六位工号登录", color = Muted)
                    Spacer(Modifier.height(22.dp))
                    OutlinedTextField(loginId, { loginId = it.filter(Char::isDigit).take(6) }, label = { Text("六位工号") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(loginPassword, { loginPassword = it }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val store = R2Store(R2Config(account,bucket,access,secret,ssid))
                                val emp = store.readEmployeesAsync()
                                    .firstOrNull { it.employeeId == loginId && it.enabled }
                                if (emp == null) message = "工号不存在或已停用"
                                else if (!R2Store.verifyPassword(emp, loginPassword)) message = "密码错误"
                                else {
                                    employeeId = emp.employeeId; employeeName = emp.displayName; role = emp.role
                                    loggedIn = true; needChangePassword = emp.mustChangePassword
                                    prefs.edit().putString("employee", employeeId).putString("name", employeeName).putString("role", role).putBoolean("loggedIn", true).apply()
                                    try {
                                        punchList = store.readPunchesAsync(monthKey).filter { it.employeeId == employeeId }
                                        val todayKey = LocalDate.now().toString()
                                        val todays = punchList.filter { it.day == todayKey }.sortedBy { it.at }
                                        val lastTemp = todays.indexOfLast { it.type == "TEMP_OUT" }
                                        status = if (todays.drop(lastTemp + 1).any { it.type == "IN" }) {
                                            "已上班"
                                        } else if (todays.isNotEmpty()) {
                                            "已打卡"
                                        } else {
                                            "尚未打卡"
                                        }
                                        message = if (needChangePassword) "当前为初始密码，请联系管理员更新 employees.json 中的密码配置。" else "登录成功，已同步今日打卡状态。"
                                    } catch (refreshError: Exception) {
                                        Log.e("WorkClockLogin", "登录后读取考勤失败", refreshError)
                                        punchList = emptyList()
                                        status = "状态读取失败"
                                        message = "登录成功，但读取考勤记录失败：" + (refreshError.message ?: refreshError.javaClass.simpleName)
                                    }
                                }
                            } catch (e: Exception) { message = "登录失败：${e.message ?: "请检查 R2 文件"}" }
                            finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "请稍候…" else "登录") }
                }
            } else if (needChangePassword) {
                Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("设置新密码", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("首次登录请修改密码。", color = Muted)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(newPassword, { newPassword = it }, label = { Text("新密码（至少 6 位）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(confirmPassword, { confirmPassword = it }, label = { Text("确认新密码") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(enabled = !busy && newPassword.length >= 6 && newPassword == confirmPassword, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val store = R2Store(R2Config(account,bucket,access,secret,ssid))
                                val list = store.readEmployeesAsync()
                                store.writeEmployeesAsync(list.map { if (it.employeeId == employeeId) R2Store.createEmployee(it.employeeId,it.displayName,it.role,newPassword,false,it.enabled) else it })
                                needChangePassword = false; newPassword = ""; confirmPassword = ""; message = "密码已更新"
                            } catch (e: Exception) { message = "修改失败：" + (e.message ?: "请检查 R2 权限") }
                            finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "保存中…" else "保存新密码") }
                    TextButton(onClick = { loggedIn = false; configUnlocked = false; prefs.edit().putBoolean("loggedIn", false).apply() }) { Text("退出登录") }
                }
            } else if (showAdminSummary && role == "admin") {
                Column(Modifier.fillMaxSize().padding(padding).pointerInput(role) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, amount -> drag += amount },
                        onDragEnd = { if (drag > 70f) showAdminSummary = false; drag = 0f },
                        onDragCancel = { drag = 0f }
                    )
                }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("全员打卡合计", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Text(today.format(DateTimeFormatter.ofPattern("yyyy年MM月")) + " · 本月累计工时", color = Muted, fontSize = 13.sp)
                        }
                        TextButton(onClick = { showAdminSummary = false }) { Icon(Icons.Default.ArrowBack, null); Text("返回") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !summaryLoading, onClick = {
                        scope.launch {
                            summaryLoading = true
                            summaryError = ""
                            try {
                                val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                                summaryEmployees = store.readEmployeesAsync().filter { it.enabled }
                                summaryPunches = store.readPunchesAsync(monthKey)
                            } catch (e: Exception) {
                                summaryError = "读取合计失败：${e.message ?: e.javaClass.simpleName}"
                                message = summaryError
                            } finally { summaryLoading = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp))
                        Text(if (summaryLoading) "正在读取…" else "刷新全员数据")
                    }

                    val totalHours = summaryEmployees.sumOf { emp ->
                        calculateHours(summaryPunches.filter { it.employeeId == emp.employeeId && it.day.startsWith(monthKey) })
                    }
                    Card(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("全员累计工时", color = Muted, fontSize = 13.sp)
                                Text(String.format("%.2f 小时", totalHours), color = Blue, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("员工人数", color = Muted, fontSize = 13.sp)
                                Text("${summaryEmployees.size}", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (summaryEmployees.isEmpty() && !summaryLoading && summaryError.isBlank()) {
                        Text("点击“刷新全员数据”读取 R2 中的员工和考勤记录。", color = Muted, modifier = Modifier.padding(top = 18.dp))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(summaryEmployees.size) { index ->
                                val emp = summaryEmployees[index]
                                val empPunches = summaryPunches.filter { it.employeeId == emp.employeeId && it.day.startsWith(monthKey) }
                                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(emp.displayName, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                Text("${emp.employeeId} · ${if (emp.role == "admin") "管理员" else "员工"}", color = Muted, fontSize = 12.sp)
                                            }
                                            Text(String.format("%.2f 小时", calculateHours(empPunches)), color = Blue, fontWeight = FontWeight.Bold)
                                        }
                                        Text("打卡 ${empPunches.size} 次 · ${empPunches.map { it.day }.distinct().size} 天", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                    Text("向右滑动返回打卡页面", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding).pointerInput(role) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, amount -> drag += amount },
                        onDragEnd = {
                            if (role == "admin" && drag < -70f) {
                                showAdminSummary = true
                                scope.launch {
                                    summaryLoading = true
                                    summaryError = ""
                                    try {
                                        val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                                        summaryEmployees = store.readEmployeesAsync().filter { it.enabled }
                                        summaryPunches = store.readPunchesAsync(monthKey)
                                    } catch (e: Exception) {
                                        summaryError = "读取合计失败：${e.message ?: e.javaClass.simpleName}"
                                        message = summaryError
                                    } finally { summaryLoading = false }
                                }
                            }
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f }
                    )
                }, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        if (message.isNotBlank()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { hintExpanded = !hintExpanded },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 16.dp).padding(horizontal = 8.dp, vertical = if (hintExpanded) 8.dp else 0.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        message,
                                        color = Blue,
                                        fontSize = 11.sp,
                                        maxLines = if (hintExpanded) Int.MAX_VALUE else 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(if (hintExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (hintExpanded) "收起提示" else "展开提示", tint = Muted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { doPunch("TEMP_OUT") }, enabled = !busy) {
                                Icon(Icons.Default.PauseCircle, null); Spacer(Modifier.width(4.dp)); Text("临时下班")
                            }
                        }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(190.dp).background(if (active) Color(0xFF159B72) else Blue, CircleShape)
                                    .clickable(enabled = !busy) { doPunch(if (active) "OUT" else "IN") },
                                    contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(if (active) Icons.Default.Logout else Icons.Default.Fingerprint, null, tint = Color.White, modifier = Modifier.size(46.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text(if (active) "下班打卡" else "上班打卡", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        Text(if (busy) "正在同步…" else "点击记录时间", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(status, color = Muted)
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        if (showMonthCalendar) "本月打卡" else "本周打卡",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Ink,
                                        modifier = Modifier.clickable { showMonthCalendar = !showMonthCalendar }
                                    )
                                    Text(
                                        today.format(DateTimeFormatter.ofPattern("yyyy年MM月")) + " · 点击标题切换",
                                        color = Muted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.clickable { showMonthCalendar = !showMonthCalendar }
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                val monday = today.with(java.time.DayOfWeek.MONDAY)
                                val monthFirst = today.withDayOfMonth(1)
                                val monthDays = monthFirst.lengthOfMonth()
                                val monthLeading = monthFirst.dayOfWeek.value - 1
                                val calendarCells: List<LocalDate?> =
                                    List(monthLeading) { null } + (1..monthDays).map { monthFirst.withDayOfMonth(it) }
                                val calendarWeeks = (calendarCells.size + 6) / 7
                                val displayedDays = if (showMonthCalendar) (1..monthDays).map { monthFirst.withDayOfMonth(it) } else (0..6).map { monday.plusDays(it.toLong()) }
                                val displayedTotalHours = displayedDays.sumOf { d -> calculateHours(punchList.filter { it.day == d.toString() }) }
                                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (showMonthCalendar) "本月总工时" else "本周总工时", color = Muted, fontSize = 13.sp)
                                    Text(String.format("%.2f 小时", displayedTotalHours), color = Blue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                                if (showMonthCalendar) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                        listOf("一","二","三","四","五","六","日").forEach { dayName ->
                                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(dayName, color = Muted, fontSize = 10.sp) }
                                        }
                                    }
                                    (0 until calendarWeeks).forEach { week ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                            (0..6).forEach { col ->
                                                val d = calendarCells.getOrNull(week * 7 + col)
                                                if (d == null) {
                                                    Spacer(Modifier.weight(1f).height(62.dp))
                                                } else {
                                                    val dayHours = calculateHours(punchList.filter { it.day == d.toString() })
                                                    val has = punchList.any { it.day == d.toString() }
                                                    Column(
                                                        Modifier.weight(1f).background(if (d == selectedDay) Color(0xFFE5EEFF) else Color.Transparent, RoundedCornerShape(10.dp))
                                                            .clickable { selectedDay = d }.padding(vertical = 5.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Text(d.dayOfMonth.toString(), color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                        Spacer(Modifier.height(2.dp))
                                                        Text(if (has) String.format("%.1f", dayHours) else "—", color = if (has) Blue else Muted, fontSize = 9.sp, maxLines = 1)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Text("日期下方为当天工时（小时），点击日期查看打卡明细。", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                        (0..6).forEach { offset ->
                                            val d = monday.plusDays(offset.toLong())
                                            val dayHours = calculateHours(punchList.filter { it.day == d.toString() })
                                            val has = punchList.any { it.day == d.toString() }
                                            Column(Modifier.weight(1f).background(if (d == selectedDay) Color(0xFFE5EEFF) else Color.Transparent, RoundedCornerShape(10.dp))
                                                .clickable { selectedDay = d }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(listOf("一","二","三","四","五","六","日")[offset], color = Muted, fontSize = 10.sp)
                                                Spacer(Modifier.height(4.dp))
                                                Text(d.dayOfMonth.toString(), color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                Spacer(Modifier.height(3.dp))
                                                Text(if (has) String.format("%.1f", dayHours) else "—", color = if (has) Blue else Muted, fontSize = 9.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                    Text("日期下方为当天工时（小时）。", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${selectedDay.monthValue}月${selectedDay.dayOfMonth}日 · 打卡明细", fontWeight = FontWeight.Bold, color = Ink)
                                    Text("${"%.2f".format(hours)} 小时", color = Blue, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(10.dp))
                                if (dayPunches.isEmpty()) {
                                    Text("当天暂无打卡记录", color = Muted, modifier = Modifier.padding(vertical = 16.dp))
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                                        verticalArrangement = Arrangement.spacedBy(0.dp)
                                    ) {
                                        items(dayPunches, key = { it.id }) { p ->
                                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Circle, null, tint = when(p.type) { "IN" -> Color(0xFF16A36A); "OUT" -> Color(0xFFDB5864); else -> Color(0xFFED9A32) }, modifier = Modifier.size(10.dp))
                                                Spacer(Modifier.width(10.dp))
                                                Column(Modifier.weight(1f)) {
                                                    Text(when(p.type) { "IN" -> "上班打卡"; "OUT" -> "下班打卡"; else -> "临时下班" }, color = Ink, fontWeight = FontWeight.Medium)
                                                    Text(p.at.replace("T", " ").take(19), color = Muted, fontSize = 12.sp)
                                                }
                                                Text(when(p.type) { "IN" -> "开始"; "OUT" -> "结束"; else -> "结算区间" }, color = Muted, fontSize = 12.sp)
                                            }
                                            HorizontalDivider(color = Color(0xFFF0F3F8))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showMessageDialog && message.isNotBlank()) {
            AlertDialog(
                onDismissRequest = { showMessageDialog = false },
                title = { Text("提示") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { showMessageDialog = false }) { Text("确定") } },
                dismissButton = { TextButton(onClick = { showMessageDialog = false }) { Text("取消") } }
            )
        }
        if (showSettings) {
            AlertDialog(onDismissRequest = { if (configured) { showSettings = false; configUnlocked = false; showAdminConfigAuth = false; adminConfigPassword = "" } }, title = { Text(if (role == "admin" && loggedIn) "管理设置" else "Cloudflare R2 配置") },
                text = {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (role == "admin" && loggedIn) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                listOf("R2 配置", "修改密码", "分配新用户").forEachIndexed { index, label ->
                                    TextButton(onClick = { if (index != 0) { configUnlocked = false; showAdminConfigAuth = false; adminConfigPassword = "" }; settingsPage = index }) { Text(if (settingsPage == index) "● " + label else label) }
                                }
                            }
                        }
                        when (if (role == "admin" && loggedIn) settingsPage else 0) {
                            0 -> {
                                if (restrictedUserSettings) {
                                    Text("存储桶：workcloudr2（固定）", color = Ink, fontWeight = FontWeight.Medium)
                                    Text("R2 配置由管理员统一管理。", color = Muted, fontSize = 12.sp)
                                } else if (hideR2Credentials) {
                                    Text("存储桶：workcloudr2（固定）", color = Ink, fontWeight = FontWeight.Medium)
                                    Text("R2 配置已隐藏。需要查看或修改时，请验证管理员密码。", color = Muted, fontSize = 12.sp)
                                    Button(onClick = { showAdminConfigAuth = true; adminConfigPassword = ""; configAuthMessage = "" }) {
                                        Text("输入管理员密码查看配置")
                                    }
                                } else {
                                    Text("R2 S3 API 凭据。桶固定为 workcloudr2。", color = Muted, fontSize = 12.sp)
                                    OutlinedTextField(account, { account = it }, label = { Text("Account ID") }, singleLine = true)
                                    Text("存储桶：workcloudr2（固定）", color = Ink, fontWeight = FontWeight.Medium)
                                    OutlinedTextField(access, { access = it }, label = { Text("Access Key ID") }, singleLine = true)
                                    OutlinedTextField(secret, { secret = it }, label = { Text("Secret Access Key") }, singleLine = true)
                                    if (role == "admin" && loggedIn) {
                                        OutlinedTextField(ssid, { ssid = it }, label = { Text("统一考勤 Wi-Fi 名称") }, supportingText = { Text("管理员保存后写入 R2/app-config.json，所有员工设备共用；员工无需单独设置。") }, singleLine = true)
                                    }
                                }
                                prefs.getString("last_punch_error", null)?.let { savedError ->
                                    Text("上次打卡诊断（可复制反馈）：$savedError", color = Color(0xFFD34B54), fontSize = 11.sp)
                                    prefs.getString("last_punch_error_time", null)?.let { Text("错误时间：$it", color = Muted, fontSize = 10.sp) }
                                }
                            }
                            1 -> {
                                Text("修改管理员密码", color = Ink, fontWeight = FontWeight.Bold)
                                OutlinedTextField(adminNewPassword, { adminNewPassword = it }, label = { Text("新密码（至少 6 位）") }, singleLine = true)
                                OutlinedTextField(adminConfirmPassword, { adminConfirmPassword = it }, label = { Text("确认新密码") }, singleLine = true)
                                Button(enabled = !busy && adminNewPassword.length >= 6 && adminNewPassword == adminConfirmPassword, onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val store = R2Store(R2Config(account,bucket,access,secret,ssid))
                                            val list = store.readEmployeesAsync(); store.writeEmployeesAsync(list.map { if (it.employeeId == employeeId) R2Store.createEmployee(it.employeeId,it.displayName,it.role,adminNewPassword,false,it.enabled) else it })
                                            adminNewPassword = ""; adminConfirmPassword = ""; message = "管理员密码已更新"
                                        } catch (e: Exception) { message = "修改失败：" + (e.message ?: "请检查 R2 权限") }
                                        finally { busy = false }
                                    }
                                }) { Text(if (busy) "保存中…" else "保存新密码") }
                            }
                            2 -> {
                                Text("创建员工账号", color = Ink, fontWeight = FontWeight.Bold)
                                OutlinedTextField(createId, { createId = it.filter(Char::isDigit).take(6) }, label = { Text("六位工号") }, singleLine = true)
                                OutlinedTextField(createName, { createName = it }, label = { Text("员工姓名") }, singleLine = true)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = createRole == "employee", onClick = { createRole = "employee" }, label = { Text("普通员工") })
                                    FilterChip(selected = createRole == "admin", onClick = { createRole = "admin" }, label = { Text("管理员") })
                                }
                                Text("可输入 1–6 位数字，系统自动补足六位；初始密码为六位工号，首次登录需要修改。", color = Muted, fontSize = 12.sp)
                                Button(enabled = !creatingEmployee && createId.length in 1..6 && createId.all { it.isDigit() } && createName.isNotBlank(), onClick = {
                                    scope.launch {
                                        creatingEmployee = true
                                        try {
                                            val normalizedId = createId.trim().padStart(6, '0')
                                            val store = R2Store(R2Config(account,bucket,access,secret,ssid))
                                            val list = store.readEmployeesAsync(); require(list.none { it.employeeId == normalizedId }) { "该工号已存在" }; store.writeEmployeesAsync(list + R2Store.createEmployee(normalizedId,createName.trim(),createRole,normalizedId,true,true))
                                            message = "账号创建成功，工号：${createId.trim().padStart(6, '0')}，初始密码为该六位工号"; createId = ""; createName = ""
                                        } catch (e: Exception) { message = "创建失败：" + (e.message ?: "请检查 R2 写权限") }
                                        finally { creatingEmployee = false }
                                    }
                                }) { Text(if (creatingEmployee) "正在创建…" else "创建并分配账号") }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (restrictedUserSettings || hideR2Credentials) {
                        TextButton(onClick = { showSettings = false }) { Text("完成") }
                    } else if (settingsPage == 0 || role != "admin" || !loggedIn) {
                        Button(onClick = { saveConfig(); configUnlocked = false; showAdminConfigAuth = false; adminConfigPassword = "" }, enabled = account.isNotBlank() && access.isNotBlank() && secret.isNotBlank()) { Text("保存配置") }
                    } else {
                        TextButton(onClick = { showSettings = false }) { Text("完成") }
                    }
                },
                dismissButton = {
                    Row {
                        if (settingsPage == 0 || role != "admin" || !loggedIn) TextButton(enabled = !testingConnection && account.isNotBlank() && access.isNotBlank() && secret.isNotBlank(), onClick = {
                            scope.launch {
                                testingConnection = true; connectionTest = "正在测试连接…"
                                try { connectionTest = R2Store(R2Config(account.trim(), "workcloudr2", access.trim(), secret, ssid)).testConnectionAsync() }
                                catch (e: Exception) { connectionTest = "测试异常：${e.message ?: e.javaClass.simpleName}" }
                                finally { testingConnection = false; message = connectionTest }
                            }
                        }) { Text(if (testingConnection) "正在测试…" else "测试连接") }
                        if (configured) TextButton(onClick = { showSettings = false; configUnlocked = false; showAdminConfigAuth = false; adminConfigPassword = ""; configAuthMessage = "" }) { Text("取消") }
                    }
                })
        }
        if (showAdminConfigAuth) {
            AlertDialog(
                onDismissRequest = { showAdminConfigAuth = false; configUnlocked = false; adminConfigPassword = ""; configAuthMessage = "" },
                title = { Text("管理员验证") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("输入管理员密码后，才能查看或修改 R2 配置。", color = Muted)
                        OutlinedTextField(
                            value = adminConfigPassword,
                            onValueChange = { adminConfigPassword = it },
                            label = { Text("管理员密码") },
                            singleLine = true
                        )
                        if (configAuthMessage.isNotBlank()) Text(configAuthMessage, color = Color(0xFFD34B54))
                    }
                },
                confirmButton = {
                    Button(enabled = adminConfigPassword.isNotBlank() && !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                                val admins = store.readEmployeesAsync()
                                val valid = admins.any { it.role == "admin" && it.enabled && R2Store.verifyPassword(it, adminConfigPassword) }
                                if (valid) {
                                    configUnlocked = true
                                    showAdminConfigAuth = false
                                    adminConfigPassword = ""
                                    configAuthMessage = ""
                                } else configAuthMessage = "管理员密码错误，或无法验证管理员账号。"
                            } catch (e: Exception) {
                                configAuthMessage = "验证失败：" + (e.message ?: "请检查当前 R2 配置")
                            } finally { busy = false }
                        }
                    }) { Text(if (busy) "验证中…" else "验证并查看") }
                },
                dismissButton = { TextButton(onClick = { showAdminConfigAuth = false; configUnlocked = false; adminConfigPassword = ""; configAuthMessage = "" }) { Text("取消") } }
            )
        }
    }
}

private fun calculateHours(items: List<Punch>): Double {
    if (items.isEmpty()) return 0.0
    val sorted = items.sortedBy { it.at }
    var start: Instant? = null
    var lastOut: Instant? = null
    var total = 0L

    fun instant(p: Punch): Instant? =
        runCatching { ZonedDateTime.parse(p.at).toInstant() }.getOrNull()

    for (p in sorted) {
        val at = instant(p) ?: continue
        when (p.type) {
            "IN" -> {
                // Start a new work interval. A TEMP_OUT explicitly resets the next punch to IN.
                if (start == null) {
                    start = at
                    lastOut = null
                }
            }
            "OUT" -> {
                // Ordinary OUT punches do not reset the state; the latest OUT is the interval end.
                if (start != null) lastOut = at
            }
            "TEMP_OUT" -> {
                // Temporary departure closes the current interval at the TEMP_OUT timestamp itself.
                if (start != null) {
                    total += Duration.between(start, at).toMillis().coerceAtLeast(0)
                    start = null
                    lastOut = null
                }
            }
        }
    }
    // An open interval is counted only up to the latest ordinary OUT punch.
    if (start != null && lastOut != null) {
        total += Duration.between(start, lastOut!!).toMillis().coerceAtLeast(0)
    }
    return total / 3600000.0
}
