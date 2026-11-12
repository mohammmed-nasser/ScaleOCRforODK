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
    private val requiredStableReads = 2 // تم تقليل عدد القراءات المتطابقة لتسريع الاستجابة

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
                        val input = InputImage.fromBitmap(bitmap, 0)

                        // الاعتماد الأساسي على ML Kit للتعرف السريع والدقيق على شاشات الميزان والكمبيوتر
                        recognizer.process(input)
                            .addOnSuccessListener { result ->
                                val mlKitMatch = extractWeight(result)
                                // إذا لم يجد ML Kit نتيجة واضحة، نحاول باستخدام الخوارزمية البديلة
                                val finalMatch = mlKitMatch ?: recognizeSevenSegment(bitmapForCleanup)
                                runOnUiThread { updateReading(finalMatch) }
                            }
                            .addOnFailureListener {
                                val sevenSegmentMatch = recognizeSevenSegment(bitmapForCleanup)
                                runOnUiThread { updateReading(sevenSegmentMatch) }
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

    private data class DigitBox(
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    private data class RowBand(val top: Int, val bottom: Int) {
        val height: Int get() = bottom - top + 1
    }

    private fun recognizeSevenSegment(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 80 || height < 80) return null

        val gray = IntArray(width * height)
        val source = IntArray(width * height)
        bitmap.getPixels(source, 0, width, 0, 0, width, height)

        for (i in source.indices) {
            gray[i] = source[i] and 0xFF
        }

        val darkMask = adaptiveMask(gray, width, height, darkForeground = true)
        val lightMask = adaptiveMask(gray, width, height, darkForeground = false)

        val darkResult = decodeMask(darkMask, width, height)
        if (darkResult != null) return darkResult

        return decodeMask(lightMask, width, height)
    }

    private fun adaptiveMask(
        gray: IntArray,
        width: Int,
        height: Int,
        darkForeground: Boolean
    ): BooleanArray {
        val stride = width + 1
        val integral = LongArray((width + 1) * (height + 1))

        for (y in 1..height) {
            var rowSum = 0L
            for (x in 1..width) {
                rowSum += gray[(y - 1) * width + (x - 1)]
                integral[y * stride + x] =
                    integral[(y - 1) * stride + x] + rowSum
            }
        }

        val radius = max(8, min(width, height) / 28)
        val offset = 7.0
        val mask = BooleanArray(width * height)

        for (y in 0 until height) {
            val y1 = max(0, y - radius)
            val y2 = min(height - 1, y + radius)

            for (x in 0 until width) {
                val x1 = max(0, x - radius)
                val x2 = min(width - 1, x + radius)

                val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum =
                    integral[(y2 + 1) * stride + (x2 + 1)] -
                    integral[y1 * stride + (x2 + 1)] -
                    integral[(y2 + 1) * stride + x1] +
                    integral[y1 * stride + x1]

                val mean = sum.toDouble() / count
                val value = gray[y * width + x].toDouble()

                mask[y * width + x] =
                    if (darkForeground) value < mean - offset
                    else value > mean + offset
            }
        }

        return mask
    }

    private fun decodeMask(
        mask: BooleanArray,
        width: Int,
        height: Int
    ): String? {
        val rowCounts = IntArray(height)
        for (y in 0 until height) {
            var count = 0
            val start = y * width
            for (x in 0 until width) {
                if (mask[start + x]) count++
            }
            rowCounts[y] = count
        }

        val rowThreshold = max(4, (width * 0.008f).toInt())
        val bands = findRuns(rowCounts, rowThreshold, maxGap = 2)
            .map { RowBand(it.first, it.second) }
            .filter {
                it.height >= height * 0.035f &&
                it.height <= height * 0.55f
            }

        if (bands.isEmpty()) return null

        var bestResult: String? = null
        var bestScore = Int.MIN_VALUE

        for (band in bands) {
            val boxes = findDigitBoxes(mask, width, height, band)
            if (boxes.size !in 1..8) continue

            val heights = boxes.map { it.height }.sorted()
            val medianHeight = heights[heights.size / 2]

            if (medianHeight < 6) continue

            val digits = mutableListOf<Char>()
            var failed = false

            for (box in boxes) {
                val digit = classifyDigit(mask, width, height, box)
                if (digit == null) {
                    failed = true
                    break
                }
                digits.add(digit)
            }
            if (failed) continue

            val output = StringBuilder()
            for (i in digits.indices) {
                output.append(digits[i])
                if (hasDecimalBetween(
                        mask, width, height, band, boxes, i
                    )
                ) {
                    output.append('.')
                }
            }

            val value = output.toString()
            if (!isValidWeight(value)) continue

            val score =
                boxes.size * 100 +
                medianHeight -
                boxes.sumOf { abs(it.height - medianHeight) }

            if (score > bestScore) {
                bestScore = score
                bestResult = value
            }
        }

        return bestResult
    }

    private fun findRuns(
        values: IntArray,
        threshold: Int,
        maxGap: Int
    ): List<Pair<Int, Int>> {
        val runs = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var lastActive = -1

        for (i in values.indices) {
            if (values[i] >= threshold) {
                if (start < 0) start = i
                lastActive = i
            } else if (start >= 0 && i - lastActive > maxGap) {
                runs.add(start to lastActive)
                start = -1
                lastActive = -1
            }
        }

        if (start >= 0) runs.add(start to lastActive)
        return runs
    }

    private fun findDigitBoxes(
        mask: BooleanArray,
        width: Int,
        height: Int,
        band: RowBand
    ): List<DigitBox> {
        val columnCounts = IntArray(width)

        for (x in 0 until width) {
            var count = 0
            for (y in band.top..band.bottom) {
                if (mask[y * width + x]) count++
            }
            columnCounts[x] = count
        }

        val threshold = max(2, (band.height * 0.030f).toInt())
        val runs = findRuns(columnCounts, threshold, maxGap = 1)

        val boxes = mutableListOf<DigitBox>()

        for ((left, right) in runs) {
            val runWidth = right - left + 1
            if (runWidth < band.height * 0.05f ||
                runWidth > band.height * 1.2f
            ) continue

            var top = height
            var bottom = -1

            for (y in band.top..band.bottom) {
                var rowCount = 0
                for (x in left..right) {
                    if (mask[y * width + x]) rowCount++
                }
                if (rowCount > 0) {
                    top = min(top, y)
                    bottom = max(bottom, y)
                }
            }

            if (bottom > top) {
                boxes.add(DigitBox(left, right, top, bottom))
            }
        }

        return boxes.sortedBy { it.left }
    }

    private fun classifyDigit(
        mask: BooleanArray,
        imageWidth: Int,
        imageHeight: Int,
        box: DigitBox
    ): Char? {
        val patterns = mapOf(
            63 to '0',
            6 to '1',
            91 to '2',
            79 to '3',
            102 to '4',
            109 to '5',
            125 to '6',
            7 to '7',
            127 to '8',
            111 to '9'
        )

        val points = arrayOf(
            0.50f to 0.12f,
            0.86f to 0.30f,
            0.86f to 0.70f,
            0.50f to 0.88f,
            0.14f to 0.70f,
            0.14f to 0.30f,
            0.50f to 0.50f
        )

        var bits = 0

        for (i in points.indices) {
            val cx = box.left + points[i].first * box.width
            val cy = box.top + points[i].second * box.height

            val patchW = max(2, (box.width * 0.20f).toInt())
            val patchH = max(2, (box.height * 0.16f).toInt())

            val x1 = max(box.left, (cx - patchW / 2f).toInt())
            val x2 = min(box.right, (cx + patchW / 2f).toInt())
            val y1 = max(box.top, (cy - patchH / 2f).toInt())
            val y2 = min(box.bottom, (cy + patchH / 2f).toInt())

            var active = 0
            var total = 0

            for (y in y1..y2) {
                for (x in x1..x2) {
                    if (x in 0 until imageWidth && y in 0 until imageHeight) {
                        total++
                        if (mask[y * imageWidth + x]) active++
                    }
                }
            }

            if (total > 0 && active.toFloat() / total >= 0.20f) {
                bits = bits or (1 shl i)
            }
        }

        val best = patterns.minByOrNull {
            Integer.bitCount(it.key xor bits)
        } ?: return null

        if (Integer.bitCount(best.key xor bits) > 2) return null
        return best.value
    }

    private fun hasDecimalBetween(
        mask: BooleanArray,
        width: Int,
        height: Int,
        band: RowBand,
        boxes: List<DigitBox>,
        index: Int
    ): Boolean {
        val current = boxes[index]
        val next = boxes.getOrNull(index + 1) ?: return false

        val gapLeft = current.right + 1
        val gapRight = next.left - 1
        if (gapRight < gapLeft) return false

        val yStart = max(
            band.top,
            current.top + (current.height * 0.60f).toInt()
        )
        val yEnd = min(
            height - 1,
            band.bottom + (current.height * 0.25f).toInt()
        )

        var count = 0
        for (y in yStart..yEnd) {
            for (x in gapLeft..gapRight) {
                if (x in 0 until width && mask[y * width + x]) {
                    count++
                }
            }
        }

        return count >= 1
    }

    private fun isValidWeight(value: String): Boolean {
        if (value.isEmpty()) return false
        // السماح بقراءة الأرقام الصحيحة أو العشرية بمرونة كاملة
        return value.toDoubleOrNull() != null
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
            detected.text = — — —
            confirm.isEnabled = false
            status.text = "جارٍ البحث عن أرقام شاشة الميزان..."
        }
    }

    /**
     * OCR محسن ومعدل ليتعامل مع أرقام شاشات الموازان وشاشات الكمبيوتر بمرونة عالية.
     */
    private fun extractWeight(result: Text): String? {
        val cleanResults = mutableListOf<String>()

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

                if (normalized.isNotEmpty() && isValidWeight(normalized)) {
                    cleanResults.add(normalized)
                }
            }
        }

        // إرجاع أطول رقم تم التعرف عليه (عادةً هو قيمة الوزن الكاملة على الشاشة)
        return cleanResults.maxByOrNull { it.length }
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
