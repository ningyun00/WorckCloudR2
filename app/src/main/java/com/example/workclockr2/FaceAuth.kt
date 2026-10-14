package com.example.workclockr2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.sqrt

/**
 * On-device face embedding and device-bound encryption.
 * Only the encrypted embedding is uploaded; captured images are never persisted.
 */
class FaceAuth(private val context: Context) {
    data class Result(val embedding: FloatArray, val faceCount: Int)

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
    }

    fun extract(bitmap: Bitmap): FloatArray {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = Tasks.await(detector.process(image))
        require(faces.size == 1) { if (faces.isEmpty()) "没有检测到人脸，请正对摄像头并重新拍摄" else "检测到多张人脸，请确保画面中只有本人" }
        val face = faces.single()
        require(face.boundingBox.width() >= 80 && face.boundingBox.height() >= 80) { "人脸太小，请靠近摄像头" }
        val bounds = face.boundingBox
        val side = maxOf(bounds.width(), bounds.height())
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val left = (cx - side / 2).coerceIn(0, bitmap.width - 1)
        val top = (cy - side / 2).coerceIn(0, bitmap.height - 1)
        val right = (left + side).coerceAtMost(bitmap.width)
        val bottom = (top + side).coerceAtMost(bitmap.height)
        val cropSide = minOf(right - left, bottom - top)
        require(cropSide > 0) { "人脸裁剪失败，请重新拍摄" }
        val crop = Bitmap.createBitmap(bitmap, left, top, cropSide, cropSide)
        val aligned = Bitmap.createScaledBitmap(crop, 112, 112, true)
        val model = modelFile()
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        val session = env.createSession(model.absolutePath, options)
        try {
            val input = FloatArray(3 * 112 * 112)
            var p = 0
            for (channel in 0..2) {
                for (y in 0 until 112) for (x in 0 until 112) {
                    val pixel = aligned.getPixel(x, y)
                    val v = when (channel) { 0 -> android.graphics.Color.red(pixel); 1 -> android.graphics.Color.green(pixel); else -> android.graphics.Color.blue(pixel) }
                    input[p++] = v / 127.5f - 1f
                }
            }
            val inputName = session.inputNames.first()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, 112, 112)).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { output ->
                    val raw = output[0].value
                    val vector = when (raw) {
                        is Array<*> -> (raw[0] as FloatArray).copyOf()
                        is FloatArray -> raw.copyOf()
                        else -> error("人脸模型输出格式不受支持")
                    }
                    require(vector.size == 256) { "人脸模型输出维度异常：${vector.size}（当前模型应输出 256 维）" }
                    val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-12f)
                    return FloatArray(vector.size) { vector[it] / norm }
                }
            }
        } finally {
            session.close()
            options.close()
            if (crop !== bitmap && !crop.isRecycled) crop.recycle()
            if (!aligned.isRecycled) aligned.recycle()
        }
    }

    fun encrypt(vector: FloatArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey())
        val encrypted = cipher.doFinal(floatArrayToBytes(vector))
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
    }

    fun decrypt(payload: ByteArray): FloatArray {
        require(payload.size > 13) { "人脸数据无效" }
        val ivSize = payload[0].toInt() and 0xff
        require(ivSize in 12..16 && payload.size > 1 + ivSize) { "人脸数据格式错误" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, payload.copyOfRange(1, 1 + ivSize)))
        val bytes = cipher.doFinal(payload.copyOfRange(1 + ivSize, payload.size))
        require(bytes.size % 4 == 0) { "人脸数据损坏" }
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }

    fun similarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty()) { "人脸特征长度不一致" }
        return a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()
    }

    private fun deviceKey(): SecretKey {
        val alias = "workclock_face_key_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun modelFile(): File {
        val file = File(context.filesDir, "facex_nano.onnx")
        if (file.exists() && file.length() > 700_000L) return file
        val temp = File(context.cacheDir, "facex_nano.onnx.part")
        val connection = (URL("https://github.com/facex-engine/facex/releases/download/facex-nano-1.0/facex_nano.onnx").openConnection() as HttpURLConnection)
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        try {
            connection.connect()
            require(connection.responseCode in 200..299) { "下载人脸识别模型失败（HTTP ${connection.responseCode}）" }
            connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            require(temp.length() == 802377L) { "人脸模型文件大小校验失败" }
            check(temp.renameTo(file)) { "无法保存人脸模型文件" }
        } finally { connection.disconnect() }
        return file
    }

    private fun floatArrayToBytes(values: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(values.size * 4).order(java.nio.ByteOrder.BIG_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }
}
