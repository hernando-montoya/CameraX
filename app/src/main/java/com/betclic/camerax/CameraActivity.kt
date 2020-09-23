package com.betclic.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
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
        const val CAPTURE_TYPE = "CAPTURE_TYPE"
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        const val IBAN_PREFIX_FRANCE = "FR"
        val IBAN_PATTERN_FR = "^${IBAN_PREFIX_FRANCE}\\d{12}[0-9A-Z]{11}\\d{2}\$".toRegex()
        val PASSPORT_PATTERN =
            "P<(\\w{3})([A-Z]+)(<([A-Z]+))?<<([A-Z]+)".toRegex()

        val ID_PATTERN =
            "([A-Z]{1})([A-Z]{1})([A-Z]{3})([A-Z<]{25})([A-Z0-9<]{3})([0-9<]{3})".toRegex()

        fun newIntent(context: Context, captureType: CaptureType) =
            Intent(context, CameraActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(CAPTURE_TYPE, captureType)
            }
    }

    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraSelector: CameraSelector? = null
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    lateinit var overlay: Bitmap

    lateinit var viewFinderRect: Rect

    private val captureType: CaptureType by lazy { intent.getSerializableExtra(CAPTURE_TYPE) as CaptureType }

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

        if (captureType == CaptureType.IBAN) {
            camera_infos_title.text = resources.getString(R.string.app_name)
        } else {
            camera_infos_title.text = "Documents recognizer"
        }
        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()

        viewFinder.post {
            viewFinderRect = Rect(
                viewFinderWindow.left + 10,
                viewFinderWindow.top + 10,
                viewFinderWindow.right - 10,
                viewFinderWindow.bottom - 10
            )
            view_finder_background.setViewFinderRect(viewFinderRect)
        }
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
//                        val bitmap = viewFinder.bitmap ?: return@Analyzer
//                        overlay = Bitmap.createBitmap(
//                            bitmap.width,
//                            bitmap.height,
//                            Bitmap.Config.ARGB_8888
//                        )
                        image.setCropRect(viewFinderRect)
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
        val outputOptions =
            ImageCapture
                .OutputFileOptions
                .Builder(photoFile)
                .build()

        imageCapture.setCropAspectRatio(Rational(200, 126))
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

                    // show preview photo
                    baseContext.startActivity(
                        CameraPreviewActivity.newIntent(
                            baseContext,
                            savedUri.toFile().absolutePath
                        )
                    )
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
        val rotation = proxyImage.imageInfo.rotationDegrees
        mediaImage?.let { img ->
            val image =
                InputImage.fromMediaImage(img, rotation)

            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val reverseDimens = rotation == 90 || rotation == 270
                    val width = if (reverseDimens) proxyImage.height else proxyImage.width
                    val height = if (reverseDimens) proxyImage.width else proxyImage.height

                    val rectBounds = text.textBlocks.map { block ->
                        block.boundingBox?.transform(width, height)
                    }

                    // graphic_overlay.post { graphic_overlay.drawRectBounds(rectBounds as List<RectF>) }

//                    val linesPaint = Paint().apply {
//                        style = Paint.Style.FILL_AND_STROKE
//                        color = ContextCompat.getColor(applicationContext, R.color.whiteAlpha20)
//                        strokeWidth = 10f
//                    }
//                    val blocks = text.textBlocks
//                    val blocksRect = blocks.mapNotNull { it.boundingBox }
//
//                    val lines = blocks.flatMap { it.lines }
//                    val linesRect = lines.mapNotNull { it.boundingBox }
//
//                    val elements = lines.flatMap { it.elements }
//                    val elementsRect = elements.mapNotNull { it.boundingBox }
//
//                    // copy bitmap and make it mutable if necessary (optional step)
//                    // depends on how do you get your bitmap
//                    val mutableBitmap = overlay.copy(overlay.config, true)
//
//                    // draw all bounding boxes on bitmap
//                    with(Canvas(mutableBitmap)) {
//                        //blocksRect.forEach { drawRect(it, blocksPaint) }
//                        linesRect.forEach { drawRect(it, linesPaint) }
//                        //elementsRect.forEach { drawRect(it, elementsPaint) }
//                    }
//                    graphic_overlay.setImageBitmap(mutableBitmap)

                    processResults(text)
                }
                .addOnFailureListener {
                    Toast.makeText(baseContext, "Error detecting Text $it", Toast.LENGTH_LONG)
                        .show()
                }
                .addOnCompleteListener { proxyImage.close() }
        }
    }

    private fun processResults(text: Text) {
        if (text.textBlocks.isNullOrEmpty()) {
            if (captureType == CaptureType.IBAN) {
                camera_infos_description.text = "No iban detected, please try again!"
            } else {
                camera_infos_description.text = "No document detected, please try again!"
                camera_capture_button.visibility = View.INVISIBLE
            }
        }

        text.textBlocks.forEach { textBlock ->
            textBlock.lines.forEach { line ->
                Log.i("MRZ", line.text)
                if (captureType == CaptureType.IBAN) {
                    checkIfIbanPresent(line)
                } else {
                    checkIfIdCardPresent(line)
                }
            }
        }
    }

    private fun checkIfIdCardPresent(line: Text.Line) {
        if (line.text.matches(ID_PATTERN)) {
            camera_capture_button.visibility = View.VISIBLE
            camera_infos_description.text = "ID card detected"
            Log.i("MRZ", "id detected")
        }
    }

    private fun Rect.transform(width: Int, height: Int): RectF {
        val scaleX = viewFinder.width / width.toFloat()
        val scaleY = viewFinder.height / height.toFloat()

        // If the front camera lens is being used, reverse the right/left coordinates
        val flippedLeft =
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) width - right else left
        val flippedRight =
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) width - left else right

        // Scale all coordinates to match preview
        val scaledLeft = scaleX * flippedLeft
        val scaledTop = scaleY * top
        val scaledRight = scaleX * flippedRight
        val scaledBottom = scaleY * bottom
        return RectF(scaledLeft, scaledTop, scaledRight, scaledBottom)
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