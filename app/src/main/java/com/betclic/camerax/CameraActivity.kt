package com.betclic.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.android.synthetic.main.activity_camera.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        const val IBAN_PREFIX_FRANCE = "FR"
        val IBAN_PATTERN_FR = "^${IBAN_PREFIX_FRANCE}\\d{12}[0-9A-Z]{11}\\d{2}\$".toRegex()

        fun newIntent(context: Context) =
            Intent(context, CameraActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
    }

    var captureType = CaptureType.IBAN
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraSelector: CameraSelector? = null
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Setup the listener for take photo button
        camera_capture_button.setOnClickListener { takePhoto() }

        camera_flash_button.setOnClickListener {
            if (imageCapture?.flashMode != ImageCapture.FLASH_MODE_ON) {
                flashMode = ImageCapture.FLASH_MODE_ON
                camera?.cameraControl?.enableTorch(true)
            } else {
                flashMode = ImageCapture.FLASH_MODE_OFF
                camera?.cameraControl?.enableTorch(false)
            }
            imageCapture = ImageCapture.Builder()
                .setFlashMode(flashMode)
                .build()
        }

        camera_back_button.setOnClickListener { onBackPressed() }

        camera_switch_button.setOnClickListener { toggleCamera() }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                .build()

            imageCapture = ImageCapture.Builder()
                .setFlashMode(flashMode)
                .build()


            imageAnalyzer = ImageAnalysis.Builder()
                .setImageQueueDepth(1)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { image: ImageProxy ->
                        graphic_overlay.setCameraInfo(image.width, image.height, 1)
                        runTextRecognitionFromImageProxy(image)
                    })
                }

            // Select lens camera
            cameraSelector =
                CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector!!, preview, imageCapture, imageAnalyzer
                )
                preview?.setSurfaceProvider(viewFinder.createSurfaceProvider())
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create timestamped output file to hold the image
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(
                FILENAME_FORMAT, Locale.US
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Setup image capture listener which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val msg = "Photo capture succeeded: $savedUri"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)

                    when (captureType) {
                        CaptureType.IBAN -> runTextRecognitionFromUri(savedUri)
                        CaptureType.DOCUMENT -> TODO()
                    }

                }
            })
    }

    private fun runTextRecognitionFromUri(savedUri: Uri) {
        val image = InputImage.fromFilePath(baseContext, savedUri)
        val recognizer = TextRecognition.getClient()
        recognizer.process(image)
            .addOnSuccessListener { processResults(it) }
            .addOnFailureListener {
                Toast.makeText(baseContext, "Error detecting Text $it", Toast.LENGTH_LONG)
                    .show()
            }
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun runTextRecognitionFromImageProxy(proxyImage: ImageProxy) {
        val recognizer = TextRecognition.getClient()

        val mediaImage = proxyImage.image
        mediaImage?.let { img ->
            val image =
                InputImage.fromMediaImage(img, proxyImage.imageInfo.rotationDegrees)

            recognizer.process(image)
                .addOnSuccessListener { processResults(it) }
                .addOnFailureListener {
                    Toast.makeText(baseContext, "Error detecting Text $it", Toast.LENGTH_LONG)
                        .show()
                }
                .addOnCompleteListener { proxyImage.close() }
        }
    }

    private fun processResults(text: Text) {
        if (text.textBlocks.isNullOrEmpty()) {
            camera_infos_title.text = resources.getString(R.string.app_name)
            camera_infos_description.text = "No iban detected, please try again!"
        }

        text.textBlocks.forEach { textBlock ->
            textBlock.lines.forEach { line ->
                checkIfIbanPresent(line)
            }
        }
    }

    private fun checkIfIbanPresent(line: Text.Line) {
        val text = line.text
        Log.i("IBAN", text.substringAfter("FR"))

        val ibanResult = "$IBAN_PREFIX_FRANCE${text.substringAfter(IBAN_PREFIX_FRANCE)}"
        if (ibanResult.replace("\\s".toRegex(), "").matches(IBAN_PATTERN_FR)) {
            camera_infos_description.text = "Iban detected"
            Log.i("IBAN", "iban detected")

            // draw box
//            graphic_overlay.clear()
//            line.elements.indices.forEach { index ->
//                val textGraphic: GraphicOverlay.Graphic =
//                    TextGraphic(graphic_overlay, line.elements[index])
//                graphic_overlay.add(textGraphic)
//            }

            // Send result
            baseContext.startActivity(
                ResultActivity.newIntent(
                    baseContext,
                    ibanResult
                )
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }
}