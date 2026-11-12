
package com.example.scaleocr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
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

    private val requiredStableReads = 3

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else status.text = "يلزم السماح باستخدام الكاميرا."
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
            text = "— — —"
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

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {

        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy ->

                    if (!processing.compareAndSet(false, true)) {
                        proxy.close()
                        return@setAnalyzer
                    }

                    try {
                        val bitmap = createEnhancedBitmap(proxy)

                        if (bitmap == null) {
                            processing.set(false)
                            proxy.close()
                            return@setAnalyzer
                        }

                        val input = InputImage.fromBitmap(bitmap, 0)

                        recognizer.process(input)
                            .addOnSuccessListener { result ->

                                val match = extractWeight(result)

                                runOnUiThread {
                                    updateReading(match)
                                }
                            }
                            .addOnFailureListener {
                                runOnUiThread {
                                    status.text =
                                        "تعذرت القراءة، ثبّت الهاتف وحسّن الإضاءة."
                                }
                            }
                            .addOnCompleteListener {
                                bitmap.recycle()
                                processing.set(false)
                                proxy.close()
                            }

                    } catch (e: Exception) {
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

    // تحويل قناة الإضاءة إلى صورة، مع مراعاة اتجاه الكاميرا.
    private fun createEnhancedBitmap(proxy: ImageProxy): Bitmap? {

        val plane = proxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()

        val crop = proxy.cropRect
        val width = crop.width()
        val height = crop.height()

        if (width <= 0 || height <= 0) return null

        val gray = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {

                val index =
                    (y + crop.top) * plane.rowStride +
                    (x + crop.left) * plane.pixelStride

                val value = buffer.get(index).toInt() and 0xFF

                gray[y * width + x] =
                    (0xFF shl 24) or
                    (value shl 16) or
                    (value shl 8) or value
            }
        }

        val rotation = proxy.imageInfo.rotationDegrees

        val rotated = rotatePixels(gray, width, height, rotation)

        val rw = if (rotation == 90 || rotation == 270) height else width
        val rh = if (rotation == 90 || rotation == 270) width else height

        val fullBitmap = Bitmap.createBitmap(
            rotated,
            rw,
            rh,
            Bitmap.Config.ARGB_8888
        )

        // نأخذ الجهة اليمنى مع ترك مساحة كافية لعدم فقدان الأرقام.
        val left = (rw * 0.35f).toInt()
        val roiWidth = rw - left

        if (roiWidth < 10 || rh < 10) {
            fullBitmap.recycle()
            return null
        }

        val roi = Bitmap.createBitmap(
            fullBitmap,
            left,
            0,
            roiWidth,
            rh
        )

        fullBitmap.recycle()

        val pixels = IntArray(roi.width * roi.height)
        roi.getPixels(
            pixels, 0, roi.width,
            0, 0, roi.width, roi.height
        )

        val histogram = IntArray(256)

        for (pixel in pixels) {
            val value = pixel and 0xFF
            histogram[value]++
        }

        val threshold = otsuThreshold(histogram, pixels.size)

        // إبراز الأرقام الداكنة على الخلفية الفاتحة.
        for (i in pixels.indices) {
            val value = pixels[i] and 0xFF
            val output = if (value < threshold) 0 else 255

            pixels[i] =
                (0xFF shl 24) or
                (output shl 16) or
                (output shl 8) or output
        }

        val binary = Bitmap.createBitmap(
            roi.width,
            roi.height,
            Bitmap.Config.ARGB_8888
        )

        binary.setPixels(
            pixels, 0, roi.width,
            0, 0, roi.width, roi.height
        )

        roi.recycle()

        // تكبير الصورة لتحسين فرصة قراءة المقاطع.
        val enlarged = Bitmap.createScaledBitmap(
            binary,
            binary.width * 2,
            binary.height * 2,
            false
        )

        binary.recycle()

        return enlarged
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
                    90 -> {
                        val dx = height - 1 - y
                        val dy = x
                        dy * outWidth + dx
                    }

                    180 -> {
                        val dx = width - 1 - x
                        val dy = height - 1 - y
                        dy * outWidth + dx
                    }

                    270 -> {
                        val dx = y
                        val dy = width - 1 - x
                        dy * outWidth + dx
                    }

                    else -> y * width + x
                }

                output[destination] = source[y * width + x]
            }
        }

        return output
    }

    private fun otsuThreshold(
        histogram: IntArray,
        total: Int
    ): Int {

        var sum = 0L

        for (i in 0..255) {
            sum += i.toLong() * histogram[i]
        }

        var sumBackground = 0L
        var weightBackground = 0
        var bestVariance = -1.0
        var threshold = 127

        for (i in 0..255) {

            weightBackground += histogram[i]

            if (weightBackground == 0) continue

            val weightForeground = total - weightBackground
            if (weightForeground == 0) break

            sumBackground += i.toLong() * histogram[i]

            val meanBackground =
                sumBackground.toDouble() / weightBackground

            val meanForeground =
                (sum - sumBackground).toDouble() / weightForeground

            val difference = meanBackground - meanForeground

            val variance =
                weightBackground.toDouble() *
                weightForeground.toDouble() *
                difference * difference

            if (variance > bestVariance) {
                bestVariance = variance
                threshold = i
            }
        }

        return threshold.coerceIn(40, 220)
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

                status.text =
                    "تم تثبيت قراءة الوزن. يمكنك التأكيد والإرسال."

                confirm.isEnabled = true

            } else {

                candidate = null
                confirm.isEnabled = false

                status.text =
                    "جارٍ التأكد من القراءة: $stableCount/$requiredStableReads"
            }

        } else {

            candidate = null
            pendingCandidate = null
            stableCount = 0

            detected.text = "— — —"
            confirm.isEnabled = false

            // لا نعرض نص OCR الخام حتى لا تومض الرموز على الشاشة.
            status.text = "جارٍ البحث عن أرقام الوزن في الجهة اليمنى..."
        }
    }

    private fun extractWeight(result: Text): String? {

        val pattern = Regex(
            "(?<![0-9])([0-9]{1,6})[.,٫،]([0-9]{1,3})(?![0-9])"
        )

        val candidates = result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->

                val normalized = line.text.map { char ->
                    when (char) {
                        in '٠'..'٩' -> '0' + (char - '٠')
                        in '۰'..'۹' -> '0' + (char - '۰')
                        '٫', '،', ',' -> '.'
                        else -> char
                    }
                }.joinToString("")
                    .replace(Regex("\\s+"), "")

                val match = pattern.find(normalized)
                    ?: return@mapNotNull null

                val whole = match.groupValues[1]
                val fraction = match.groupValues[2]

                val value = "$whole.${fraction}"

                if (value.toDoubleOrNull() == null) {
                    return@mapNotNull null
                }

                val height = line.boundingBox?.height() ?: 0
                val width = line.boundingBox?.width() ?: 0

                Triple(value, height, width)
            }

        return candidates
            .maxWithOrNull(
                compareBy<Triple<String, Int, Int>> { it.second }
                    .thenBy { it.third }
            )
            ?.first
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
