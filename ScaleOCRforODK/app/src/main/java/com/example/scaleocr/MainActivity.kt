package com.example.scaleocr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var detected: TextView
    private lateinit var confirm: Button
    private lateinit var cameraExecutor: ExecutorService

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val processing = AtomicBoolean(false)

    @Volatile
    private var candidate: String? = null
    private var pendingCandidate: String? = null
    private var stableCount = 0
    private val requiredStableReads = 2

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "يلزم السماح باستخدام الكاميرا."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildScreen()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 28, 20, 20)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val heading = TextView(this).apply { text = "قراءة وزن الميزان"; textSize = 23f; gravity = Gravity.CENTER }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        status = TextView(this).apply { text = "وجّه شاشة الميزان بوضوح داخل الإطار"; textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 14, 0, 8) }
        detected = TextView(this).apply { text = "---"; textSize = 34f; gravity = Gravity.CENTER; setPadding(0, 8, 0, 14) }

        confirm = Button(this).apply {
            text = "تأكيد وإرسال إلى ODK"
            isEnabled = false
            setOnClickListener { returnValue() }
        }

        val cancel = Button(this).apply {
            text = "إلغاء"
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }

        root.addView(heading)
        root.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status)
        root.addView(detected)
        root.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(960, 540))
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    if (!processing.compareAndSet(false, true)) {
                        proxy.close()
                        return@setAnalyzer
                    }

                    var bitmap: Bitmap? = null
                    try {
                        bitmap = imageProxyToBitmap(proxy)
                        if (bitmap == null) {
                            processing.set(false)
                            proxy.close()
                            return@setAnalyzer
                        }

                        val rotatedBitmap = rotateBitmap(bitmap, 90f)
                        val processedBitmap = preprocessBitmapForScale(rotatedBitmap)
                        val input = InputImage.fromBitmap(processedBitmap, 0)

                        recognizer.process(input)
                            .addOnSuccessListener { result ->
                                val match = extractScaleWeight(result)
                                runOnUiThread { updateReading(match) }
                            }
                            .addOnFailureListener {
                                runOnUiThread { updateReading(null) }
                            }
                            .addOnCompleteListener {
                                if (!bitmap.isRecycled) bitmap.recycle()
                                if (!rotatedBitmap.isRecycled) rotatedBitmap.recycle()
                                if (!processedBitmap.isRecycled) processedBitmap.recycle()
                                processing.set(false)
                                proxy.close()
                            }
                    } catch (e: Exception) {
                        bitmap?.let { if (!it.isRecycled) it.recycle() }
                        processing.set(false)
                        proxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                status.text = "تعذر تشغيل الكاميرا: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val crop = proxy.cropRect
        val width = crop.width()
        val height = crop.height()
        if (width < 20 || height < 20) return null

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = (y + crop.top) * plane.rowStride + (x + crop.left) * plane.pixelStride
                val value = buffer.get(index).toInt() and 0xFF
                pixels[y * width + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * دالة معالجة شاشات الميزان المضيئة عبر عكس الألوان (Color Inversion)
     * لتحويل الإضاءة الخضراء الخلفية إلى داكنة وإبراز الأرقام بوضوح لمحرك OCR.
     */
    private fun preprocessBitmapForScale(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val processed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(processed)
        val paint = Paint()

        val colorMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                -1.0f,  0f,    0f,    0f, 255f, // عكس اللون الأحمر
                 0f,   -1.0f,  0f,    0f, 255f, // عكس اللون الأخضر (الخلفية المضيئة تصبح سوداء)
                 0f,    0f,   -1.0f,  0f, 255f, // عكس اللون الأزرق
                 0f,    0f,    0f,    1f, 0f    // الحفاظ على الشفافية
            ))
        }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return processed
    }

    private fun extractScaleWeight(result: Text): String? {
        val sb = StringBuilder()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val cleaned = line.text.map { char ->
                    when (char) {
                        in '٠'..'٩' -> '0' + (char - '٠')
                        in '۰'..'۹' -> '0' + (char - '۰')
                        '٫', '،', ',' -> '.'
                        'O', 'o', 'Q', 'D' -> '0'
                        'I', 'l', 'i', '|', '!', 'T' -> '1'
                        'Z', 'z' -> '2'
                        'E' -> '3'
                        'A' -> '4'
                        'S', 's' -> '5'
                        'G', 'b' -> '6'
                        'B' -> '8'
                        'g', 'q' -> '9'
                        else -> char
                    }
                }.joinToString("").replace(Regex("[^0-9.]"), "")

                if (cleaned.isNotEmpty()) {
                    sb.append(cleaned)
                }
            }
        }

        val text = sb.toString()
        if (text.length < 2) return null

        return if (text.toDoubleOrNull() != null && text.length >= 2) text else null
    }

    private fun updateReading(match: String?) {
        if (match != null) {
            if (match == pendingCandidate) {
                stableCount++
            } else {
                pendingCandidate = match
                stableCount = 1
            }
            detected.text = match

            if (stableCount >= requiredStableReads) {
                candidate = match
                status.text = "تم تثبيت القراءة. اضغط تأكيد."
                confirm.isEnabled = true
            } else {
                status.text = "جارٍ التأكد... $stableCount/$requiredStableReads"
            }
        } else {
            candidate = null
            pendingCandidate = null
            stableCount = 0
            detected.text = "---"
            confirm.isEnabled = false
            status.text = "جارٍ البحث عن شاشة الميزان..."
        }
    }

    private fun returnValue() {
        val value = candidate ?: return
        setResult(RESULT_OK, Intent().putExtra("value", value))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}
