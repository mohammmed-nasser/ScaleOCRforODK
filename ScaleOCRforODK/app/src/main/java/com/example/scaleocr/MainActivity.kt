package com.example.scaleocr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var detected: TextView
    private lateinit var captureButton: Button
    private lateinit var confirmButton: Button
    private lateinit var cameraExecutor: ExecutorService

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var imageCapture: ImageCapture? = null

    private var capturedValue: String? = null

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

        val heading = TextView(this).apply { text = "التقاط وزن الميزان"; textSize = 23f; gravity = Gravity.CENTER }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        status = TextView(this).apply { text = "اجعل شاشة الميزان وحدها داخل الإطار ثم اضغط التقاط"; textSize = 15f; gravity = Gravity.CENTER; setPadding(0, 14, 0, 8) }
        detected = TextView(this).apply { text = "---"; textSize = 34f; gravity = Gravity.CENTER; setPadding(0, 8, 0, 14) }

        captureButton = Button(this).apply {
            text = "📷 التقاط صورة الميزان"
            setOnClickListener { takePhoto() }
        }

        confirmButton = Button(this).apply {
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
        root.addView(captureButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(confirmButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                status.text = "تعذر تشغيل الكاميرا: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        status.text = "جاري التقاط الصورة وتحليلها..."

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(proxy: ImageProxy) {
                try {
                    val bitmap = imageProxyToBitmap(proxy)
                    proxy.close()

                    if (bitmap != null) {
                        val rotatedBitmap = rotateBitmap(bitmap, 90f)
                        val processedBitmap = preprocessBitmapForScale(rotatedBitmap)
                        
                        val input = InputImage.fromBitmap(processedBitmap, 0)
                        recognizer.process(input)
                            .addOnSuccessListener { result ->
                                val match = extractScaleWeight(result)
                                runOnUiThread { handleCapturedResult(match) }
                            }
                            .addOnFailureListener {
                                runOnUiThread { handleCapturedResult(null) }
                            }
                    } else {
                        runOnUiThread {
                            status.text = "فشل معالجة الصورة، حاول مرة أخرى."
                        }
                    }
                } catch (e: Exception) {
                    proxy.close()
                    runOnUiThread {
                        status.text = "حدث خطأ: ${e.localizedMessage}"
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    status.text = "فشل الالتقاط: ${exception.localizedMessage}"
                }
            }
        })
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val width = proxy.width
        val height = proxy.height
        if (width < 20 || height < 20) return null

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index < buffer.limit()) {
                    val value = buffer.get(index).toInt() and 0xFF
                    pixels[y * width + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun preprocessBitmapForScale(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val scaled = Bitmap.createScaledBitmap(source, width, height, false)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            // عزل شاشة الميزان الخضراء والتركيز على الأرقام الداكنة داخلها
            val isGreenLight = (g > r + 15) && (g > b + 15) && (g > 70)
            pixels[i] = if (isGreenLight) {
                if (g < 150) Color.BLACK else Color.WHITE
            } else {
                Color.WHITE
            }
        }

        val processed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        processed.setPixels(pixels, 0, width, 0, 0, width, height)
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

    private fun handleCapturedResult(match: String?) {
        if (match != null) {
            capturedValue = match
            detected.text = match
            status.text = "تمت قراءة الوزن بنجاح! اضغط تأكيد أو اعتد الالتقاط"
            confirmButton.isEnabled = true
            captureButton.text = "🔄 إعادة التقاط الصورة"
        } else {
            capturedValue = null
            detected.text = "---"
            confirmButton.isEnabled = false
            captureButton.text = "📷 التقاط صورة الميزان"
            status.text = "تعذر قراءة الرقم بوضوح، قرب الكاميرا وحاول مجدداً."
        }
    }

    private fun returnValue() {
        val value = capturedValue ?: return
        setResult(RESULT_OK, Intent().putExtra("value", value))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}
