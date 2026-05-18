package com.example.rawcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.ImageFormat.YUV_420_888
import android.graphics.ImageFormat.JPEG
import android.media.ImageReader

import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.DngCreator
import android.media.Image
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.multi.MultiplePermissionsListener

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var captureButton: ImageButton
    private lateinit var modeButton: Button

    private lateinit var isoSeekBar: SeekBar
    private lateinit var shutterSeekBar: SeekBar
    private lateinit var focusSeekBar: SeekBar

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession

    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraCharacteristics: CameraCharacteristics
    private lateinit var cameraId: String

    private lateinit var rawImageReader: ImageReader
    private lateinit var yuvImageReader: ImageReader
    private var lastCaptureResult: TotalCaptureResult? = null

    // Manual control values
    private var isoValue: Int = 100
    private var exposureTimeNs: Long = 100_000_000L
    private var focusDistance: Float = 0f

    // Capture mode
    enum class CaptureMode { RAW, PNG }
    private var captureMode: CaptureMode = CaptureMode.RAW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        captureButton = findViewById(R.id.captureBtn)
        modeButton = findViewById(R.id.modeButton)

        isoSeekBar = findViewById(R.id.isoSeekBar)
        shutterSeekBar = findViewById(R.id.shutterSeekBar)
        focusSeekBar = findViewById(R.id.focusSeekBar)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        requestPermissions()
        textureView.surfaceTextureListener = textureListener

        captureButton.setOnClickListener { captureImage() }

        modeButton.setOnClickListener {
            // Toggle mode
            captureMode = if (captureMode == CaptureMode.RAW) CaptureMode.PNG else CaptureMode.RAW
            modeButton.text = if (captureMode == CaptureMode.RAW) "Mode: RAW" else "Mode: PNG"
        }

        setupSliders()
    }

    private fun setupSliders() {
        isoSeekBar.max = 1600
        isoSeekBar.progress = isoValue
        isoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                isoValue = progress
                updateManualSettings()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        shutterSeekBar.max = 2000
        shutterSeekBar.progress = (exposureTimeNs / 1_000_000).toInt()
        shutterSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                exposureTimeNs = progress * 1_000_000L
                updateManualSettings()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        focusSeekBar.max = 1000
        focusSeekBar.progress = (focusDistance * 100).toInt()
        focusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                focusDistance = progress / 100f
                updateManualSettings()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateManualSettings() {
        if (::captureSession.isInitialized && ::previewRequestBuilder.isInitialized) {
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
            previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            previewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null)
        }
    }

    private fun setupCamera() {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = id
                cameraCharacteristics = characteristics

                // RAW reader
                val rawSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.first()
                if (rawSize != null) {
                    rawImageReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 5)
                }

                // YUV reader for PNG
                val yuvSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(YUV_420_888)?.first()
                if (yuvSize != null) {
                    yuvImageReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, YUV_420_888, 5)
                }

                val manualSupported = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) ?: false
                Log.i("CameraCheck", "Camera ID: $cameraId, Manual sensor supported: $manualSupported")
                return
            }
        }
        Toast.makeText(this, "RAW not supported", Toast.LENGTH_LONG).show()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }

        Dexter.withContext(this)
            .withPermissions(permissions)
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                    if (report.grantedPermissionResponses.any { it.permissionName == Manifest.permission.CAMERA }) {
                        setupCamera()
                        if (textureView.isAvailable && ::cameraId.isInitialized) openCamera()
                    } else {
                        Toast.makeText(this@MainActivity, "Camera permission required!", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onPermissionRationaleShouldBeShown(
                    permissions: MutableList<com.karumi.dexter.listener.PermissionRequest>,
                    token: PermissionToken
                ) { token.continuePermissionRequest() }
            }).check()
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (::cameraId.isInitialized) openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startCameraPreview()
            }
            override fun onDisconnected(camera: CameraDevice) {}
            override fun onError(camera: CameraDevice, error: Int) {}
        }, null)
    }

    private fun startCameraPreview() {
        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(surfaceTexture)

        val outputs = mutableListOf<Surface>(previewSurface)
        if (::rawImageReader.isInitialized) outputs.add(rawImageReader.surface)
        if (::yuvImageReader.isInitialized) outputs.add(yuvImageReader.surface)

        previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder.addTarget(previewSurface)

        cameraDevice.createCaptureSession(outputs, object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                updateManualSettings()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Toast.makeText(applicationContext, "Preview failed", Toast.LENGTH_SHORT).show()
            }
        }, null)
    }

    private fun captureImage() {
        when(captureMode) {
            CaptureMode.RAW -> captureRawImage()
            CaptureMode.PNG -> capturePngImage()
        }
    }

    private fun captureRawImage() {
        val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureBuilder.addTarget(rawImageReader.surface)
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
        captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
        captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
        captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                lastCaptureResult = result
            }
        }

        rawImageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            val result = lastCaptureResult
            if (result != null) {
                val filename = "RAW_${System.currentTimeMillis()}.dng"
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/dng")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawCameraApp")
                }
                contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { output ->
                        DngCreator(cameraCharacteristics, result).use { dngCreator ->
                            dngCreator.writeImage(output, image)
                        }
                    }
                    Toast.makeText(this, "RAW Saved: $filename", Toast.LENGTH_LONG).show()
                }
            }
            image.close()
        }, null)

        captureSession.capture(captureBuilder.build(), captureCallback, null)
    }

    private fun capturePngImage() {
        val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureBuilder.addTarget(yuvImageReader.surface)
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
        captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
        captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
        captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)

        yuvImageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            val bitmap = yuvToBitmap(image)
            image.close()

            val filename = "IMG_${System.currentTimeMillis()}.png"
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawCameraApp")
            }

            contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                Toast.makeText(this, "PNG Saved: $filename", Toast.LENGTH_LONG).show()
            }
        }, null)

        captureSession.capture(captureBuilder.build(), null, null)
    }

    // Helper: Convert YUV_420_888 to Bitmap
    private fun yuvToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val argb = IntArray(width * height)
        var yp = 0

        for (j in 0 until height) {
            val pY = yRowStride * j
            val pUV = uvRowStride * (j / 2)
            for (i in 0 until width) {
                val y = (yBuffer.get(pY + i).toInt() and 0xff)
                val u = (uBuffer.get(pUV + (i / 2) * uvPixelStride).toInt() and 0xff) - 128
                val v = (vBuffer.get(pUV + (i / 2) * uvPixelStride).toInt() and 0xff) - 128

                var r = (y + 1.402 * v).toInt()
                var g = (y - 0.344136 * u - 0.714136 * v).toInt()
                var b = (y + 1.772 * u).toInt()

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                argb[yp++] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        return bitmap
    }

}
