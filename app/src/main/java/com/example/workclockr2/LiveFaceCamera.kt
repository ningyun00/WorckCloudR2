package com.example.workclockr2

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Live front-camera preview. When exactly one sufficiently large face is detected,
 * the first eligible frame is passed to the caller and analysis stops.
 */
@Composable
fun LiveFaceCamera(
    context: Context,
    enabled: Boolean,
    onFaceFrame: (Bitmap) -> Unit,
    onStatus: (String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val detector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .build()
        )
    }

    DisposableEffect(enabled, lifecycleOwner) {
        val sessionActive = AtomicBoolean(true)
        val cameraReadyAt = AtomicLong(Long.MAX_VALUE)
        val lastAnalysisAt = AtomicLong(0L)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        if (enabled) {
            delivered.set(false)
            val mainExecutor = ContextCompat.getMainExecutor(context)
            val listener = Runnable {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        val now = System.currentTimeMillis()
                        // Let the preview settle before starting face detection, then sample at 4 fps.
                        if (now < cameraReadyAt.get() || now - lastAnalysisAt.get() < 250L) {
                            proxy.close()
                        } else if (!sessionActive.get() || delivered.get()) {
                            proxy.close()
                        } else {
                            lastAnalysisAt.set(now)
                            val mediaImage = proxy.image
                            if (mediaImage == null) {
                                proxy.close()
                            } else {
                                val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                                detector.process(input)
                                    .addOnSuccessListener(executor) { faces ->
                                        if (sessionActive.get() && !delivered.get()) {
                                            when {
                                                faces.isEmpty() -> onStatus("请将脸部对准取景框，正在实时检测…")
                                                faces.size > 1 -> onStatus("检测到多张人脸，请确保画面中只有本人")
                                                faces[0].boundingBox.width() < 80 || faces[0].boundingBox.height() < 80 ->
                                                    onStatus("请靠近摄像头一些")
                                                else -> {
                                                    if (delivered.compareAndSet(false, true)) {
                                                        try {
                                                            val bitmap = proxy.toBitmap()
                                                            val rotated = if (proxy.imageInfo.rotationDegrees != 0) {
                                                                val matrix = android.graphics.Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
                                                                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                                                                    if (it !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                                                                }
                                                            } else bitmap
                                                            onStatus("已检测到人脸，正在自动验证…")
                                                            onFaceFrame(rotated)
                                                        } catch (e: Exception) {
                                                            delivered.set(false)
                                                            onStatus("读取摄像头画面失败，请调整后重试")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .addOnFailureListener(executor) {
                                        if (sessionActive.get()) onStatus("人脸检测失败，请重新对准摄像头")
                                    }
                                    .addOnCompleteListener(executor) { proxy.close() }
                            }
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                    cameraReadyAt.set(System.currentTimeMillis() + 1200L)
                    if (sessionActive.get()) onStatus("请正对前置摄像头，系统会自动识别，无需拍照")
                } catch (e: Exception) {
                    if (sessionActive.get()) onStatus("无法启动前置摄像头：" + (e.message ?: e.javaClass.simpleName))
                }
            }
            providerFuture.addListener(listener, mainExecutor)
        } else {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
        onDispose {
            // Invalidate callbacks first. Do not close ML Kit or its executor here:
            // pending detector tasks may still be completing when the dialog is dismissed.
            sessionActive.set(false)
            runCatching {
                if (providerFuture.isDone) providerFuture.get().unbindAll()
            }
        }
    }

    DisposableEffect(detector, executor, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                runCatching { detector.close() }
                executor.shutdown()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
}
