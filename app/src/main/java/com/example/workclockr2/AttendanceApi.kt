package com.example.workclockr2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class AttendanceSession(
    val token: String,
    val employeeId: String,
    val displayName: String,
    val role: String,
    val mustChangePassword: Boolean
)

data class WorkerPunchResult(val record: Punch)

class AttendanceApi {
    private val baseUrl = "https://hlcs.sixiaojie.dpdns.org"

    suspend fun login(employeeId: String, password: String): AttendanceSession = withContext(Dispatchers.IO) {
        val result = request("POST", "/api/session", body = JSONObject()
            .put("employeeId", employeeId)
            .put("password", password))
        val employee = result.getJSONObject("employee")
        AttendanceSession(
            token = result.getString("token"),
            employeeId = employee.getString("employeeId"),
            displayName = employee.optString("displayName", ""),
            role = employee.optString("role", ""),
            mustChangePassword = employee.optBoolean("mustChangePassword", false)
        )
    }

    suspend fun allowedSsid(token: String): String = withContext(Dispatchers.IO) {
        request("GET", "/api/config", token = token).optString("allowedSsid", "")
    }

    suspend fun punchesForDay(token: String, day: String): List<Punch> = withContext(Dispatchers.IO) {
        parsePunches(request("GET", "/api/attendance?day=" + encode(day), token = token))
    }

    suspend fun punchesForMonth(token: String, month: String): List<Punch> = withContext(Dispatchers.IO) {
        parsePunches(request("GET", "/api/attendance?month=" + encode(month), token = token))
    }

    suspend fun punch(token: String, id: String, type: String): Punch = withContext(Dispatchers.IO) {
        request("POST", "/api/attendance", token = token, body = JSONObject()
            .put("id", id)
            .put("type", type)).getJSONObject("record").toPunch()
    }

    suspend fun addMakeUpRecords(token: String, records: List<Punch>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject()
                .put("id", record.id)
                .put("employeeId", record.employeeId)
                .put("at", record.at)
                .put("type", record.type)
                .put("day", record.day))
        }
        request("POST", "/api/admin/attendance/makeup", token = token, body = JSONObject().put("records", array))
        Unit
    }

    suspend fun changePassword(token: String, currentPassword: String, newPassword: String) = withContext(Dispatchers.IO) {
        request("POST", "/api/password", token = token, body = JSONObject()
            .put("currentPassword", currentPassword)
            .put("newPassword", newPassword))
        Unit
    }

    private fun request(method: String, path: String, token: String? = null, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                val message = response.optString("error").ifBlank {
                    when (code) {
                        401 -> "登录已失效或账号密码错误，请重新登录"
                        503 -> "考勤服务尚未完成配置，请联系管理员"
                        else -> "考勤服务请求失败（HTTP $code）"
                    }
                }
                throw AttendanceApiException(code, message)
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePunches(value: JSONObject): List<Punch> {
        val array = value.optJSONArray("records") ?: return emptyList()
        return array.toPunchList()
    }


    private fun JSONArray.toPunchList(): List<Punch> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            runCatching { add(item.toPunch()) }
        }
    }

    private fun JSONObject.toPunch() = Punch(
        id = getString("id"),
        employeeId = getString("employeeId"),
        at = getString("at"),
        type = getString("type"),
        day = getString("day")
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

class AttendanceApiException(val statusCode: Int, message: String) : Exception(message)
