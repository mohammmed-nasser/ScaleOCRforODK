from pathlib import Path

source = r'''package com.example.scaleocr

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
                this, Manifest.permission.CAMERA
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
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
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

    private data class FrameBitmaps(
        val original: Bitmap,
        val binary: Bitmap
    )

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
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    if (!processing.compareAndSet(false, true)) {
                        proxy.close()
                        return@setAnalyzer
                    }

                    var frames: FrameBitmaps? = null
                    try {
                        frames = createFrameBitmaps(proxy)

                        if (frames == null) {
                            processing.set(false)
                            proxy.close()
                            return@setAnalyzer
                        }

                        // نجرّب قارئ أرقام شاشة LCD أولاً، بصورة ثنائية محسّنة.
                        val segmentMatch = recognizeSevenSegment(frames.binary)

                        // ML Kit يعمل على الصورة الرمادية الأصلية، لا على الصورة الثنائية فقط.
                        recognizer.process(InputImage.fromBitmap(frames.original, 0))
                            .addOnSuccessListener { result ->
                                val match = segmentMatch ?: extractWeight(result)
                                runOnUiThread { updateReading(match) }
                            }
                            .addOnFailureListener {
                                runOnUiThread { updateReading(segmentMatch) }
                            }
                            .addOnCompleteListener {
                                frames?.original?.recycle()
                                frames?.binary?.recycle()
                                processing.set(false)
                                proxy.close()
                            }

                    } catch (e: Exception) {
                        frames?.original?.recycle()
                        frames?.binary?.recycle()
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

    /*
     * إنشاء صورتين من قناة الإضاءة Y:
     * 1) صورة رمادية تحتفظ بتفاصيل الصورة لعمل ML Kit.
     * 2) صورة ثنائية بتباين محلي لتحليل أرقام شاشة الميزان.
     *
     * لا نعتمد على اللون الأخضر أو على إضاءة الشاشة.
     */
    private fun createFrameBitmaps(proxy: ImageProxy): FrameBitmaps? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val crop = proxy.cropRect
        val width = crop.width()
        val height = crop.height()

        if (width < 20 || height < 20) return null

        val gray = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index =
                    (y + crop.top) * plane.rowStride +
                    (x + crop.left) * plane.pixelStride

                if (index >= buffer.limit()) return null

                val value = buffer.get(index).toInt() and 0xFF
                gray[y * width + x] =
                    (0xFF shl 24) or (value shl 16) or
                    (value shl 8) or value
            }
        }

        val rotation = proxy.imageInfo.rotationDegrees
        val rotated = rotatePixels(gray, width, height, rotation)

        val rotatedWidth =
            if (rotation == 90 || rotation == 270) height else width
        val rotatedHeight =
            if (rotation == 90 || rotation == 270) width else height

        val full = Bitmap.createBitmap(
            rotated, rotatedWidth, rotatedHeight, Bitmap.Config.ARGB_8888
        )

        /*
         * نحتفظ بمعظم عرض الصورة بدلاً من قص 35% من اليسار.
         * هذا يمنع حذف الرقم الأول إذا كانت شاشة الميزان في منتصف الكادر.
         */
        val left = (rotatedWidth * 0.08f).toInt()
        val right = (rotatedWidth * 0.92f).toInt()
        val roiWidth = right - left

        if (roiWidth < 20 || rotatedHeight < 20) {
            full.recycle()
            return null
        }

        val roi = Bitmap.createBitmap(
            full, left, 0, roiWidth, rotatedHeight
        )
        full.recycle()

        val pixels = IntArray(roi.width * roi.height)
        roi.getPixels(
            pixels, 0, roi.width, 0, 0, roi.width, roi.height
        )

        val original = Bitmap.createBitmap(
            pixels, roi.width, roi.height, Bitmap.Config.ARGB_8888
        )

        val binaryPixels = adaptiveThreshold(
            pixels, roi.width, roi.height
        )

        val binary = Bitmap.createBitmap(
            binaryPixels, roi.width, roi.height, Bitmap.Config.ARGB_8888
        )

        roi.recycle()

        // تكبير الصورة الثنائية لتسهيل تحليل المقاطع الصغيرة.
        val enlarged = Bitmap.createScaledBitmap(
            binary, binary.width * 2, binary.height * 2, false
        )
        binary.recycle()

        return FrameBitmaps(original, enlarged)
    }

    /*
     * عتبة محلية بدلاً من Otsu على كامل الصورة.
     * هذا أفضل عندما تختلف إضاءة الشاشة أو توجد انعكاسات حولها.
     */
    private fun adaptiveThreshold(
        pixels: IntArray,
        width: Int,
        height: Int
    ): IntArray {
        val stride = width + 1
        val integral = IntArray((width + 1) * (height + 1))

        for (y in 1..height) {
            var rowSum = 0
            for (x in 1..width) {
                val gray = pixels[(y - 1) * width + (x - 1)] and 0xFF
                rowSum += gray
                integral[y * stride + x] =
                    integral[(y - 1) * stride + x] + rowSum
            }
        }

        val radius = max(8, min(width, height) / 35)
        val output = IntArray(pixels.size)

        for (y in 0 until height) {
            val y1 = max(0, y - radius)
            val y2 = min(height - 1, y + radius)

            for (x in 0 until width) {
                val x1 = max(0, x - radius)
                val x2 = min(width - 1, x + radius)

                val area =
                    (x2 - x1 + 1) * (y2 - y1 + 1)

                val sum =
                    integral[(y2 + 1) * stride + (x2 + 1)] -
                    integral[y1 * stride + (x2 + 1)] -
                    integral[(y2 + 1) * stride + x1] +
                    integral[y1 * stride + x1]

                val mean = sum / area
                val gray = pixels[y * width + x] and 0xFF

                // أرقام LCD داكنة عادةً من خلفيتها.
                val value = if (gray < mean - 8) 0 else 255

                output[y * width + x] =
                    (0xFF shl 24) or (value shl 16) or
                    (value shl 8) or value
            }
        }

        return output
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
                    90 -> x * outWidth + (height - 1 - y)
                    180 -> (height - 1 - y) * outWidth + (width - 1 - x)
                    270 -> (width - 1 - x) * outWidth + y
                    else -> y * outWidth + x
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
    )

    /*
     * قارئ تقريبي للأرقام ذات السبعة مقاطع.
     * لا يفترض أن الأرقام بخط Excel أو بخط مطبوع.
     */
    private fun recognizeSevenSegment(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 20 || height < 20) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        fun isDark(x: Int, y: Int): Boolean {
            if (x !in 0 until width || y !in 0 until height) return false
            return (pixels[y * width + x] and 0xFF) < 128
        }

        val columnCounts = IntArray(width)
        for (x in 0 until width) {
            for (y in 0 until height) {
                if (isDark(x, y)) columnCounts[x]++
            }
        }

        /*
         * لا نستخدم 6% من ارتفاع الصورة كما في النسخة السابقة؛
         * لأن ذلك كان يستبعد الأعمدة التي لا تحتوي إلا على أجزاء من الرقم.
         */
        val columnThreshold = max(2, (height * 0.012f).toInt())
        val active = BooleanArray(width) { x ->
            columnCounts[x] >= columnThreshold
        }

        val boxes = mutableListOf<DigitBox>()
        var x = 0

        while (x < width) {
            if (!active[x]) {
                x++
                continue
            }

            val start = x
            while (x < width && active[x]) x++
            val end = x - 1
            val digitWidth = end - start + 1

            var top = height
            var bottom = -1

            for (yy in 0 until height) {
                var count = 0
                for (xx in start..end) {
                    if (isDark(xx, yy)) count++
                }

                if (count >= max(2, (digitWidth * 0.07f).toInt())) {
                    top = min(top, yy)
                    bottom = max(bottom, yy)
                }
            }

            if (bottom > top) {
                val digitHeight = bottom - top + 1

                // المقارنة هنا مع ارتفاع الرقم نفسه، لا ارتفاع إطار الكاميرا.
                if (digitWidth >= digitHeight * 0.12f &&
                    digitWidth <= digitHeight * 0.90f
                ) {
                    boxes.add(DigitBox(start, end, top, bottom))
                }
            }
        }

        if (boxes.size !in 2..6) return null

        // ترتيب المقاطع: أعلى، يمين أعلى، يمين أسفل، أسفل،
        // يسار أسفل، يسار أعلى، الوسط.
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

        fun segmentIsOn(
            box: DigitBox,
            centerX: Float,
            centerY: Float,
            patchWidth: Float,
            patchHeight: Float
        ): Boolean {
            val digitWidth = (box.right - box.left + 1).toFloat()
            val digitHeight = (box.bottom - box.top + 1).toFloat()

            val x1 = max(
                box.left,
                (box.left + digitWidth * centerX - patchWidth / 2).toInt()
            )
            val x2 = min(
                box.right,
                (box.left + digitWidth * centerX + patchWidth / 2).toInt()
            )
            val y1 = max(
                box.top,
                (box.top + digitHeight * centerY - patchHeight / 2).toInt()
            )
            val y2 = min(
                box.bottom,
                (box.top + digitHeight * centerY + patchHeight / 2).toInt()
            )

            if (x2 < x1 || y2 < y1) return false

            var dark = 0
            var total = 0
            for (yy in y1..y2) {
                for (xx in x1..x2) {
                    total++
                    if (isDark(xx, yy)) dark++
                }
            }
            return total > 0 && dark.toFloat() / total >= 0.28f
        }

        fun classifyDigit(box: DigitBox): Char? {
            val digitWidth = (box.right - box.left + 1).toFloat()
            val digitHeight = (box.bottom - box.top + 1).toFloat()
            if (digitWidth <= 0 || digitHeight <= 0) return null

            val points = arrayOf(
                0.50f to 0.12f,
                0.86f to 0.30f,
                0.86f to 0.70f,
                0.50f to 0.88f,
                0.14f to 0.70f,
                0.14f to 0.30f,
                0.50f to 0.50f
            )

            var mask = 0
            for (i in points.indices) {
                val p = points[i]
                if (segmentIsOn(
                        box, p.first, p.second,
                        digitWidth * 0.24f, digitHeight * 0.20f
                    )
                ) {
                    mask = mask or (1 shl i)
                }
            }

            val best = patterns.minByOrNull {
                Integer.bitCount(it.key xor mask)
            } ?: return null

            if (Integer.bitCount(best.key xor mask) > 1) return null
            return best.value
        }

        val digits = boxes.map { classifyDigit(it) ?: return null }

        fun hasDecimalAfter(index: Int): Boolean {
            val current = boxes[index]
            val next = boxes.getOrNull(index + 1) ?: return false

            val gapStart = current.right + 1
            val gapEnd = next.left - 1
            if (gapEnd < gapStart) return false

            val digitHeight = current.bottom - current.top + 1
            val lowerStart = current.top + (digitHeight * 0.72f).toInt()
            val lowerEnd = min(
                height - 1,
                current.bottom + max(2, (digitHeight * 0.18f).toInt())
            )
            if (lowerStart > lowerEnd) return false

            var darkCount = 0
            var minX = width
            var maxX = -1
            var minY = height
            var maxY = -1

            for (yy in lowerStart..lowerEnd) {
                for (xx in gapStart..gapEnd) {
                    if (isDark(xx, yy)) {
                        darkCount++
                        minX = min(minX, xx)
                        maxX = max(maxX, xx)
                        minY = min(minY, yy)
                        maxY = max(maxY, yy)
                    }
                }
            }

            if (darkCount < 2) return false
            val dotWidth = maxX - minX + 1
            val dotHeight = maxY - minY + 1

            return dotWidth <= digitHeight * 0.22f &&
                dotHeight <= digitHeight * 0.24f
        }

        val output = StringBuilder()
        for (i in digits.indices) {
            output.append(digits[i])
            if (hasDecimalAfter(i)) output.append('.')
        }

        val value = output.toString()
        if (!value.contains('.') || value.count { it == '.' } != 1) return null
        if (value.startsWith('.') || value.endsWith('.')) return null

        return value.takeIf { it.toDoubleOrNull() != null }
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
            detected.text = "— — —"
            confirm.isEnabled = false
            status.text = "لم يتم تمييز الأرقام بعد. قرّب الكاميرا وثبّتها أمام الشاشة."
        }
    }

    // OCR احتياطي للأرقام المطبوعة أو الواضحة.
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
                }.joinToString("").replace(Regex("\\s+"), "")

                val match = pattern.find(normalized) ?: return@mapNotNull null
                val value = "${match.groupValues[1]}.${match.groupValues[2]}"
                if (value.toDoubleOrNull() == null) return@mapNotNull null

                val height = line.boundingBox?.height() ?: 0
                val width = line.boundingBox?.width() ?: 0
                Triple(value, height, width)
            }

        return candidates.maxWithOrNull(
            compareBy<Triple<String, Int, Int>> { it.second }
                .thenBy { it.third }
        )?.first
    }

    private fun returnValue() {
        val value = candidate ?: return
        val result = Intent().putExtra("value", value)
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        recognizer.close()
    }
}
'''

path = Path("/mnt/data/MainActivity_Updated.kt")
path.write_text(source, encoding="utf-8")
print(f"تم إنشاء الملف: {path.name}")
print(f"عدد الأسطر: {len(source.splitlines())}")
