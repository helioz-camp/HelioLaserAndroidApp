package xyz.helioz.heliolaser

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.CamcorderProfile
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info

abstract class HelioCameraInterface {
    abstract fun disposeCamera(): Unit
    abstract fun lockFocusAllSettings(): Unit
    abstract fun flashlight(on: Boolean): Unit
    // as android.util.Size was added in API 21
    abstract fun previewTextureSize(): Pair<Int, Int>
}

object HelioCameraSource : AnkoLogger, CoroutineScope {
    val cameraThread by lazy { HandlerThread(javaClass.canonicalName).apply { start() } }
    val cameraHandler by lazy { Handler(cameraThread.looper) }
    var cameraInterface: HelioCameraInterface? = null

    fun prepareCameraAsync(previewTexture: SurfaceTexture) {
        launch {
            tryOrContinue {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cameraInterface = HelioCamera2Setup().cameraStart(previewTexture)
                    return@launch
                }
            }
            tryOrContinue {
                cameraInterface = prepareCamera1SourceAsync(previewTexture)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun prepareCamera1SourceAsync(cameraTexture: SurfaceTexture): HelioCameraInterface {
        var bestCamera = -1
        var info: Camera.CameraInfo? = null
        val camera: Camera
        var camcorderProfile: CamcorderProfile? = null
        var cameraId: Int
        tryOrContinue {
            info = Camera.CameraInfo()
            for (camId in 0 until Camera.getNumberOfCameras()) {
                bestCamera = camId
                Camera.getCameraInfo(camId, info)

                if (info?.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    break
                }
            }
        }
        if (bestCamera < 0 || info == null) {
            throw RuntimeException("prepareCamera1SourceAsync noCameras")
        } else {
            camera = Camera.open(bestCamera)
            cameraId = bestCamera
            info("opened camera $camera with $info params ${camera.parameters.flatten()}")
        }

        fun doWithCameraParams(callback: (params: Camera.Parameters) -> Unit) {
            tryOrContinue {
                val params = camera.parameters
                callback(params)
                camera.parameters = params
            }
        }

        tryOrContinue {
            camcorderProfile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_480P)
        }

        tryOrContinue {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                camcorderProfile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_LOW)
            }
        }

        tryOrContinue {
            camcorderProfile?.let {
                info { "prepareCameraTexture expecting profile with frame rate ${it.videoFrameRate} resolution ${it.videoFrameWidth}x${it.videoFrameHeight}" }
                doWithCameraParams { params ->
                    params.setPreviewSize(it.videoFrameWidth, it.videoFrameHeight)
                }
            }
        }

        doWithCameraParams { params ->
            // receive at top speed
            var bestMaxFpsMillis = params.supportedPreviewFpsRange[0][1]
            var bestMinFpsMillis = params.supportedPreviewFpsRange[0][0]
            for (range in params.supportedPreviewFpsRange) {
                bestMaxFpsMillis = max(range[1], bestMaxFpsMillis)
            }
            for (range in params.supportedPreviewFpsRange) {
                if (range[1] == bestMaxFpsMillis) {
                    bestMinFpsMillis = max(range[0], bestMinFpsMillis)
                }
            }
            params.setPreviewFpsRange(bestMinFpsMillis, bestMaxFpsMillis)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
            if (HelioPref("camera_video_stabilization", false)) {
                doWithCameraParams { params ->
                    if (params.isVideoStabilizationSupported) {
                        params.videoStabilization = true
                    }
                }
            }
        }
        doWithCameraParams { params ->
            if (HelioPref("camera_set_recording_hint", true)) {
                params.setRecordingHint(true)
            }
        }

        camera.setPreviewTexture(cameraTexture)
        info { "prepareCameraTexture camera preview requested for $this; ${camera.parameters.flatten()}" }
        tryOrContinue {
            camera.startPreview()
        }
        tryOrContinue {
            camera.autoFocus { success, _ ->
                info { "prepareCameraTextureAutofocus camera $this autofocus $success" }
            }
        }
        return object : HelioCameraInterface() {
            override fun disposeCamera() {
                tryOrContinue {
                    camera?.release()
                }
            }

            override fun lockFocusAllSettings() {
                doWithCameraParams { params ->
                    if (params.isAutoExposureLockSupported) {
                        params.autoExposureLock = true
                    }
                }
                doWithCameraParams { params ->
                    params.autoWhiteBalanceLock = true
                }
            }

            override fun flashlight(on: Boolean) {
                doWithCameraParams { params ->
                    params.flashMode = if (on) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
                }
            }

            override fun previewTextureSize(): Pair<Int, Int> {
                return Pair(
                        camera.parameters.previewSize.width,
                        camera.parameters.previewSize.height
                )
            }
        }
    }

    override val coroutineContext: CoroutineContext by lazy {
        cameraHandler.asCoroutineDispatcher(cameraThread.name)
    }
}
