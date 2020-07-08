package com.betclic.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit
typealias IbanListener = (ibanImage: InputImage, proxyImage: ImageProxy) -> Unit
//typealias IbanFirebaseListener = (ibanFirebaseImage: FirebaseVisionImage) -> Unit

class MainActivity : AppCompatActivity() {
    var captureType = CaptureType.IBAN
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraSelector: CameraSelector? = null
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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


        viewFinder.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_UP) {
                return@setOnTouchListener false
            }

            val factory = viewFinder.createMeteringPointFactory(cameraSelector!!)
            val point = factory.createPoint(event.x, event.y)
            val action = FocusMeteringAction.Builder(point).build()
            camera?.cameraControl?.startFocusAndMetering(action)
            return@setOnTouchListener true
        }

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
                //.setTargetResolution(Size(1280, 720))
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                //.setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { image: ImageProxy ->
                        runTextRecognitionFromImageProxy(image)
                        Log.d(TAG, "image${image.imageInfo}")
                    })


//                    it.setAnalyzer(cameraExecutor, IbanAnalyzer { ibanImage, proxy ->
//                        runTextRecognitionFromImage(ibanImage, proxy)
//
//                        Log.d(TAG, "image${ibanImage.format}")
//
//                    })
//                    it.setAnalyzer(cameraExecutor, IbanFirebaseAnalyzer { ibanImage ->
//                        runTextRecognitionFromFirebaseImage(ibanImage)
//                        //Log.d(TAG, "image${ibanImage.format}")
//                    })
                }

            // Select back camera
            cameraSelector =
                CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector!!, preview, imageCapture, imageAnalyzer
                )
                preview?.setSurfaceProvider(viewFinder.createSurfaceProvider(camera?.cameraInfo))
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
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

//    private fun runTextRecognitionFromFirebaseImage(image: FirebaseVisionImage) {
//        val detector = FirebaseVision.getInstance().onDeviceTextRecognizer
//
//        detector.processImage(image)
//            .addOnSuccessListener { processFirebaseResults(it) }
//            .addOnFailureListener {
//                Toast.makeText(baseContext, "Error detecting Text $it", Toast.LENGTH_LONG)
//                    .show()
//            }
//    }

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


    private fun runTextRecognitionFromImage(image: InputImage, proxyImage: ImageProxy) {
        val recognizer = TextRecognition.getClient()
        recognizer.process(image)
            .addOnSuccessListener { processResults(it) }
            .addOnFailureListener {
                Toast.makeText(baseContext, "Error detecting Text $it", Toast.LENGTH_LONG)
                    .show()
            }
            .addOnCompleteListener { proxyImage.close() }
    }

//    private fun processFirebaseResults(text: FirebaseVisionText) {
//        if (text.textBlocks.isNullOrEmpty()) {
//            Toast.makeText(
//                baseContext,
//                "No Text detected, Please try again",
//                Toast.LENGTH_LONG
//            ).show()
//        }
//
//        text.textBlocks.forEach { textBlock ->
//            textBlock.lines.forEach { line ->
//                checkIfIbanPresent(line.text)
//            }
//        }
//    }

    private fun processResults(text: Text) {
        if (text.textBlocks.isNullOrEmpty()) {
            Toast.makeText(
                baseContext,
                "No Text detected, Please try again",
                Toast.LENGTH_LONG
            ).show()
        }

        text.textBlocks.forEach { textBlock ->
            textBlock.lines.forEach { line ->
                checkIfIbanPresent(line.text)
            }
        }
    }

    private fun checkIfIbanPresent(text: String) {
        Log.i("IBAN", text.substringAfter("FR"))

        val ibanResult = "$IBAN_PREFIX_FRANCE${text.substringAfter(IBAN_PREFIX_FRANCE)}"
        if (ibanResult.replace("\\s".toRegex(), "").matches(IBAN_PATTERN_FR)) {
            Log.i("IBAN", "iban detected")
            // retrieve result and send it to consumer
            Toast.makeText(
                baseContext,
                "IBAN correctly detected ;)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        const val IBAN_PREFIX_FRANCE = "FR"
        val IBAN_PATTERN_FR = "^${IBAN_PREFIX_FRANCE}\\d{12}[0-9A-Z]{11}\\d{2}\$".toRegex()

    }

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy) {

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }

//    private class IbanFirebaseAnalyzer(private val listener: IbanFirebaseListener) :
//        ImageAnalysis.Analyzer {
//        @SuppressLint("UnsafeExperimentalUsageError")
//        override fun analyze(proxyImage: ImageProxy) {
//            val mediaImage = proxyImage.image
//            mediaImage?.let { img ->
//                val firebaseVisionImage =
//                    FirebaseVisionImage.fromMediaImage(img, proxyImage.imageInfo.rotationDegrees)
//
//                listener(firebaseVisionImage)
//            }
//
//            proxyImage.close()
//        }
//    }

    private class IbanAnalyzer(private val listener: IbanListener) : ImageAnalysis.Analyzer {
        @SuppressLint("UnsafeExperimentalUsageError")
        override fun analyze(proxyImage: ImageProxy) {
            val mediaImage = proxyImage.image
            mediaImage?.let { img ->
                val image =
                    InputImage.fromMediaImage(img, proxyImage.imageInfo.rotationDegrees)

                listener(image, proxyImage)
            }
            //proxyImage.close()
        }
    }
}