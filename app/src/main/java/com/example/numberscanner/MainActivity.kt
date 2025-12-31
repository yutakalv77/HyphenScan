package com.example.numberscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.numberscanner.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    // Analyzerのインスタンスを保持しておく変数
    private var textAnalyzer: TextAnalyzer? = null

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext, "カメラの権限が許可されませんでした。", Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // シークバーの設定 (1秒〜10秒)
        // SeekBarは0から始まるので、0=1秒, 9=10秒 として扱います
        binding.seekBarInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress + 1
                binding.txtIntervalLabel.text = "更新間隔: ${seconds}秒"

                // Analyzerの間隔を更新 (ミリ秒に変換)
                textAnalyzer?.updateThrottleInterval(seconds * 1000L)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            // Analyzerのインスタンスを作成
            textAnalyzer = TextAnalyzer { text ->
                runOnUiThread {
                    val currentTextWithoutHyphen = binding.txtResult.text.toString().replace("-", "")

                    if (currentTextWithoutHyphen != text) {
                        binding.txtResult.text = formatNumber(text)
                    }
                }
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolutionSelector)
                .build()
                .also {
                    // 作成したインスタンスをセット
                    it.setAnalyzer(cameraExecutor, textAnalyzer!!)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private class TextAnalyzer(private val onTextFound: (String) -> Unit) : ImageAnalysis.Analyzer {

        private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        private var lastAnalyzedTimestamp = 0L

        // デフォルトは1秒 (1000ms)
        // Volatileアノテーションをつけて、スレッド間の可視性を保証します
        @Volatile
        private var throttleIntervalMs = 1000L

        // 外部から間隔を変更するためのメソッド
        fun updateThrottleInterval(newInterval: Long) {
            throttleIntervalMs = newInterval
        }

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {

            val currentTimestamp = System.currentTimeMillis()

            if (currentTimestamp - lastAnalyzedTimestamp < throttleIntervalMs) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                // ここでタイムスタンプを更新
                lastAnalyzedTimestamp = currentTimestamp

                val originalBitmap = imageProxy.toBitmap()

                if (originalBitmap == null) {
                    imageProxy.close()
                    return
                }

                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotationDegrees.toFloat())

                val rotatedBitmap = try {
                    android.graphics.Bitmap.createBitmap(
                        originalBitmap,
                        0, 0,
                        originalBitmap.width, originalBitmap.height,
                        matrix, true
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Rotate failed", e)
                    imageProxy.close()
                    return
                }

                val cropWidth = (rotatedBitmap.width * 0.8).toInt()
                val cropHeight = (rotatedBitmap.height * 0.2).toInt()

                val cropX = (rotatedBitmap.width - cropWidth) / 2
                val cropY = (rotatedBitmap.height - cropHeight) / 2

                val safeX = cropX.coerceAtLeast(0)
                val safeY = cropY.coerceAtLeast(0)
                val safeWidth = cropWidth.coerceAtMost(rotatedBitmap.width - safeX)
                val safeHeight = cropHeight.coerceAtMost(rotatedBitmap.height - safeY)

                val croppedBitmap = try {
                    android.graphics.Bitmap.createBitmap(
                        rotatedBitmap,
                        safeX, safeY, safeWidth, safeHeight
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Crop failed", e)
                    imageProxy.close()
                    return
                }

                val image = InputImage.fromBitmap(croppedBitmap, 0)

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val imgCenterX = croppedBitmap.width / 2f
                        val imgCenterY = croppedBitmap.height / 2f

                        data class TextCandidate(val text: String, val distance: Float)
                        val candidates = mutableListOf<TextCandidate>()

                        for (block in visionText.textBlocks) {
                            val box = block.boundingBox ?: continue

                            val blockCenterX = box.centerX().toFloat()
                            val blockCenterY = box.centerY().toFloat()

                            val dx = imgCenterX - blockCenterX
                            val dy = imgCenterY - blockCenterY
                            val distance = dx * dx + dy * dy

                            val cleanText = block.text.replace(Regex("\\s+"), "")
                                .replace(Regex("[^a-zA-Z0-9]"), "")

                            if (cleanText.length >= 3) {
                                candidates.add(TextCandidate(cleanText, distance))
                            }
                        }

                        val bestMatch = candidates.minByOrNull { it.distance }?.text

                        if (bestMatch != null) {
                            onTextFound(bestMatch)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Text recognition failed", e)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    private fun formatNumber(number: String): String {
        return number.chunked(3).joinToString("-")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).toTypedArray()
    }
}
