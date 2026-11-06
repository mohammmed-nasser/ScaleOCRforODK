package com.example.scaleocr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var detected: TextView
    private lateinit var confirm: Button
    private lateinit var cameraExecutor: ExecutorService
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val processing = AtomicBoolean(false)
    @Volatile private var candidate: String? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "يلزم السماح باستخدام الكاميرا لقراءة الوزن."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else cameraPermission.launch(Manifest.permission.CAMERA)
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
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }
        root.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                val mediaImage = proxy.image
                if (mediaImage == null || !processing.compareAndSet(false, true)) {
                    proxy.close()
                } else {
                    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                    recognizer.process(image)
                        .addOnSuccessListener { result ->
                            val match = extractWeight(result.text)
                            runOnUiThread {
                                if (match != null) {
                                    candidate = match
                                    detected.text = match
                                    status.text = "تم التعرف على وزن محتمل. راجعه قبل الإرسال."
                                    confirm.isEnabled = true
                                } else {
                                    candidate = null
                                    detected.text = "— — —"
                                    status.text = "لم أتعرف على وزن بصيغة 00.000 بعد"
                                    confirm.isEnabled = false
                                }
                            }
                        }
                        .addOnFailureListener {
                            runOnUiThread { status.text = "تعذرت قراءة الصورة؛ ثبّت الهاتف وحسّن الإضاءة." }
                        }
                        .addOnCompleteListener {
                            processing.set(false)
                            proxy.close()
                        }
                }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                status.text = "تعذر تشغيل الكاميرا: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Accept exactly three decimal digits. Normalize comma to dot, then validate a decimal range.
    private fun extractWeight(text: String): String? {
        val normalized = text.replace(',', '.')
        val pattern = Pattern.compile("(?<![0-9])[0-9]{1,6}\\.[0-9]{3}(?![0-9])")
        val matcher = pattern.matcher(normalized)
        if (!matcher.find()) return null
        val value = matcher.group()
        return value?.takeIf { it.toDoubleOrNull() != null }
    }

    private fun returnValue() {
        val value = candidate ?: return
        val result = intent.apply { putExtra("value", value) }
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}
