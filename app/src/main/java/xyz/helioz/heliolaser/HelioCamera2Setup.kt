package xyz.helioz.heliolaser

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info
import org.jetbrains.anko.warn

@RequiresApi(Build.VERSION_CODES.M)
@TargetApi(Build.VERSION_CODES.M)
class HelioCamera2Setup() : AnkoLogger {
    companion object {
        data class HelioCamera2Config(
            val camera2Id: String,
            val lensFacing: Int?,
            val fpsEstimate: Double,
            val cameraCapturePixels: Size,
            val highSpeed: Boolean
        ) : Comparable<HelioCamera2Config> {
            fun minSizeDimensionPixels(): Int {
                return min(cameraCapturePixels.height, cameraCapturePixels.width)
            }

            override fun compareTo(other: HelioCamera2Config): Int {
                if (other.lensFacing == CameraCharacteristics.LENS_FACING_FRONT || lensFacing != CameraCharacteristics.LENS_FACING_FRONT) {
                    return 1
                }
                if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT && other.lensFacing != CameraCharacteristics.LENS_FACING_FRONT) {
                    return -1
                }
                val fpsCompare = fpsEstimate.compareTo(other.fpsEstimate)
                if (fpsCompare != 0) {
                    return fpsCompare
                }
                val sizeCompare = minSizeDimensionPixels().compareTo(other.minSizeDimensionPixels())
                if (sizeCompare != 0) {
                    return sizeCompare
                }
                val highSpeedCompare = highSpeed.compareTo(other.highSpeed)
                if (highSpeedCompare != 0) {
                    return -highSpeedCompare
                }
                return camera2Id.compareTo(other.camera2Id)
            }
        }
    }

    private val cameraManager: CameraManager by lazy {
        val context = HelioLaserApplication.helioLaserApplicationInstance!!
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    private val recorderSurface: Surface by lazy {

        val surface = MediaCodec.createPersistentInputSurface()

        createRecorder(surface).apply {
            prepare()
            release()
        }

        surface
    }

    private fun createRecorder(surface: Surface) = MediaRecorder().apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setInputSurface(surface)
    }

    val camera2Config: HelioCamera2Config? by lazy {
        var bestConfig: HelioCamera2Config? = null
        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
            val cameraConfig = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            fun considerThisConfig(highSpeed: Boolean, size: Size, fps: Double) {
                val config = HelioCamera2Config(
                        camera2Id = id, lensFacing = lensFacing, highSpeed = highSpeed, cameraCapturePixels = size, fpsEstimate = fps
                )
                val previousBestConfig = bestConfig
                if (previousBestConfig == null || previousBestConfig < config) {
                    info { "HelioCamera2Setup choosing $config over $previousBestConfig" }
                    bestConfig = config
                }
            }
            if (capabilities.contains(CameraCharacteristics
                            .REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                for (size in cameraConfig.highSpeedVideoSizes) {
                    for (fpsRange in cameraConfig.getHighSpeedVideoFpsRangesFor(size)) {
                        val fps = fpsRange.upper
                        considerThisConfig(highSpeed = true, fps = fps.toDouble(), size = size)
                    }
                }
            }
            val format = SurfaceHolder::class.java
            for (size in cameraConfig.getOutputSizes(format)) {
                // according to documentation, some cameras may not fill in enough information for minFrameNanos to be meaningful
                val minFrameNanos = cameraConfig.getOutputMinFrameDuration(format, size) + cameraConfig.getOutputStallDuration(format, size)
                val fps = if (minFrameNanos > 0) 1e9 / minFrameNanos.toDouble() else 0.0
                considerThisConfig(highSpeed = false, fps = fps, size = size)
            }
        }
        bestConfig
    }

    @SuppressLint("MissingPermission")
    suspend fun openCameraDevice(): CameraDevice = suspendCancellableCoroutine { cont ->
        cameraManager.openCamera(camera2Config!!.camera2Id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                info { "HelioCamera2Setup.openCameraDevice.onOpened camera $camera2Config $device" }
                cont.resume(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                warn { "HelioCamera2Setup.openCameraDevice.onDisconnected camera $camera2Config " }
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
                    ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
                    ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
                    ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
                    ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
                    else -> ""
                }
                val exc = RuntimeException("HelioCamera2Setup.openCameraDevice.onError camera $camera2Config error: ($error) $msg")
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, HelioCameraSource.cameraHandler)
    }

    suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>
    ): CameraCaptureSession = suspendCoroutine { cont ->
        fun tryRegularSession() {
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    cont.resumeWithException(RuntimeException("HelioCamera2Setup.createCaptureSession onConfigureFailed"))
                }

                override fun onConfigured(session: CameraCaptureSession) {
                    cont.resume(session)
                }
            }, HelioCameraSource.cameraHandler)
        }
        if (targets.size < 2) {
            tryRegularSession()
        } else {
            try {
                device.createConstrainedHighSpeedCaptureSession(
                        targets, object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(session: CameraCaptureSession) {
                        cont.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        warn { "HelioCamera2Setup.createConstrainedHighSpeedCaptureSession onConfigureFailed" }
                        tryRegularSession()
                    }
                }, HelioCameraSource.cameraHandler)
            } catch (e: Exception) {
                warn("HelioCamera2Setup.createConstrainedHighSpeedCaptureSession failed", e)
                tryRegularSession()
            }
        }
    }

    fun captureRequestBuilder(cameraDevice: CameraDevice): CaptureRequest.Builder {
        return cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(min(30 /*PREVIEW_FPS*/, camera2Config!!.fpsEstimate.toInt()), camera2Config!!.fpsEstimate.toInt()))
        }
    }

    suspend fun cameraStart(previewTexture: SurfaceTexture): HelioCameraInterface {
        val previewSurface = Surface(previewTexture)
        previewTexture.setDefaultBufferSize(camera2Config!!.cameraCapturePixels.width, camera2Config!!.cameraCapturePixels.height)
        val surfaces = ArrayList<Surface>().apply {
            add(previewSurface)
        }
        val mediaRecorder = MediaRecorder()

        tryOrContinue {
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            val config = camera2Config!!
            tryOrContinue {
                val camcorderProfile = CamcorderProfile.get(config.camera2Id.toInt(), CamcorderProfile.QUALITY_HIGH_SPEED_HIGH)
                mediaRecorder.setProfile(camcorderProfile)
            }
            mediaRecorder.apply {
                setVideoFrameRate(config.fpsEstimate.toInt())
                setVideoSize(config.cameraCapturePixels.width, config.cameraCapturePixels.height)
                setVideoEncodingBitRate(
                        (config.cameraCapturePixels.width * config.cameraCapturePixels.height * config.fpsEstimate * HelioPref("camera_video_bitrate_bits_per_pixel_minimum", 0.4)).toInt()
                )
            }
            val outputFileName: String = (HelioLaserApplication.helioLaserApplicationInstance?.getExternalFilesDir(Environment.DIRECTORY_DCIM)?.toString()
                    ?: "") + "/morseRecording-${System.currentTimeMillis() / 1000}.vid"
            mediaRecorder.setOutputFile(outputFileName)
            info { "HelioCamera2Setup recording to $outputFileName" }
            mediaRecorder.prepare()
            mediaRecorder.start()
            surfaces.add(mediaRecorder.surface)
        }

        val setup = HelioCamera2Setup()
        val camera = setup.openCameraDevice()
        val session = setup.createCaptureSession(camera, surfaces)
        val captureRequestBuilder = setup.captureRequestBuilder(camera)
        for (surface in surfaces) {
            captureRequestBuilder.addTarget(surface)
        }

        fun makeRepeatingRequests() {
            tryOrContinue {
                session.stopRepeating() // TODO use onReadyCallback here
            }
            val captureRequest = captureRequestBuilder.build()
            val requestList = if (session is CameraConstrainedHighSpeedCaptureSession) {
                session.createHighSpeedRequestList(captureRequest)
            } else {
                ArrayList<CaptureRequest>().apply {
                    add(captureRequest)
                }
            }
            session.setRepeatingBurst(requestList, null, HelioCameraSource.cameraHandler)
        }
        makeRepeatingRequests()

        return object : HelioCameraInterface() {
            override fun disposeCamera() {
                tryOrContinue {
                    session.stopRepeating()
                }
                tryOrContinue {
                    camera.close()
                }
            }

            override fun lockFocusAllSettings() {
                captureRequestBuilder.apply {
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                    set(CaptureRequest.BLACK_LEVEL_LOCK, true)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                }
                makeRepeatingRequests()
            }

            override fun flashlight(on: Boolean) {
                HelioCameraSource.cameraHandler.post {
                    tryOrContinue {
                        captureRequestBuilder.removeTarget(mediaRecorder.surface)
                        surfaces.remove(mediaRecorder.surface)
                    }
                    tryOrContinue {
                       captureRequestBuilder.apply {
                            set(CaptureRequest.FLASH_MODE, if (on) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
                        }
                        makeRepeatingRequests()
                    }
                    tryOrContinue {
                        mediaRecorder.stop()
                    }
                    tryOrContinue {
                        session.close()
                    }
                    tryOrContinue {
                        camera.close()
                    }
                    tryOrContinue {
                        cameraManager.setTorchMode(camera2Config!!.camera2Id, on)
                    }
                }
            }

            override fun previewTextureSize(): Pair<Int, Int> {
                return Pair(camera2Config!!.cameraCapturePixels.width, camera2Config!!.cameraCapturePixels.height)
            }
        }
    }
}
