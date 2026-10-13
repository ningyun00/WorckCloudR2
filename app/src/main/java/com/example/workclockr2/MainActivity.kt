package com.example.workclockr2

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
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
data class MakeUpSegment(val start: String = "09:00", val end: String = "18:00")
data class Employee(val employeeId: String, val displayName: String, val role: String, val passwordSalt: String, val passwordHash: String, val mustChangePassword: Boolean, val enabled: Boolean)

data class ProfileData(val employeeId: String, val name: String, val gender: String, val age: String, val avatarKey: String, val background: Int)

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
    var isDarkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
    val pageBackground = if (isDarkMode) Color(0xFF101B22) else Color(0xFFF4F7FC)
    val cardBackground = if (isDarkMode) Color(0xFF1C2935) else Color.White
    val selectedDayBackground = if (isDarkMode) Color(0xFF263C54) else Color(0xFFEAF2FF)
    val Ink = if (isDarkMode) Color(0xFFE7EDF5) else Color(0xFF142238)
    val Muted = if (isDarkMode) Color(0xFFA8B4C5) else Color(0xFF758198)
    var account by remember { mutableStateOf(prefs.getString("account", "") ?: "") }
    val bucket = "workcloudr2"
    var access by remember { mutableStateOf(prefs.getString("access", "") ?: "") }
    var secret by remember { mutableStateOf(prefs.getString("secret", "") ?: "") }
    var ssid by remember { mutableStateOf(prefs.getString("ssid", "") ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    var configMethod by remember { mutableStateOf("选择配置方式") }
    var qrConfigCompleted by remember { mutableStateOf(prefs.getBoolean("qr_config_completed", false)) }
    var qrScanMessage by remember { mutableStateOf("") }
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
    var showMakeUpDialog by remember { mutableStateOf(false) }
    var makeUpEmployees by remember { mutableStateOf(listOf<Employee>()) }
    var makeUpEmployeeId by remember { mutableStateOf("") }
    var makeUpDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var makeUpSegments by remember { mutableStateOf(listOf(MakeUpSegment())) }
    var makeUpSaving by remember { mutableStateOf(false) }
    var testingConnection by remember { mutableStateOf(false) }
    var creatingEmployee by remember { mutableStateOf(false) }
    var loginId by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var needChangePassword by remember { mutableStateOf(false) }
    var showAdminSummary by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var profileGender by remember { mutableStateOf("未设置") }
    var profileAge by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf(prefs.getString("name", "员工") ?: "员工") }
    var profileBackground by remember { mutableIntStateOf(prefs.getInt("profile_background", 0)) }
    var profileAvatar by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var profileBackgroundImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var profileLoading by remember { mutableStateOf(false) }
    var profileMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            profileLoading = true
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取图片")
                require(bytes.size <= 8 * 1024 * 1024) { "头像不能超过 8 MB" }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片格式不支持")
                val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                val key = "profiles/avatars/" + employeeId + ".jpg"
                store.writeBinaryAsync(key, bytes, "image/jpeg")
                store.writeProfileAsync(ProfileData(employeeId, profileName, profileGender, profileAge, key, profileBackground))
                profileAvatar = bitmap
                profileMessage = "头像已上传并保存到 R2。"
            } catch (e: Exception) { profileMessage = "头像上传失败：" + (e.message ?: e.javaClass.simpleName) }
            finally { profileLoading = false }
        }
    }
    val backgroundPicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            profileLoading = true
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取背景图片")
                require(bytes.size <= 8 * 1024 * 1024) { "背景图片不能超过 8 MB" }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片格式不支持")
                val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                val key = "profiles/backgrounds/" + employeeId + ".jpg"
                store.writeBinaryAsync(key, bytes, "image/jpeg")
                profileBackgroundImage = bitmap
                profileMessage = "背景图片已上传并保存到 R2。"
            } catch (e: Exception) { profileMessage = "背景图片上传失败：" + (e.message ?: e.javaClass.simpleName) }
            finally { profileLoading = false }
        }
    }
    LaunchedEffect(showProfile, employeeId, configured) {
        if (showProfile && employeeId.isNotBlank() && configured) {
            try {
                val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                val bytes = store.readBinaryAsync("profiles/backgrounds/" + employeeId + ".jpg")
                profileBackgroundImage = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                val profile = store.readProfileAsync(employeeId)
                if (profile != null) {
                    profileName = profile.name
                    profileGender = profile.gender
                    profileAge = profile.age
                    profileBackground = profile.background
                    val avatarBytes = store.readBinaryAsync(profile.avatarKey)
                    profileAvatar = avatarBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            } catch (e: Exception) {
                Log.e("WorkClockProfile", "读取个人背景/资料失败", e)
            }
        }
    }
    var showMakeUpRecords by remember { mutableStateOf(false) }
    var makeUpQueryEmployee by remember { mutableStateOf("") }
    var makeUpQueryStart by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var makeUpQueryEnd by remember { mutableStateOf(LocalDate.now().toString()) }
    var makeUpQueryPunches by remember { mutableStateOf(listOf<Punch>()) }
    var editingMakeUpGroup by remember { mutableStateOf<String?>(null) }
    var pendingDeleteMakeUp by remember { mutableStateOf<Pair<Punch, Punch>?>(null) }
    var editingMakeUpDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var editingMakeUpStart by remember { mutableStateOf("09:00") }
    var editingMakeUpEnd by remember { mutableStateOf("18:00") }
    var summaryLoading by remember { mutableStateOf(false) }
    var summaryEmployees by remember { mutableStateOf(listOf<Employee>()) }
    var summaryPunches by remember { mutableStateOf(listOf<Punch>()) }
    var summaryError by remember { mutableStateOf("") }
    var selectedSummaryEmployee by remember { mutableStateOf<Employee?>(null) }
    var selectedSummaryDay by remember { mutableStateOf<String?>(null) }
    val canNavigateBack = showAdminConfigAuth || showSettings || needChangePassword ||
        (role == "admin" && (selectedSummaryDay != null || selectedSummaryEmployee != null || showAdminSummary || showMakeUpRecords || editingMakeUpGroup != null))
    BackHandler(enabled = canNavigateBack) {
        when {
            showAdminConfigAuth -> {
                showAdminConfigAuth = false
                adminConfigPassword = ""
                configAuthMessage = ""
            }
            showSettings -> {
                showSettings = false
                configUnlocked = false
            }
            needChangePassword -> {
                needChangePassword = false
                newPassword = ""
                confirmPassword = ""
            }
            role == "admin" && editingMakeUpGroup != null -> editingMakeUpGroup = null
            role == "admin" && showMakeUpRecords -> showMakeUpRecords = false
            role == "admin" && selectedSummaryDay != null -> selectedSummaryDay = null
            role == "admin" && selectedSummaryEmployee != null -> {
                selectedSummaryEmployee = null
                selectedSummaryDay = null
            }
            role == "admin" && showAdminSummary -> showAdminSummary = false
        }
    }
    val configured = account.isNotBlank() && access.isNotBlank() && secret.isNotBlank()
    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Rehydrate durable settings/session after process recreation or vendor task cleanup.
                // Never treat returning to foreground as a logout.
                val savedAccount = prefs.getString("account", "") ?: ""
                val savedAccess = prefs.getString("access", "") ?: ""
                val savedSecret = prefs.getString("secret", "") ?: ""
                val savedSsid = prefs.getString("ssid", "") ?: ""
                val savedEmployee = prefs.getString("employee", "") ?: ""
                val savedName = prefs.getString("name", "员工") ?: "员工"
                val savedRole = prefs.getString("role", "") ?: ""
                val savedLoggedIn = prefs.getBoolean("loggedIn", false)
                if (savedAccount.isNotBlank() && savedAccess.isNotBlank() && savedSecret.isNotBlank()) {
                    account = savedAccount
                    access = savedAccess
                    secret = savedSecret
                    ssid = savedSsid
                    showSettings = false
                }
                if (savedLoggedIn && savedEmployee.isNotBlank()) {
                    employeeId = savedEmployee
                    employeeName = savedName
                    role = savedRole
                    loggedIn = true
                } else if (!savedLoggedIn) {
                    loggedIn = false
                }
                if (loggedIn && account.isNotBlank() && access.isNotBlank() && secret.isNotBlank()) {
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

    // Persist valid credentials as they are entered, not only when the dialog button is tapped.
    LaunchedEffect(account, access, secret, ssid) {
        if (account.isNotBlank() && access.isNotBlank() && secret.isNotBlank()) {
            prefs.edit()
                .putString("account", account.trim())
                .putString("bucket", "workcloudr2")
                .putString("access", access.trim())
                .putString("secret", secret)
                .putString("ssid", ssid.trim())
                .commit()
        }
    }

    fun saveConfig() {
        val saved = prefs.edit().putString("account", account.trim()).putString("bucket", "workcloudr2")
            .putString("access", access.trim()).putString("secret", secret).putString("ssid", ssid.trim()).commit()
        if (!saved) {
            message = "R2 配置写入本机失败，请检查手机存储空间。"
            return
        }
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
    val qrScanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val payload: String? = result.contents
        if (payload.isNullOrBlank()) {
            qrScanMessage = "未读取到二维码内容，请对准管理员提供的 R2 配置二维码重试。"
        } else {
            try {
                val json = com.google.gson.JsonParser.parseString(payload).asJsonObject
                account = json.get("AccountID")?.asString ?: json.get("account")?.asString ?: json.get("accountId")?.asString ?: ""
                access = json.get("AccessKeyID")?.asString ?: json.get("access")?.asString ?: json.get("accessKey")?.asString ?: ""
                secret = json.get("SecretAccessKey")?.asString ?: json.get("secret")?.asString ?: json.get("secretKey")?.asString ?: ""
                ssid = json.get("SSID")?.asString ?: json.get("ssid")?.asString ?: json.get("allowedSsid")?.asString ?: ""
                require(account.isNotBlank() && access.isNotBlank() && secret.isNotBlank()) { "二维码缺少 account、access 或 secret 字段" }
                prefs.edit().putString("account", account.trim()).putString("bucket", "workcloudr2").putString("access", access.trim()).putString("secret", secret).putString("ssid", ssid.trim()).putBoolean("qr_config_completed", true).commit()
                qrConfigCompleted = true
                configMethod = "手动配置"
                qrScanMessage = "扫码成功，已读取 R2 配置。请确认信息后点击保存配置。"
            } catch (e: Exception) {
                qrScanMessage = "二维码格式不正确：" + (e.message ?: "请使用 R2 配置二维码")
            }
        }
    }
    fun scanR2Qr() {
        try {
            qrScanMessage = "正在打开本地二维码扫描器…"
            val options = com.journeyapps.barcodescanner.ScanOptions()
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setPrompt("扫描管理员提供的 R2 配置二维码")
                .setBeepEnabled(false)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
                .setOrientationLocked(true)
            qrScanLauncher.launch(options)
        } catch (e: Exception) {
            qrScanMessage = "无法启动扫码器：" + (e.message ?: e.javaClass.simpleName) + "。请检查相机权限，或使用“手动配置”。"
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

    val appColorScheme = if (isDarkMode) darkColorScheme(primary = Blue, background = pageBackground, surface = cardBackground, onBackground = Ink, onSurface = Ink) else lightColorScheme(primary = Blue, background = pageBackground, surface = cardBackground, onBackground = Ink, onSurface = Ink)
    MaterialTheme(colorScheme = appColorScheme) {
        Scaffold(containerColor = pageBackground, topBar = {
            TopAppBar(title = {
                Column {
                    Text("上班打卡", fontWeight = FontWeight.Bold, color = Ink)
                    Text(if (loggedIn) "$employeeName · $employeeId" else "考勤工作台", fontSize = 12.sp, color = Muted)
                }
            }, actions = {
                IconButton(onClick = { isDarkMode = !isDarkMode; prefs.edit().putBoolean("dark_mode", isDarkMode).apply() }) {
                    Icon(if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode, if (isDarkMode) "切换白天模式" else "切换黑夜模式", tint = Ink)
                }
                IconButton(onClick = { if (role == "admin" && loggedIn) { configUnlocked = false; showAdminConfigAuth = false; adminConfigPassword = ""; configAuthMessage = "" }; showSettings = true }) { Icon(Icons.Default.Settings, "设置") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = pageBackground))
        }, bottomBar = {
            if (loggedIn && !needChangePassword && !showAdminSummary && configured) {
                Surface(shadowElevation = 8.dp, color = cardBackground) {
                    OutlinedButton(
                        onClick = {
                            loggedIn = false
                            configUnlocked = false
                            showAdminConfigAuth = false
                            adminConfigPassword = ""
                            prefs.edit().putBoolean("loggedIn", false).commit()
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
                                    prefs.edit().putString("employee", employeeId).putString("name", employeeName).putString("role", role).putBoolean("loggedIn", true).commit()
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
                    TextButton(onClick = { loggedIn = false; configUnlocked = false; prefs.edit().putBoolean("loggedIn", false).commit() }) { Text("退出登录") }
                }
            } else if (selectedSummaryEmployee != null && role == "admin") {
                val emp = selectedSummaryEmployee!!
                val empPunches = summaryPunches.filter { it.employeeId == emp.employeeId && it.day.startsWith(monthKey) }
                val empDays = empPunches.map { it.day }.distinct()
                val empTotalHours = empPunches.groupBy { it.day }.values.sumOf { calculateHours(it) }
                val reportMonth = YearMonth.now()
                val calendarStartOffset = reportMonth.atDay(1).dayOfWeek.value - 1
                val calendarCells: List<LocalDate?> = List(calendarStartOffset) { null } +
                    (1..reportMonth.lengthOfMonth()).map { reportMonth.atDay(it) }
                val selectedDayPunches = selectedSummaryDay?.let { day ->
                    empPunches.filter { it.day == day }.sortedBy { it.at }
                } ?: emptyList()
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            if (selectedSummaryDay != null) selectedSummaryDay = null else selectedSummaryEmployee = null
                        }) {
                            Icon(Icons.Default.ArrowBack, null)
                            Text(if (selectedSummaryDay != null) "返回日历" else "返回全员")
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(emp.displayName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Text("${emp.employeeId} · ${if (emp.role == "admin") "管理员" else "员工"} · 本月报表", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = cardBackground)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("本月总工时", color = Muted, fontSize = 12.sp)
                                Text(String.format("%.2f 小时", empTotalHours), color = Blue, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            }
                        }
                        Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("打卡天数 / 次数", color = Muted, fontSize = 12.sp)
                                Text("${empDays.size} 天 / ${empPunches.size} 次", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (selectedSummaryDay != null) {
                        Text(selectedSummaryDay!!, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("当日工时：${String.format("%.2f", calculateHours(selectedDayPunches))} 小时", color = Blue, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        if (selectedDayPunches.isEmpty()) {
                            Text("当天没有打卡记录", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
                        } else {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(selectedDayPunches) { punch ->
                                    Card(colors = CardDefaults.cardColors(containerColor = cardBackground), shape = RoundedCornerShape(12.dp)) {
                                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(when (punch.type) { "IN" -> "上班打卡"; "OUT" -> "下班打卡"; else -> "临时下班" },
                                                color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                            Text(punch.at.replace("T", " ").take(19), color = Muted, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text("月度日历 · 点击日期查看打卡明细", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("${reportMonth.year}年${reportMonth.monthValue}月", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                                Text(weekday, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            calendarCells.chunked(7).forEach { week ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    week.forEach { date ->
                                        if (date == null) {
                                            Spacer(Modifier.weight(1f).height(66.dp))
                                        } else {
                                            val dayKey = date.toString()
                                            val dayItems = empPunches.filter { it.day == dayKey }.sortedBy { it.at }
                                            val dayHours = calculateHours(dayItems)
                                            val hasPunch = dayItems.isNotEmpty()
                                            Card(
                                                modifier = Modifier.weight(1f).height(66.dp).clickable { selectedSummaryDay = dayKey },
                                                colors = CardDefaults.cardColors(containerColor = if (hasPunch) selectedDayBackground else cardBackground),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Column(Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center) {
                                                    Text(date.dayOfMonth.toString(), color = if (hasPunch) Blue else Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                    if (hasPunch) Text(String.format("%.1f小时", dayHours), color = Blue, fontSize = 9.sp, maxLines = 1)
                                                }
                                            }
                                        }
                                    }
                                    repeat((7 - week.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f).height(66.dp)) }
                                }
                            }
                        }
                        Text("有打卡记录的日期会显示当天工时；点击任意日期可查看该日记录。", color = Muted, fontSize = 11.sp)
                    }
                }
            } else if (showProfile) {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if (profileBackgroundImage != null) {
                        Image(profileBackgroundImage!!.asImageBitmap(), "个人空间背景", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(Color.Transparent))
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showProfile = false }) { Icon(Icons.Default.ArrowBack, null); Text("返回打卡") }
                        Spacer(Modifier.weight(1f)); Icon(Icons.Default.AutoAwesome, null, tint = Blue)
                    }
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = cardBackground)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(124.dp).background(Color(0xFFE5E9FF), CircleShape).clickable { avatarPicker.launch("image/*") }, contentAlignment = Alignment.Center) {
                                if (profileAvatar != null) Image(profileAvatar!!.asImageBitmap(), "个人头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Icon(Icons.Default.AccountCircle, null, tint = Blue, modifier = Modifier.size(100.dp))
                                Surface(Modifier.align(Alignment.BottomEnd), shape = CircleShape, color = Blue) { Icon(Icons.Default.CameraAlt, null, tint = Color.White, modifier = Modifier.padding(7.dp).size(18.dp)) }
                            }
                            Text(profileName.ifBlank { employeeName }, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                            Text("个人空间 · " + if (role == "admin") "管理员" else "员工", color = Muted, fontSize = 13.sp)
                            Text("工号：" + employeeId, color = Blue, fontWeight = FontWeight.Medium)
                        }
                    }
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = cardBackground)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("个人资料", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                            OutlinedTextField(profileName, { profileName = it }, label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("性别", color = Muted)
                                listOf("男", "女", "未设置").forEach { g -> FilterChip(selected = profileGender == g, onClick = { profileGender = g }, label = { Text(g) }) }
                            }
                            OutlinedTextField(profileAge, { profileAge = it.filter(Char::isDigit).take(3) }, label = { Text("年龄") }, suffix = { Text("岁") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("个人空间背景图片", color = Ink, fontWeight = FontWeight.SemiBold)
                            Text("上传的图片将作为个人空间背景，并按工号保存到 R2。", color = Muted, fontSize = 12.sp)
                            OutlinedButton(enabled = !profileLoading, onClick = { backgroundPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text(if (profileLoading) "处理中…" else "上传/更换背景图片")
                            }
                            Button(enabled = !profileLoading && profileName.isNotBlank() && (profileAge.isBlank() || (profileAge.toIntOrNull() ?: 999) <= 120), onClick = {
                                scope.launch {
                                    profileLoading = true
                                    try {
                                        val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                                        store.writeProfileAsync(ProfileData(employeeId, profileName.trim(), profileGender, profileAge, "profiles/avatars/" + employeeId + ".jpg", profileBackground))
                                        prefs.edit().putString("name", profileName.trim()).apply()
                                        employeeName = profileName.trim()
                                        profileMessage = "个人资料已保存到 R2。"
                                    } catch (e: Exception) { profileMessage = "保存失败：" + (e.message ?: e.javaClass.simpleName) }
                                    finally { profileLoading = false }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text(if (profileLoading) "保存中…" else "保存个人资料") }
                            OutlinedButton(enabled = !profileLoading, onClick = { avatarPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AddAPhoto, null); Spacer(Modifier.width(8.dp)); Text("上传/更换头像（保存至 R2）") }
                            if (profileMessage.isNotBlank()) Text(profileMessage, color = Blue, fontSize = 13.sp)
                        }
                    }
                    Text("轻松工作，自信生活 ✨", color = Muted, fontSize = 12.sp)
                    }
                }
            } else if (showMakeUpRecords && role == "admin") {
                val recordGroups = makeUpQueryPunches
                    .filter { it.employeeId.let { id -> makeUpQueryEmployee.isBlank() || id == makeUpQueryEmployee } }
                    .filter { it.day >= makeUpQueryStart && it.day <= makeUpQueryEnd }
                    .filter { it.id.endsWith("-IN") && it.type == "IN" }
                    .sortedWith(compareByDescending<Punch> { it.day }.thenByDescending { it.at })
                    .mapNotNull { start ->
                        val groupId = start.id.removePrefix("MAKEUP-").removeSuffix("-IN")
                        val end = makeUpQueryPunches.firstOrNull { it.id == "MAKEUP-$groupId-OUT" }
                        if (end == null) null else Triple(groupId, start, end)
                    }
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showMakeUpRecords = false }) {
                            Icon(Icons.Default.ArrowBack, null); Text("返回全员")
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("补班记录", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                    }
                    Text("可按员工和日期范围查询；修改或删除会同步更新考勤记录。", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    var employeeFilterExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedTextField(
                            value = summaryEmployees.firstOrNull { it.employeeId == makeUpQueryEmployee }?.let { "${it.displayName}（${it.employeeId}）" } ?: "全部员工",
                            onValueChange = {}, readOnly = true, label = { Text("员工筛选") },
                            trailingIcon = { IconButton(onClick = { employeeFilterExpanded = true }) { Icon(Icons.Default.ArrowDropDown, null) } },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(expanded = employeeFilterExpanded, onDismissRequest = { employeeFilterExpanded = false }) {
                            DropdownMenuItem(text = { Text("全部员工") }, onClick = { makeUpQueryEmployee = ""; employeeFilterExpanded = false })
                            summaryEmployees.forEach { emp ->
                                DropdownMenuItem(text = { Text("${emp.displayName}（${emp.employeeId}）") }, onClick = { makeUpQueryEmployee = emp.employeeId; employeeFilterExpanded = false })
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = makeUpQueryStart, onValueChange = { makeUpQueryStart = it },
                            label = { Text("开始日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = makeUpQueryEnd, onValueChange = { makeUpQueryEnd = it },
                            label = { Text("结束日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                    Button(enabled = !summaryLoading && runCatching { LocalDate.parse(makeUpQueryStart).isBefore(LocalDate.parse(makeUpQueryEnd).plusDays(1)) }.getOrDefault(false), onClick = {
                        scope.launch {
                            summaryLoading = true
                            summaryError = ""
                            try {
                                val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                                summaryEmployees = store.readEmployeesAsync().filter { it.enabled }
                                val start = LocalDate.parse(makeUpQueryStart)
                                val end = LocalDate.parse(makeUpQueryEnd)
                                val months = generateSequence(YearMonth.from(start)) { current -> if (current < YearMonth.from(end)) current.plusMonths(1) else null }.toList()
                                makeUpQueryPunches = months.flatMap { month -> store.readPunchesAsync(month.toString()) }
                                    .filter { it.id.startsWith("MAKEUP-") && it.type != "MAKEUP_DELETED" }
                            } catch (e: Exception) {
                                summaryError = "查询补班记录失败：${e.message ?: e.javaClass.simpleName}"
                            } finally { summaryLoading = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text(if (summaryLoading) "查询中…" else "查询")
                    }
                    if (summaryError.isNotBlank()) Text(summaryError, color = Color(0xFFD34B54), fontSize = 12.sp)
                    Text("查询到 ${recordGroups.size} 个补班时间段", color = Muted, fontSize = 12.sp)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recordGroups, key = { it.first }) { (groupId, start, end) ->
                            val emp = summaryEmployees.firstOrNull { it.employeeId == start.employeeId }
                            Card(colors = CardDefaults.cardColors(containerColor = cardBackground), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("${emp?.displayName ?: start.employeeId}（${start.employeeId}）", color = Ink, fontWeight = FontWeight.Bold)
                                    Text("${start.day} · ${runCatching { ZonedDateTime.parse(start.at).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault(start.at.takeLast(8).take(5))} - ${runCatching { ZonedDateTime.parse(end.at).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault(end.at.takeLast(8).take(5))}", color = Blue, fontWeight = FontWeight.Medium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = {
                                            editingMakeUpGroup = groupId
                                            editingMakeUpDate = start.day
                                            editingMakeUpStart = runCatching { ZonedDateTime.parse(start.at).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("09:00")
                                            editingMakeUpEnd = runCatching { ZonedDateTime.parse(end.at).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("18:00")
                                            makeUpEmployeeId = start.employeeId
                                        }) { Icon(Icons.Default.Edit, null); Text("修改") }
                                        TextButton(onClick = { pendingDeleteMakeUp = start to end }) { Icon(Icons.Default.Delete, null); Text("删除") }
                                    }
                                }
                            }
                        }
                    }
                    if (recordGroups.isEmpty() && !summaryLoading) Text("当前条件没有补班记录。", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
                }
                if (pendingDeleteMakeUp != null) {
                    val (deleteStart, deleteEnd) = pendingDeleteMakeUp!!
                    AlertDialog(
                        onDismissRequest = { pendingDeleteMakeUp = null },
                        title = { Text("确认删除补班记录") },
                        text = { Text("确定删除 ${deleteStart.day} 的补班时间段吗？删除后该记录将被隐藏，不会再参与工时统计。") },
                        confirmButton = {
                            Button(enabled = !summaryLoading, onClick = {
                                scope.launch {
                                    summaryLoading = true
                                    try {
                                        val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                                        val month = deleteStart.day.take(7)
                                        val rows = store.readPunchesAsync(month).map {
                                            if (it.id == deleteStart.id || it.id == deleteEnd.id) it.copy(type = "MAKEUP_DELETED") else it
                                        }
                                        store.writePunchesAsync(month, rows)
                                        val verified = store.readPunchesAsync(month)
                                        check(verified.any { it.id == deleteStart.id && it.type == "MAKEUP_DELETED" } &&
                                            verified.any { it.id == deleteEnd.id && it.type == "MAKEUP_DELETED" }) { "软删除回读验证失败" }
                                        makeUpQueryPunches = makeUpQueryPunches.filterNot { it.id == deleteStart.id || it.id == deleteEnd.id }
                                        pendingDeleteMakeUp = null
                                        message = "补班记录已软删除"
                                    } catch (e: Exception) { message = "删除失败：${e.message ?: e.javaClass.simpleName}" }
                                    finally { summaryLoading = false }
                                }
                            }) { Text(if (summaryLoading) "处理中…" else "确认删除") }
                        },
                        dismissButton = { TextButton(onClick = { pendingDeleteMakeUp = null }) { Text("取消") } }
                    )
                }
                if (editingMakeUpGroup != null) {
                    val groupId = editingMakeUpGroup!!
                    val start = makeUpQueryPunches.firstOrNull { it.id == "MAKEUP-$groupId-IN" }
                    val end = makeUpQueryPunches.firstOrNull { it.id == "MAKEUP-$groupId-OUT" }
                    var showEditDatePicker by remember(groupId) { mutableStateOf(false) }
                    var showEditStartPicker by remember(groupId) { mutableStateOf(false) }
                    var showEditEndPicker by remember(groupId) { mutableStateOf(false) }
                    AlertDialog(
                        onDismissRequest = { editingMakeUpGroup = null },
                        title = { Text("修改补班记录") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(summaryEmployees.firstOrNull { it.employeeId == makeUpEmployeeId }?.let { "${it.displayName}（${it.employeeId}）" } ?: makeUpEmployeeId, color = Muted)
                                OutlinedTextField(value = editingMakeUpDate, onValueChange = {}, readOnly = true, label = { Text("补班日期") }, trailingIcon = { IconButton(onClick = { showEditDatePicker = true }) { Icon(Icons.Default.CalendarMonth, null) } }, modifier = Modifier.fillMaxWidth().clickable { showEditDatePicker = true })
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = editingMakeUpStart, onValueChange = {}, readOnly = true, label = { Text("开始时间") }, trailingIcon = { IconButton(onClick = { showEditStartPicker = true }) { Icon(Icons.Default.AccessTime, null) } }, modifier = Modifier.weight(1f).clickable { showEditStartPicker = true })
                                    OutlinedTextField(value = editingMakeUpEnd, onValueChange = {}, readOnly = true, label = { Text("结束时间") }, trailingIcon = { IconButton(onClick = { showEditEndPicker = true }) { Icon(Icons.Default.AccessTime, null) } }, modifier = Modifier.weight(1f).clickable { showEditEndPicker = true })
                                }
                                if (showEditDatePicker) {
                                    val state = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(editingMakeUpDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                                    DatePickerDialog(onDismissRequest = { showEditDatePicker = false }, confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { editingMakeUpDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }; showEditDatePicker = false }) { Text("确定") } }, dismissButton = { TextButton(onClick = { showEditDatePicker = false }) { Text("取消") } }) { DatePicker(state = state) }
                                }
                                if (showEditStartPicker || showEditEndPicker) {
                                    val isStart = showEditStartPicker
                                    val initial = LocalTime.parse(if (isStart) editingMakeUpStart else editingMakeUpEnd)
                                    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
                                    AlertDialog(onDismissRequest = { showEditStartPicker = false; showEditEndPicker = false }, title = { Text(if (isStart) "选择开始时间" else "选择结束时间") }, text = { TimePicker(state = state) }, confirmButton = { TextButton(onClick = { val value = "%02d:%02d".format(state.hour, state.minute); if (isStart) editingMakeUpStart = value else editingMakeUpEnd = value; showEditStartPicker = false; showEditEndPicker = false }) { Text("确定") } }, dismissButton = { TextButton(onClick = { showEditStartPicker = false; showEditEndPicker = false }) { Text("取消") } })
                                }
                            }
                        },
                        confirmButton = {
                            Button(enabled = start != null && end != null && runCatching { LocalDate.parse(editingMakeUpDate); LocalTime.parse(editingMakeUpStart).isBefore(LocalTime.parse(editingMakeUpEnd)) }.getOrDefault(false) && !summaryLoading, onClick = {
                                scope.launch {
                                    summaryLoading = true
                                    try {
                                        val oldStart = start!!
                                        val oldEnd = end!!
                                        val date = LocalDate.parse(editingMakeUpDate)
                                        val newStart = oldStart.copy(at = date.atTime(LocalTime.parse(editingMakeUpStart)).atZone(ZoneId.systemDefault()).toString(), day = date.toString())
                                        val newEnd = oldEnd.copy(at = date.atTime(LocalTime.parse(editingMakeUpEnd)).atZone(ZoneId.systemDefault()).toString(), day = date.toString())
                                        val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                                        val oldMonth = oldStart.day.take(7)
                                        val newMonth = date.toString().take(7)
                                        if (oldMonth == newMonth) {
                                            val rows = store.readPunchesAsync(oldMonth).map { when (it.id) { oldStart.id -> newStart; oldEnd.id -> newEnd; else -> it } }
                                            store.writePunchesAsync(oldMonth, rows)
                                        } else {
                                            store.writePunchesAsync(oldMonth, store.readPunchesAsync(oldMonth).filterNot { it.id == oldStart.id || it.id == oldEnd.id })
                                            store.writePunchesAsync(newMonth, store.readPunchesAsync(newMonth) + newStart + newEnd)
                                        }
                                        makeUpQueryPunches = makeUpQueryPunches.filterNot { it.id == oldStart.id || it.id == oldEnd.id } + newStart + newEnd
                                        editingMakeUpGroup = null
                                        message = "补班记录已修改"
                                    } catch (e: Exception) { message = "修改失败：${e.message ?: e.javaClass.simpleName}" }
                                    finally { summaryLoading = false }
                                }
                            }) { Text(if (summaryLoading) "保存中…" else "保存修改") }
                        },
                        dismissButton = { TextButton(onClick = { editingMakeUpGroup = null }) { Text("取消") } }
                    )
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
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        showMakeUpRecords = true
                        editingMakeUpGroup = null
                        makeUpQueryEmployee = ""
                        makeUpQueryStart = LocalDate.now().withDayOfMonth(1).toString()
                        makeUpQueryEnd = LocalDate.now().toString()
                        scope.launch {
                            summaryLoading = true
                            try {
                                val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                                summaryEmployees = store.readEmployeesAsync().filter { it.enabled }
                                makeUpQueryPunches = store.readPunchesAsync(monthKey).filter { it.id.startsWith("MAKEUP-") }
                            } catch (e: Exception) { summaryError = "读取补班记录失败：${e.message ?: e.javaClass.simpleName}" }
                            finally { summaryLoading = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.EventNote, null); Spacer(Modifier.width(8.dp)); Text("查看补班记录")
                    }
                    Spacer(Modifier.height(8.dp))
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
                    Card(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = cardBackground)) {
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
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable { selectedSummaryEmployee = emp },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = cardBackground)
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(emp.displayName, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                Text("${emp.employeeId} · ${if (emp.role == "admin") "管理员" else "员工"}", color = Muted, fontSize = 12.sp)
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(String.format("%.2f 小时", calculateHours(empPunches)), color = Blue, fontWeight = FontWeight.Bold)
                                                Text("查看报表 ›", color = Blue, fontSize = 11.sp)
                                            }
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
                            if (drag > 70f && loggedIn) {
                                showProfile = true
                                profileName = employeeName
                                scope.launch {
                                    profileLoading = true
                                    try {
                                        val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                                        val data = store.readProfileAsync(employeeId)
                                        profileName = data?.name?.ifBlank { employeeName } ?: employeeName
                                        profileGender = data?.gender ?: "未设置"
                                        profileAge = data?.age ?: ""
                                        profileBackground = data?.background ?: prefs.getInt("profile_background", 0)
                                        val bytes = store.readBinaryAsync(data?.avatarKey ?: "profiles/avatars/" + employeeId + ".jpg")
                                        if (bytes != null) profileAvatar = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    } catch (e: Exception) { profileMessage = "读取个人资料失败：" + (e.message ?: e.javaClass.simpleName) }
                                    finally { profileLoading = false }
                                }
                            } else if (role == "admin" && drag < -70f) {
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
                        if (loggedIn) Text("向右滑动，进入个人空间 ✨", color = Muted, fontSize = 11.sp)
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            if (role == "admin") {
                                TextButton(onClick = {
                                    showMakeUpDialog = true
                                    makeUpDate = LocalDate.now().toString()
                                    makeUpSegments = listOf(MakeUpSegment())
                                    scope.launch {
                                        try {
                                            makeUpEmployees = R2Store(R2Config(account, bucket, access, secret, ssid)).readEmployeesAsync().filter { it.enabled }
                                            if (makeUpEmployeeId.isBlank() && makeUpEmployees.isNotEmpty()) makeUpEmployeeId = makeUpEmployees.first().employeeId
                                        } catch (e: Exception) { message = "读取员工列表失败：" + (e.message ?: e.javaClass.simpleName); showMakeUpDialog = false }
                                    }
                                }, enabled = !busy) {
                                    Icon(Icons.Default.EventAvailable, null); Spacer(Modifier.width(4.dp)); Text("补班")
                                }
                            } else Spacer(Modifier.width(1.dp))
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
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = cardBackground)) {
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

        if (showMakeUpDialog && role == "admin") {
            AlertDialog(
                onDismissRequest = { if (!makeUpSaving) showMakeUpDialog = false },
                title = { Text("管理员补班") },
                text = {
                    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("选择员工、日期和一个或多个补班时间段。每个时间段会生成一条上班和一条下班记录。", color = Muted, fontSize = 12.sp)
                        var employeeMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedTextField(
                                value = makeUpEmployees.firstOrNull { it.employeeId == makeUpEmployeeId }?.let { "${it.displayName}（${it.employeeId}）" } ?: "",
                                onValueChange = {}, readOnly = true, label = { Text("选择员工") }, modifier = Modifier.fillMaxWidth(),
                                trailingIcon = { IconButton(onClick = { employeeMenuExpanded = true }) { Icon(Icons.Default.ArrowDropDown, null) } }
                            )
                            DropdownMenu(expanded = employeeMenuExpanded, onDismissRequest = { employeeMenuExpanded = false }) {
                                makeUpEmployees.forEach { emp ->
                                    DropdownMenuItem(text = { Text("${emp.displayName}（${emp.employeeId}）") }, onClick = { makeUpEmployeeId = emp.employeeId; employeeMenuExpanded = false })
                                }
                            }
                        }
                        var showMakeUpDatePicker by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = makeUpDate,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("补班日期") },
                            trailingIcon = { IconButton(onClick = { showMakeUpDatePicker = true }) { Icon(Icons.Default.CalendarMonth, "选择日期") } },
                            modifier = Modifier.fillMaxWidth().clickable { showMakeUpDatePicker = true }
                        )
                        if (showMakeUpDatePicker) {
                            val initialDate = runCatching { LocalDate.parse(makeUpDate) }.getOrDefault(LocalDate.now())
                            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                            DatePickerDialog(
                                onDismissRequest = { showMakeUpDatePicker = false },
                                confirmButton = {
                                    TextButton(onClick = {
                                        datePickerState.selectedDateMillis?.let { millis ->
                                            makeUpDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                                        }
                                        showMakeUpDatePicker = false
                                    }) { Text("确定") }
                                },
                                dismissButton = { TextButton(onClick = { showMakeUpDatePicker = false }) { Text("取消") } }
                            ) { DatePicker(state = datePickerState) }
                        }
                        makeUpSegments.forEachIndexed { index, segment ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("时间段 ${index + 1}", fontWeight = FontWeight.Bold, color = Ink)
                                        if (makeUpSegments.size > 1) TextButton(onClick = { makeUpSegments = makeUpSegments.filterIndexed { i, _ -> i != index } }) { Text("删除") }
                                    }
                                    var showStartTimePicker by remember(index) { mutableStateOf(false) }
                                    var showEndTimePicker by remember(index) { mutableStateOf(false) }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = segment.start, onValueChange = {}, readOnly = true,
                                            label = { Text("开始时间") },
                                            trailingIcon = { IconButton(onClick = { showStartTimePicker = true }) { Icon(Icons.Default.AccessTime, "选择开始时间") } },
                                            modifier = Modifier.weight(1f).clickable { showStartTimePicker = true }
                                        )
                                        OutlinedTextField(
                                            value = segment.end, onValueChange = {}, readOnly = true,
                                            label = { Text("结束时间") },
                                            trailingIcon = { IconButton(onClick = { showEndTimePicker = true }) { Icon(Icons.Default.AccessTime, "选择结束时间") } },
                                            modifier = Modifier.weight(1f).clickable { showEndTimePicker = true }
                                        )
                                    }
                                    if (showStartTimePicker || showEndTimePicker) {
                                        val editingStart = showStartTimePicker
                                        val initialTime = runCatching { LocalTime.parse(if (editingStart) segment.start else segment.end) }.getOrDefault(LocalTime.of(9, 0))
                                        val timePickerState = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute, is24Hour = true)
                                        AlertDialog(
                                            onDismissRequest = { showStartTimePicker = false; showEndTimePicker = false },
                                            title = { Text(if (editingStart) "选择开始时间" else "选择结束时间") },
                                            text = { TimePicker(state = timePickerState) },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    val value = "%02d:%02d".format(timePickerState.hour, timePickerState.minute)
                                                    makeUpSegments = makeUpSegments.mapIndexed { i, s ->
                                                        if (i == index) if (editingStart) s.copy(start = value) else s.copy(end = value) else s
                                                    }
                                                    showStartTimePicker = false
                                                    showEndTimePicker = false
                                                }) { Text("确定") }
                                            },
                                            dismissButton = { TextButton(onClick = { showStartTimePicker = false; showEndTimePicker = false }) { Text("取消") } }
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = { makeUpSegments = makeUpSegments + MakeUpSegment() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("添加时间段")
                        }
                    }
                },
                confirmButton = {
                    Button(enabled = !makeUpSaving && makeUpEmployeeId.isNotBlank() && runCatching { LocalDate.parse(makeUpDate) }.isSuccess && makeUpSegments.isNotEmpty() && makeUpSegments.all { s -> runCatching { LocalTime.parse(s.start) }.isSuccess && runCatching { LocalTime.parse(s.end) }.isSuccess && runCatching { LocalTime.parse(s.end).isAfter(LocalTime.parse(s.start)) }.getOrDefault(false) }, onClick = {
                        scope.launch {
                            makeUpSaving = true
                            try {
                                val date = LocalDate.parse(makeUpDate)
                                val store = R2Store(R2Config(account, bucket, access, secret, ssid))
                                val additions = makeUpSegments.flatMap { segment ->
                                    val groupId = UUID.randomUUID().toString()
                                    val startAt = date.atTime(LocalTime.parse(segment.start)).atZone(ZoneId.systemDefault())
                                    val endAt = date.atTime(LocalTime.parse(segment.end)).atZone(ZoneId.systemDefault())
                                    listOf(
                                        Punch(id = "MAKEUP-$groupId-IN", employeeId = makeUpEmployeeId, at = startAt.toString(), type = "IN", day = date.toString()),
                                        Punch(id = "MAKEUP-$groupId-OUT", employeeId = makeUpEmployeeId, at = endAt.toString(), type = "OUT", day = date.toString())
                                    )
                                }
                                val month = date.format(DateTimeFormatter.ofPattern("yyyy-MM"))
                                val existing = store.readPunchesAsync(month)
                                store.writePunchesAsync(month, existing + additions)
                                val confirmed = store.readPunchesAsync(month)
                                check(additions.all { added -> confirmed.any { it.id == added.id } }) { "补班记录写入后回读验证失败" }
                                if (month == LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")) && makeUpEmployeeId == employeeId) punchList = confirmed.filter { it.employeeId == employeeId }
                                showMakeUpDialog = false
                                message = "补班成功：${makeUpEmployees.firstOrNull { it.employeeId == makeUpEmployeeId }?.displayName ?: makeUpEmployeeId}，${date}，已添加 ${makeUpSegments.size} 个时间段。"
                            } catch (e: Exception) {
                                message = "补班失败：" + (e.message ?: e.javaClass.simpleName)
                            } finally { makeUpSaving = false }
                        }
                    }) { Text(if (makeUpSaving) "保存中…" else "确认补班") }
                },
                dismissButton = { TextButton(enabled = !makeUpSaving, onClick = { showMakeUpDialog = false }) { Text("取消") } }
            )
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
            AlertDialog(onDismissRequest = { if (configured) { showSettings = false; configUnlocked = false; showAdminConfigAuth = false; adminConfigPassword = "" } }, title = { Text(if (role == "admin" && loggedIn) "管理设置" else "设置") },
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
                                if (role != "admin" || !loggedIn) {
                                    if (!qrConfigCompleted) {
                                        Text("选择 R2 配置方式", color = Ink, fontWeight = FontWeight.Bold)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(selected = configMethod == "扫码配置", onClick = { configMethod = "扫码配置" }, label = { Text("扫码配置") })
                                            FilterChip(selected = configMethod == "手动配置", onClick = { configMethod = "手动配置" }, label = { Text("手动配置") })
                                        }
                                        if (configMethod == "扫码配置") {
                                            Text("扫描管理员提供的 R2 配置二维码，内容需包含 account、access、secret，可选 ssid。", color = Muted, fontSize = 12.sp)
                                            Button(onClick = { scanR2Qr() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("开始扫码") }
                                            if (qrScanMessage.isNotBlank()) Text(qrScanMessage, color = Blue, fontSize = 12.sp)
                                        }
                                    } else {
                                        Text("扫码配置已完成，当前仅显示手动配置。", color = Muted, fontSize = 12.sp)
                                    }
                                }
                                if (restrictedUserSettings) {
                                    Text("存储桶：workcloudr2（固定）", color = Ink, fontWeight = FontWeight.Medium)
                                    Text("R2 配置由管理员统一管理。", color = Muted, fontSize = 12.sp)
                                } else if ((role != "admin" || !loggedIn) && configMethod != "手动配置") {
                                    Text("请选择扫码配置或手动配置。", color = Muted, fontSize = 12.sp)
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
