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

class R2Store(private val config: R2Config) {
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
        if (e.message?.contains("NoSuchKey", true) == true || e.message?.contains("404") == true) null else throw e
    }

    private fun writeText(key: String, content: String) {
        client.putObject(
            PutObjectRequest.builder().bucket(config.bucket).key(key).contentType("application/json; charset=utf-8").build(),
            RequestBody.fromBytes(content.toByteArray(Charsets.UTF_8))
        )
    }

    fun readEmployees(): List<Employee> {
        val raw = readText("employees.json") ?: throw IllegalStateException("R2 中未找到 employees.json")
        val envelope: EmployeeEnvelope = gson.fromJson(raw, EmployeeEnvelope::class.java)
        return envelope.employees ?: emptyList()
    }

    fun readPunches(month: String): List<Punch> {
        val raw = readText("attendance/$month.json") ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Punch>>() {}.type
            gson.fromJson<List<Punch>>(raw, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun writePunches(month: String, punches: List<Punch>) {
        writeText("attendance/$month.json", gson.toJson(punches))
    }

    companion object {
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

private data class EmployeeEnvelope(val employees: List<Employee>?)
