package com.example.workclockr2

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.core.sync.RequestBody
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

class R2Store(private val config: R2Config) {
    // All network access is exposed through suspend functions that always switch to Dispatchers.IO.
    // UI code should use these functions instead of calling the synchronous S3 helpers directly.
    suspend fun testConnectionAsync(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { testConnection() }
    suspend fun readEmployeesAsync(): List<Employee> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readEmployees() }
    suspend fun readPunchesAsync(month: String): List<Punch> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readPunches(month) }
    suspend fun writePunchesAsync(month: String, punches: List<Punch>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writePunches(month, punches) }
    suspend fun writeEmployeesAsync(employees: List<Employee>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeEmployees(employees) }
    suspend fun readAllowedSsidAsync(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readAllowedSsid() }
    suspend fun writeAllowedSsidAsync(ssid: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeAllowedSsid(ssid) }
    suspend fun writeBinaryAsync(key: String, bytes: ByteArray, contentType: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { client.putObject(PutObjectRequest.builder().bucket(config.bucket).key(key).contentType(contentType).build(), RequestBody.fromBytes(bytes)) }
    suspend fun readBinaryAsync(key: String): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { client.getObjectAsBytes(GetObjectRequest.builder().bucket(config.bucket).key(key).build()).asByteArray() } catch (e: Exception) { val s3 = e as? software.amazon.awssdk.services.s3.model.S3Exception; if (s3?.statusCode() == 404 || e.message?.contains("NoSuchKey", true) == true || e.message?.contains("404", true) == true) null else throw e } }
    suspend fun writeProfileAsync(profile: ProfileData) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeText("profiles/" + profile.employeeId + ".json", gson.toJson(profile)); check(readProfile(profile.employeeId) == profile) { "个人资料写入后回读验证失败" } }
    suspend fun readProfileAsync(employeeId: String): ProfileData? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readText("profiles/" + employeeId + ".json")?.let { gson.fromJson(it, ProfileData::class.java) } }
    suspend fun updatePunchesAndVerifyAsync(month: String, punch: Punch): List<Punch> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val existing = readPunches(month)
        val merged = existing.filterNot { it.id == punch.id } + punch
        writePunches(month, merged)
        val confirmed = readPunches(month)
        check(confirmed.any { it.id == punch.id }) {
            "写入请求已发送，但从 R2 重新读取后未找到本次记录"
        }
        confirmed
    }
    private val gson = Gson()
    private val client: S3Client by lazy {
        S3Client.builder()
            .endpointOverride(URI.create("https://${config.accountId}.r2.cloudflarestorage.com"))
            .region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)))
            .forcePathStyle(true)
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build()
    }

    private fun readText(key: String): String? = try {
        client.getObjectAsBytes(GetObjectRequest.builder().bucket(config.bucket).key(key).build()).asUtf8String()
    } catch (e: Exception) {
        val s3 = e as? software.amazon.awssdk.services.s3.model.S3Exception
        // S3-compatible services may report a missing object through statusCode rather than message text.
        if (s3?.statusCode() == 404 ||
            e.message?.contains("NoSuchKey", true) == true ||
            e.message?.contains("NotFound", true) == true ||
            e.message?.contains("404", true) == true
        ) null else throw e
    }

    private fun readProfile(employeeId: String): ProfileData? =
        readText("profiles/" + employeeId + ".json")?.let { gson.fromJson(it, ProfileData::class.java) }

    private fun writeText(key: String, content: String) {
        client.putObject(
            PutObjectRequest.builder().bucket(config.bucket).key(key).contentType("application/json; charset=utf-8").build(),
            RequestBody.fromBytes(content.toByteArray(Charsets.UTF_8))
        )
    }

    fun testConnection(): String {
        return try {
            readEmployees()
            "连接成功：已读取 employees.json，桶名、凭据和读权限均正常。"
        } catch (e: Exception) {
            val service = e as? software.amazon.awssdk.services.s3.model.S3Exception
            when (service?.statusCode()) {
                401, 403 -> "鉴权失败（HTTP ${service.statusCode()}）：请核对 Access Key/Secret，以及该凭据对 workcloudr2 桶的读权限。"
                404 -> "对象或存储桶不存在（HTTP 404）：请确认桶名为 workcloudr2，且已上传 employees.json。"
                else -> {
                    val msg = e.message.orEmpty()
                    when {
                        e is java.net.UnknownHostException || msg.contains("UnknownHost", true) -> "网络/DNS 错误：无法解析 R2 地址，请检查 Account ID 和网络。"
                        msg.contains("timed out", true) || msg.contains("timeout", true) -> "连接超时：请检查网络是否能访问 Cloudflare R2。"
                        msg.contains("employees.json", true) -> "R2 已响应，但未能读取 employees.json：请检查对象路径及读权限。"
                        else -> "连接失败：${service?.statusCode()?.let { "HTTP $it；" } ?: ""}${msg.take(220).ifBlank { e.javaClass.simpleName }}"
                    }
                }
            }
        }
    }

    // Shared attendance Wi-Fi configuration lives in R2 so every device uses the administrator's setting.
    fun readAllowedSsid(): String {
        val raw = readText("app-config.json") ?: return ""
        return runCatching { gson.fromJson(raw, AttendanceAppConfig::class.java)?.allowedSsid.orEmpty() }
            .getOrElse { throw IllegalStateException("读取 app-config.json 失败：" + (it.message ?: "JSON 格式错误"), it) }
    }

    fun writeAllowedSsid(ssid: String) {
        writeText("app-config.json", gson.toJson(AttendanceAppConfig(ssid.trim())))
        check(readAllowedSsid() == ssid.trim()) { "指定 Wi-Fi 配置已写入，但回读验证不一致" }
    }

    fun writeEmployees(employees: List<Employee>) {
        // Keep the administrator/root accounts in employees and store child accounts in ListUsers.
        val rootUsers = employees.filter { it.role.equals("admin", ignoreCase = true) }
        val childUsers = employees.filterNot { it.role.equals("admin", ignoreCase = true) }
        writeText("employees.json", gson.toJson(mapOf("employees" to rootUsers, "ListUsers" to childUsers)))
    }

    fun readEmployees(): List<Employee> {
        val raw = readText("employees.json") ?: throw IllegalStateException("R2 中未找到 employees.json")
        val envelope: EmployeeEnvelope = gson.fromJson(raw, EmployeeEnvelope::class.java)
        // Backward compatible: older files may have all accounts under employees only.
        return (envelope.employees.orEmpty() + envelope.ListUsers.orEmpty())
            .distinctBy { it.employeeId }
    }

    fun readPunches(month: String): List<Punch> {
        val yearMonth = try { java.time.YearMonth.parse(month) } catch (e: Exception) {
            throw IllegalArgumentException("月份格式应为 yyyy-MM：$month", e)
        }
        // Read daily files in order; keep the result typed and compatible with this store.
        val daily = mutableListOf<Punch>()
        for (day in 1..yearMonth.lengthOfMonth()) {
            val date = yearMonth.atDay(day).toString()
            val key = "attendance/$month/$date.json"
            readText(key)?.let { daily.addAll(parsePunches(key, it)) }
        }
        if (daily.isNotEmpty()) return daily.distinctBy { it.id }

        // Migrate older monthly files on first read; keep the legacy file as a backup.
        val legacyKey = "attendance/$month.json"
        val legacyRaw = readText(legacyKey)
        if (legacyRaw != null) {
            val legacy = parsePunches(legacyKey, legacyRaw)
            legacy.groupBy { it.day }.forEach { (date, rows) ->
                writeText("attendance/$month/$date.json", gson.toJson(rows))
            }
            return legacy.distinctBy { it.id }
        }
        return emptyList()
    }

    private fun parsePunches(key: String, raw: String): List<Punch> = try {
        val type = object : TypeToken<List<Punch>>() {}.type
        gson.fromJson<List<Punch>>(raw, type)
            ?: throw IllegalStateException("$key 内容为空或不是有效的考勤 JSON 数组")
    } catch (e: Exception) {
        throw IllegalStateException("读取 $key 失败，文件内容不是有效 JSON；为避免覆盖原数据，已停止写入。原因：${e.message}", e)
    }

    fun writePunches(month: String, punches: List<Punch>) {
        val yearMonth = try { java.time.YearMonth.parse(month) } catch (e: Exception) {
            throw IllegalArgumentException("月份格式应为 yyyy-MM：$month", e)
        }
        val grouped = punches.filter { it.day.startsWith("$month-") }.groupBy { it.day }
        // Clear previously existing daily files too, so edits/deletions do not leave stale records.
        for (day in 1..yearMonth.lengthOfMonth()) {
            val date = yearMonth.atDay(day).toString()
            val key = "attendance/$month/$date.json"
            if (readText(key) != null || grouped.containsKey(date)) {
                writeText(key, gson.toJson(grouped[date].orEmpty()))
            }
        }
    }

    companion object {
        fun createEmployee(employeeId: String, displayName: String, role: String, password: String, mustChangePassword: Boolean = true, enabled: Boolean = true): Employee {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
            return Employee(employeeId, displayName, role, Base64.encodeToString(salt, Base64.NO_WRAP), Base64.encodeToString(hash, Base64.NO_WRAP), mustChangePassword, enabled)
        }

        fun verifyPassword(employee: Employee, password: String): Boolean {
            val salt = runCatching { Base64.decode(employee.passwordSalt, Base64.DEFAULT) }.getOrNull() ?: return false
            val expected = runCatching { Base64.decode(employee.passwordHash, Base64.DEFAULT) }.getOrNull() ?: return false
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            val actual = digest.digest(password.toByteArray(Charsets.UTF_8))
            return MessageDigest.isEqual(expected, actual)
        }
    }
}

private data class EmployeeEnvelope(val employees: List<Employee>?, val ListUsers: List<Employee>?)

private data class AttendanceAppConfig(val allowedSsid: String)
