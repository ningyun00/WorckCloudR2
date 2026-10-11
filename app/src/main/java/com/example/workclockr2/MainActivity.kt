package com.example.workclockr2

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.launch
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
    var busy by remember { mutableStateOf(false) }
    var loginId by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var needChangePassword by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val configured = account.isNotBlank() && access.isNotBlank() && secret.isNotBlank()
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
            @Suppress("DEPRECATION")
            val current = wm.connectionInfo?.ssid?.trim('"')
            if (current.isNullOrBlank() || current == "<unknown ssid>" || current != ssid.trim()) {
                message = "当前 WiFi 不符合要求，已禁止打卡"; return
            }
        }
        scope.launch {
            busy = true
            try {
                val cfg = R2Config(account, bucket, access, secret, ssid)
                val store = R2Store(cfg)
                val existing = store.readPunches(monthKey)
                val now = ZonedDateTime.now().toString()
                val punch = Punch(employeeId = employeeId, at = now, type = type, day = today.toString())
                val merged = existing.filterNot { it.id == punch.id } + punch
                store.writePunches(monthKey, merged)
                punchList = merged.filter { it.employeeId == employeeId }
                status = when(type) { "IN" -> "已上班"; "OUT" -> "已下班"; else -> "临时下班已记录" }
                message = "$status · ${ZonedDateTime.now().format(fmt)}"
            } catch (e: Exception) {
                message = "R2 操作失败：${e.message ?: "请检查配置与网络"}"
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
                                val emp = R2Store(R2Config(account,bucket,access,secret,ssid)).readEmployees()
                                    .firstOrNull { it.employeeId == loginId && it.enabled }
                                if (emp == null) message = "工号不存在或已停用"
                                else if (!R2Store.verifyPassword(emp, loginPassword)) message = "密码错误"
                                else {
                                    employeeId = emp.employeeId; employeeName = emp.displayName; role = emp.role
                                    loggedIn = true; needChangePassword = emp.mustChangePassword
                                    prefs.edit().putString("employee", employeeId).putString("name", employeeName).putString("role", role).putBoolean("loggedIn", true).apply()
                                    punchList = R2Store(R2Config(account,bucket,access,secret,ssid)).readPunches(monthKey).filter { it.employeeId == employeeId }
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
                    Text("需要修改初始密码", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("为避免默认密码带来的风险，请联系管理员修改 R2 中 employees.json 的 passwordSalt、passwordHash 和 mustChangePassword。", color = Muted)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { loggedIn = false; prefs.edit().putBoolean("loggedIn", false).apply() }, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
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
                            prefs.edit().putBoolean("loggedIn", false).apply()
                            message = "已退出登录"
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("退出登录") }
                        if (message.isNotBlank()) Text(message, color = Blue, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        if (showSettings) {
            AlertDialog(onDismissRequest = { if (configured) showSettings = false }, title = { Text("Cloudflare R2 配置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("请填写 R2 S3 API 凭据。桶已固定为 workcloudr2。测试连接会读取 employees.json，并区分常见错误。密钥保存在本机，但仍可能被提取。", color = Muted, fontSize = 12.sp)
                        OutlinedTextField(account, { account = it }, label = { Text("Account ID") }, singleLine = true)
                        Text("存储桶：workcloudr2（已固定，无需选择）", color = Ink, fontWeight = FontWeight.Medium)
                        OutlinedTextField(access, { access = it }, label = { Text("Access Key ID") }, singleLine = true)
                        OutlinedTextField(secret, { secret = it }, label = { Text("Secret Access Key") }, singleLine = true)
                        OutlinedTextField(ssid, { ssid = it }, label = { Text("指定 WiFi 名称（可选）") }, singleLine = true)
                        if (connectionTest.isNotBlank()) Text(connectionTest, color = if (connectionTest.startsWith("连接成功")) Color(0xFF16865C) else Blue, fontSize = 12.sp)
                    }
                },
                confirmButton = { Button(onClick = { saveConfig() }, enabled = account.isNotBlank() && access.isNotBlank() && secret.isNotBlank()) { Text("保存配置") } },
                dismissButton = {
                    Row {
                        TextButton(enabled = !busy && account.isNotBlank() && access.isNotBlank() && secret.isNotBlank(), onClick = {
                            scope.launch {
                                busy = true; connectionTest = "正在测试连接…"
                                try { connectionTest = R2Store(R2Config(account.trim(), "workcloudr2", access.trim(), secret, ssid)).testConnection() }
                                catch (e: Exception) { connectionTest = "测试异常：${e.message ?: e.javaClass.simpleName}" }
                                finally { busy = false }
                            }
                        }) { Text("测试连接") }
                        if (configured) TextButton(onClick = { showSettings = false }) { Text("取消") }
                    }
                })
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
