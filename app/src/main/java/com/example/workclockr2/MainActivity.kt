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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    var status by remember { mutableStateOf("尚未打卡") }
    var message by remember { mutableStateOf("") }
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
    val scope = rememberCoroutineScope()
    val configured = account.isNotBlank() && access.isNotBlank() && secret.isNotBlank()
    val restrictedUserSettings = configured && loggedIn && role != "admin"
    val hideR2Credentials = configured && !configUnlocked && !(role == "admin" && loggedIn)
    val today = LocalDate.now()
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    val dayPunches = punchList.filter { it.day == selectedDay.toString() }.sortedBy { it.at }
    val monthKey = today.format(DateTimeFormatter.ofPattern("yyyy-MM"))
    val active = dayPunches.lastOrNull()?.type == "IN"
    val hours = calculateHours(dayPunches)

    fun saveConfig() {
        prefs.edit().putString("account", account.trim()).putString("bucket", "workcloudr2")
            .putString("access", access.trim()).putString("secret", secret).putString("ssid", ssid.trim()).apply()
        showSettings = false
        message = "配置已保存。请先在 R2 上传 employees.json，然后登录。"
    }
    fun doPunch(type: String) {
        if (!configured) { showSettings = true; message = "请先完成 R2 配置"; return }
        if (!loggedIn) { message = "请先登录"; return }
        if (ssid.isNotBlank()) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wm.isWifiEnabled) {
                message = "Wi-Fi 验证失败：手机 Wi-Fi 未开启，请连接指定 Wi-Fi 后重试。"; return
            }
            @Suppress("DEPRECATION")
            val current = runCatching { wm.connectionInfo?.ssid?.trim('"') }.getOrNull()
            when {
                current.isNullOrBlank() || current == "<unknown ssid>" -> {
                    message = "Wi-Fi 验证失败：Android 未向 App 提供当前 Wi-Fi 名称。请检查 App 的定位/附近 Wi-Fi 权限，并开启手机定位后重试。"; return
                }
                current != ssid.trim() -> {
                    message = "Wi-Fi 名称不匹配：当前读取到“$current”，要求“${ssid.trim()}”。请确认连接了指定 Wi-Fi，名称需完全一致。"; return
                }
            }
        }
        scope.launch {
            busy = true
            message = "正在保存打卡记录…"
            try {
                val cfg = R2Config(account, bucket, access, secret, ssid)
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
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "设置") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF4F7FC)))
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
                                    punchList = runCatching { store.readPunchesAsync(monthKey).filter { it.employeeId == employeeId } }.getOrDefault(emptyList())
                                    message = if (needChangePassword) "当前为初始密码，请联系管理员更新 employees.json 中的密码配置。" else "登录成功"
                                }
                            } catch (e: Exception) { message = "登录失败：${e.message ?: "请检查 R2 文件"}" }
                            finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "请稍候…" else "登录") }
                    if (message.isNotBlank()) Text(message, color = Blue, modifier = Modifier.padding(top = 12.dp))
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
                    if (message.isNotBlank()) Text(message, color = Blue)
                    TextButton(onClick = { loggedIn = false; configUnlocked = false; prefs.edit().putBoolean("loggedIn", false).apply() }) { Text("退出登录") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                                    Text("本周打卡", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                                    Text(today.format(DateTimeFormatter.ofPattern("yyyy年MM月")), color = Muted, fontSize = 12.sp)
                                }
                                Spacer(Modifier.height(14.dp))
                                val monday = today.with(java.time.DayOfWeek.MONDAY)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                    (0..6).forEach { offset ->
                                        val d = monday.plusDays(offset.toLong())
                                        val has = punchList.any { it.day == d.toString() }
                                        Column(Modifier.weight(1f).background(if (d == selectedDay) Color(0xFFE5EEFF) else Color.Transparent, RoundedCornerShape(12.dp))
                                            .clickable { selectedDay = d }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(listOf("一","二","三","四","五","六","日")[offset], color = Muted, fontSize = 12.sp)
                                            Spacer(Modifier.height(6.dp))
                                            Text(d.dayOfMonth.toString(), color = Ink, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(6.dp))
                                            Icon(if (has) Icons.Default.CheckCircle else Icons.Default.Circle, null, tint = if (has) Color(0xFF19A974) else Color(0xFFDCE3ED), modifier = Modifier.size(12.dp))
                                        }
                                    }
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
                                if (dayPunches.isEmpty()) Text("当天暂无打卡记录", color = Muted, modifier = Modifier.padding(vertical = 16.dp))
                                else dayPunches.forEach { p ->
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
                    item {
                        OutlinedButton(onClick = {
                            loggedIn = false
                            configUnlocked = false
                            prefs.edit().putBoolean("loggedIn", false).apply()
                            message = "已退出登录"
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("退出登录") }
                        if (message.isNotBlank()) Text(message, color = Blue, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        if (showSettings) {
            AlertDialog(onDismissRequest = { if (configured) showSettings = false }, title = { Text(if (role == "admin" && loggedIn) "管理设置" else "Cloudflare R2 配置") },
                text = {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (role == "admin" && loggedIn) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                listOf("R2 配置", "修改密码", "分配新用户").forEachIndexed { index, label ->
                                    TextButton(onClick = { settingsPage = index }) { Text(if (settingsPage == index) "● " + label else label) }
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
                                        OutlinedTextField(ssid, { ssid = it }, label = { Text("指定 WiFi 名称（可选）") }, singleLine = true)
                                    }
                                }
                                if (connectionTest.isNotBlank()) Text(connectionTest, color = if (connectionTest.startsWith("连接成功")) Color(0xFF16865C) else Blue, fontSize = 12.sp)
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
                                if (message.isNotBlank()) Text(message, color = Blue, fontSize = 12.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    if (restrictedUserSettings || hideR2Credentials) {
                        TextButton(onClick = { showSettings = false }) { Text("完成") }
                    } else if (settingsPage == 0 || role != "admin" || !loggedIn) {
                        Button(onClick = { saveConfig(); configUnlocked = false }, enabled = account.isNotBlank() && access.isNotBlank() && secret.isNotBlank()) { Text("保存配置") }
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
                                finally { testingConnection = false }
                            }
                        }) { Text(if (testingConnection) "正在测试…" else "测试连接") }
                        if (configured) TextButton(onClick = { showSettings = false }) { Text("取消") }
                    }
                })
        }
        if (showAdminConfigAuth) {
            AlertDialog(
                onDismissRequest = { showAdminConfigAuth = false },
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
                dismissButton = { TextButton(onClick = { showAdminConfigAuth = false }) { Text("取消") } }
            )
        }
    }
}

private fun calculateHours(items: List<Punch>): Double {
    if (items.isEmpty()) return 0.0
    val sorted = items.sortedBy { it.at }
    var start: Instant? = null
    var total = 0L
    var hasTemp = false
    for (p in sorted) {
        val instant = runCatching { ZonedDateTime.parse(p.at).toInstant() }.getOrNull() ?: continue
        when (p.type) {
            "IN" -> if (start == null) start = instant
            "TEMP_OUT" -> {
                if (start != null) { total += Duration.between(start, instant).toMillis().coerceAtLeast(0); start = null; hasTemp = true }
            }
            "OUT" -> if (start != null) { total += Duration.between(start, instant).toMillis().coerceAtLeast(0); start = null }
        }
    }
    if (!hasTemp) {
        val first = sorted.firstOrNull { it.type == "IN" }
        val last = sorted.lastOrNull { it.type == "OUT" }
        if (first != null && last != null) {
            val a = runCatching { ZonedDateTime.parse(first.at).toInstant() }.getOrNull()
            val b = runCatching { ZonedDateTime.parse(last.at).toInstant() }.getOrNull()
            if (a != null && b != null) return Duration.between(a,b).toMillis().coerceAtLeast(0) / 3600000.0
        }
    }
    return total / 3600000.0
}
