package com.example.scaleocr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var detected: TextView
    private lateinit var confirm: Button
    private lateinit var cameraExecutor: ExecutorService

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val processing = AtomicBoolean(false)

    @Volatile
    private var candidate: String? = null

    private var pendingCandidate: String? = null
    private var stableCount = 0
    private val requiredStableReads = 2

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                status.text = "يلزم السماح باستخدام الكاميرا."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        buildScreen()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
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

        val heading = TextView(this).apply {
            text = "قراءة وزن الميزان"
            textSize = 23f
            gravity = Gravity.CENTER
        }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        status = TextView(this).apply {
            text = "وجّه الكاميرا نحو شاشة الميزان"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 8)
        }

        detected = TextView(this).apply {
            text = "---"
            textSize = 34f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 14)
        }

        confirm = Button(this).apply {
            text = "تأكيد وإرسال إلى ODK"
            isEnabled = false
            setOnClickListener { returnValue() }
        }

        val cancel = Button(this).apply {
            text = "إلغاء"
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        root.addView(heading)
        root.addView(
            previewView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(status)
        root.addView(detected)
        root.addView(
            confirm,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            cancel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(1280, 720))
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    if (!processing.compareAndSet(false, true)) {
                        proxy.close()
                        return@setAnalyzer
                    }

                    var bitmap: Bitmap? = null
                    try {
                        bitmap = imageProxyToGrayBitmap(proxy)

                        if (bitmap == null) {
                            processing.set(false)
                            proxy.close()
                            return@setAnalyzer
                        }

                        val bitmapForCleanup = bitmap
                        
                        // نقوم بمعالجة الصورة الأصلية وأيضاً نسخة مقلوبة/مدورة بـ 90 درجة لضمان قراءة الأرقام العمودية في الميزان
                        val inputOriginal = InputImage.fromBitmap(bitmapForCleanup, 0)
                        
                        recognizer.process(inputOriginal)
                            .addOnSuccessListener { result ->
                                var match = extractWeight(result)
                                
                                if (match == null) {
                                    // إذا لم نجدها في الاتجاه العادي، نجرب تدوير الصورة 90 درجة لمعالجة الشاشات العمودية
                                    val rotatedBitmap = rotateBitmap(bitmapForCleanup, 90f)
                                    val inputRotated = InputImage.fromBitmap(rotatedBitmap, 0)
                                    recognizer.process(inputRotated)
                                        .addOnSuccessListener { rotatedResult ->
                                            val rotatedMatch = extractWeight(rotatedResult)
                                            if (!rotatedBitmap.isRecycled) rotatedBitmap.recycle()
                                            runOnUiThread { updateReading(rotatedMatch) }
                                        }
                                        .addOnFailureListener {
                                            if (!rotatedBitmap.isRecycled) rotatedBitmap.recycle()
                                            runOnUiThread { updateReading(null) }
                                        }
                                } else {
                                    runOnUiThread { updateReading(match) }
                                }
                            }
                            .addOnFailureListener {
                                runOnUiThread { updateReading(null) }
                            }
                            .addOnCompleteListener {
                                if (!bitmapForCleanup.isRecycled) {
                                    bitmapForCleanup.recycle()
                                }
                                processing.set(false)
                                proxy.close()
                            }

                        bitmap = null 
                    } catch (e: Exception) {
                        bitmap?.let {
                            if (!it.isRecycled) it.recycle()
                        }
                        processing.set(false)
                        proxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                status.text = "تعذر تشغيل الكاميرا: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun imageProxyToGrayBitmap(proxy: ImageProxy): Bitmap? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val crop = proxy.cropRect
        val width = crop.width()
        val height = crop.height()

        if (width < 20 || height < 20) return null

        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index =
                    (y + crop.top) * plane.rowStride +
                    (x + crop.left) * plane.pixelStride

                val value = buffer.get(index).toInt() and 0xFF
                pixels[y * width + x] =
                    (0xFF shl 24) or
                    (value shl 16) or
                    (value shl 8) or value
            }
        }

        val rotation = proxy.imageInfo.rotationDegrees
        val rotatedPixels = rotatePixels(pixels, width, height, rotation)
        val rotatedWidth =
            if (rotation == 90 || rotation == 270) height else width
        val rotatedHeight =
            if (rotation == 90 || rotation == 270) width else height

        return Bitmap.createBitmap(
            rotatedPixels,
            rotatedWidth,
            rotatedHeight,
            Bitmap.Config.ARGB_8888
        )
    }

    private fun rotatePixels(
        source: IntArray,
        width: Int,
        height: Int,
        rotation: Int
    ): IntArray {
        if (rotation == 0) return source

        val outWidth =
            if (rotation == 90 || rotation == 270) height else width
        val outHeight =
            if (rotation == 90 || rotation == 270) width else height

        val output = IntArray(outWidth * outHeight)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val destination = when (rotation) {
                    90 -> (x * outWidth) + (height - 1 - y)
                    180 -> (height - 1 - y) * outWidth + (width - 1 - x)
                    270 -> (width - 1 - x) * outWidth + y
                    else -> y * width + x
                }
                output[destination] = source[y * width + x]
            }
        }
        return output
    }

    private fun isValidWeight(value: String): Boolean {
        if (value.isEmpty()) return false
        // نتأكد أن القراءة تحتوي على أرقام منطقية للميزان (نستبعد الأرقام القصيرة جداً مثل رقم واحد منفرد إذا لم تكن عشرية، ونقبل الأرقام التي تحتوي على فاصلة أو مكونة من خانتين فأكثر)
        val d = value.toDoubleOrNull() ?: return false
        if (value.length == 1 && d < 10) return false // تجاهل الأرقام المنفردة الصغيرة مثل 9 الخاطئة
        return true
    }

    private fun updateReading(match: String?) {
        if (match != null && isValidWeight(match)) {
            if (match == pendingCandidate) {
                stableCount++
            } else {
                pendingCandidate = match
                stableCount = 1
            }

            detected.text = match

            if (stableCount >= requiredStableReads) {
                candidate = match
                status.text = "تم تثبيت قراءة الوزن. يمكنك التأكيد والإرسال."
                confirm.isEnabled = true
            } else {
                candidate = null
                confirm.isEnabled = false
                status.text = "جارٍ التأكد من القراءة: $stableCount/$requiredStableReads"
            }
        } else {
            candidate = null
            pendingCandidate = null
            stableCount = 0
            detected.text = "---"
            confirm.isEnabled = false
            status.text = "جارٍ البحث عن أرقام شاشة الميزان..."
        }
    }

    /**
     * دمج ذكي للنصوص المستخرجة من شاشة الميزان لضمان التقاط الرقم كاملاً (مثل 21.03)
     */
    private fun extractWeight(result: Text): String? {
        val allTextBuilder = StringBuilder()
        
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val normalized = line.text.map { char ->
                    when (char) {
                        in '٠'..'٩' -> '0' + (char - '٠')
                        in '۰'..'۹' -> '0' + (char - '۰')
                        '٫', '،', ',' -> '.'
                        else -> char
                    }
                }.joinToString("").replace(Regex("[^0-9.]"), "")

                if (normalized.isNotEmpty()) {
                    allTextBuilder.append(normalized).append(" ")
                }
            }
        }

        val combinedText = allTextBuilder.toString().replace(Regex("\\s+"), "")
        
        // البحث عن نمط أرقام يتضمن فاصلة أو مكون من خانات متعددة (مثل 2103 أو 21.03)
        // إذا كان الميزان يعرض الرقم بدون فاصلة واضحة أو بفاصلة، نحاول تصحيحه
        val exactPattern = Regex("([0-9]{2,5})\\.?([0-9]{2})")
        val match = exactPattern.find(combinedText)
        if (match != null) {
            val integerPart = match.groupValues[1]
            val decimalPart = match.groupValues[2]
            
            // إذا كان الرقم طويلاً بدون فاصلة صريحة (مثل 2103)، نقوم بوضع الفاصلة قبل آخر خانتين تلقائياً ليصبح 21.03
            val formatted = if (!combinedText.contains(".")) {
                if (integerPart.length >= 3) {
                    val mainInt = integerPart.substring(0, integerPart.length - 2)
                    val dec = integerPart.takeLast(2)
                    "$mainInt.$dec"
                } else {
                    integerPart
                }
            } else {
                "$integerPart.$decimalPart"
            }

            if (isValidWeight(formatted)) {
                return formatted
            }
        }

        // خيار احتياطي: البحث عن أي رقم صحيح طويل صالح
        val candidateList = mutableListOf<String>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val lineClean = line.text.replace(Regex("[^0-9.]"), "")
                if (lineClean.length >= 2 && isValidWeight(lineClean)) {
                    candidateList.add(lineClean)
                }
            }
        }

        return candidateList.maxByOrNull { it.length }
    }

    private fun returnValue() {
        val value = candidate ?: return
        val result = Intent().putExtra("value", value)
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}
