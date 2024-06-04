package com.example.yolov8deployment

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.yolov8deployment.Constants.LABELS_PATH
import com.example.yolov8deployment.Constants.MODEL_PATH
import com.example.yolov8deployment.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class YoloActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private lateinit var imageCapture: ImageCapture
//    private lateinit var imageView: ImageView

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        imageCapture = ImageCapture.Builder().build()

        binding.buttonTakePhoto.setOnClickListener {
            takePhoto()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview =  Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

//        imageCapture = ImageCapture.Builder().build()

        cameraProvider.unbindAll()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // Crucial change!
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )

            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width / 2f, imageProxy.height / 2f)
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )
            detector.detect(rotatedBitmap)
        }

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)

            binding.buttonTakePhoto.setOnClickListener { takePhoto() }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    @OptIn(ExperimentalGetImage::class) // Opt-in annotation
    private fun takePhoto() {
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                try {
                    val image = imageProxy.image ?: return // Handle null image

                    val width = imageProxy.width
                    val height = imageProxy.height

                    // Rotate the image buffer if needed
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val matrix = Matrix()
                    if (rotationDegrees != 0) {
                        matrix.postRotate(rotationDegrees.toFloat())
                    }

                    // Create a rotated Bitmap
                    var rotatedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    rotatedBitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer) // Directly copy pixels
                    if (rotationDegrees != 0) {
                        rotatedBitmap = Bitmap.createBitmap(
                            rotatedBitmap,
                            0,
                            0,
                            rotatedBitmap.width,
                            rotatedBitmap.height,
                            matrix,
                            true
                        )
                    }

                    detector.detect(rotatedBitmap)

                } catch (e: Exception) {
                    Log.e(TAG, "Error during image capture and conversion: ", e)
                    // Handle the exception (e.g., show a toast message)
                } finally {
                    imageProxy.close() // Always close the ImageProxy to avoid memory leaks
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(
                    this@YoloActivity,
                    "Failed to take picture, ensure camera is enabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }





    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        binding.overlay.invalidate()
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }
    }
}