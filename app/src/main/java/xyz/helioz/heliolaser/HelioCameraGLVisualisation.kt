package xyz.helioz.heliolaser

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.Parameters.FLASH_MODE_OFF
import android.hardware.Camera.Parameters.FLASH_MODE_TORCH
import android.opengl.GLES11Ext
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20
import android.opengl.GLES20.*
import android.view.MotionEvent
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.info
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

class HelioCameraGLVisualisation : AnkoLogger {
    var simpleSquareBufferIndex : Int = -1
    var openGLTexture : Int = -1
    val visualisationProgram by lazy {
        HelioGLProgram()
    }
    var cameraTexture:SurfaceTexture? = null
    var previewSize:Camera.Size? = null
    var camera:Camera? = null
    val sampleDimensionPixels = 4
    val sampleBuffer = ByteBuffer.allocateDirect(sampleDimensionPixels * sampleDimensionPixels * 4).order(ByteOrder.nativeOrder())!!
    val loudnessFilter = HelioMorseCodec.LoudnessFilter(
            HelioPref("loudness_filter_estimated_hz", 60.0),
            HelioPref("loudness_filter_seconds_lookback", 10.0))
    val background = FloatArray(3)

    fun prepareVisualisationShaders(helioGLRenderer: HelioGLRenderer) {
        visualisationProgram.openGLCompileAndAttachShader(GL_VERTEX_SHADER,
"""
precision mediump float;

attribute vec2 position;
void main() {
   gl_Position = vec4(position, 0.0, 1.0);
}
""")
        visualisationProgram.openGLCompileAndAttachShader(GL_FRAGMENT_SHADER,
                """
#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform vec2 previewSize;
uniform vec2 resolution;
uniform vec3 background;
uniform float wobble;
uniform samplerExternalOES textureSamplerForFragmentShader;
varying mediump vec2 textureCoordinateForFragmentShader;

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.,2./3.,1./3.)) * 6.0 - vec3(3));
    return c.z * mix(vec3(1), clamp(p - vec3(1), 0.0, 1.0), c.y);
}

void main() {
  vec2 frag_coord = vec2(gl_FragCoord.x/resolution.x, gl_FragCoord.y/resolution.y);
  vec2 textureCoordinateForFragmentShader = vec2(
      (1.0 - frag_coord.y) * (previewSize.y/previewSize.x),
      (1.0 - frag_coord.x));
  vec2 from_center = 2.0 * (frag_coord - vec2(0.5, 0.5));
  float distance = dot(from_center, from_center);
  float blend = clamp(distance*distance*distance*distance + 0.1, 0.95, 1.0);
  vec3 cameraTextureValue =
	0.5 * texture2D(textureSamplerForFragmentShader, textureCoordinateForFragmentShader + vec2(0.002, 0.0)).xyz
       + 0.5 * texture2D(textureSamplerForFragmentShader, textureCoordinateForFragmentShader + vec2(-0.002, 0.0)).xyz;
  vec3 camBlend = mix(cameraTextureValue.yyy, background, blend);
  vec2 uv = gl_FragCoord.xy / resolution.xx - vec2(.5,0);

  float h = length(uv)*(resolution.x/resolution.y * 2.2) -1. + sin(uv.x * 10. + wobble) * .03;

  vec3 rgb = camBlend;

  if (h > 0. && h < 1.) {
     float inverse_h = 1. - h;
     float hue = .85*(mix(inverse_h, 1. - (1. - inverse_h*inverse_h), .5));
     float sat = 1.;
     float lum = clamp(atan(uv.y, uv.x) + .2, 0., 1.);
     rgb = hsv2rgb(vec3(hue,sat,lum));
     rgb = mix(camBlend, rgb,  clamp(min(h, 1.-h)*100., 0., 0.3));
  }

  gl_FragColor = vec4(rgb, 1. );
}
""")
        visualisationProgram.openGLLinkAllShaders()
        simpleSquareBufferIndex = visualisationProgram.openGLTransferFloatsToGPUHandle(floatArrayOf(
                -1f,-1f,
                -1f,+1f,
                +1f,+1f,
                +1f,+1f,
                +1f,-1f,
                -1f,-1f
                ))
        openGLTexture = helioGLRenderer.allocateGLTexture()

        Thread({
            val info = Camera.CameraInfo()
            var bestCamera = -1
            for (camId in 0 until Camera.getNumberOfCameras()) {
                bestCamera = camId
                Camera.getCameraInfo(camId, info)

                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    break
                }
            }
            if (bestCamera < 0) {
                info("no cameras")
            }
            val cam = Camera.open(bestCamera)
            info("opened camera $cam with $info params ${cam.parameters.flatten()}")
            helioGLRenderer.backgroundGLAction {
                prepareCameraTexture(helioGLRenderer, cam, info)
            }
        }, "camera open thread").start()

        background.fill(1f)
    }

    fun releaseVisualisationResources() {
        tryOrContinue {
            camera?.release()
        }
        camera = null
        tryOrContinue {
            cameraTexture?.release()
        }
        cameraTexture = null

    }

    fun renderVisualisationFrame(helioGLRenderer: HelioGLRenderer) {
        checkedGL {
            glClearColor(background[0], background[1], background[2], 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        }
        checkedGL {
            visualisationProgram.openGLAttachProgram()
            visualisationProgram.openGLSetUniformFloat("wobble", floatArrayOf(
                    ((System.currentTimeMillis()*0.001) % (2.0* Math.PI)).toFloat()))
            visualisationProgram.openGLSetUniformFloat("previewSize",
                    floatArrayOf(previewSize?.width?.toFloat() ?: 0f, previewSize?.height?.toFloat() ?: 0f))
            visualisationProgram.openGLSetUniformFloat("resolution",
                    floatArrayOf(helioGLRenderer.surfaceWidth.toFloat(), helioGLRenderer.surfaceHeight.toFloat()))
            visualisationProgram.openGLSetUniformInt("textureSamplerForFragmentShader", 0)
            visualisationProgram.openGLSetUniformFloat("background", background)
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, openGLTexture)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

            visualisationProgram.openGLShadeVerticesFromBuffer(
                    "position",
                    simpleSquareBufferIndex,
                    GL_TRIANGLES,
                    2,
                    6
            )

            glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)
        }

        checkedGL {
            sampleBuffer.clear()
            glReadPixels(
                    helioGLRenderer.surfaceWidth/2 - sampleDimensionPixels/2,
                    helioGLRenderer.surfaceHeight/2 - sampleDimensionPixels/2,
                    sampleDimensionPixels,
                    sampleDimensionPixels,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    sampleBuffer)

            val sampleCount = sampleBuffer.capacity()/4 - 1
            val greens = IntArray(sampleCount)
            for (i in greens.indices) {
                greens[i] = sampleBuffer[i * 4 + 1].toInt() and 0xff
            }
            greens.sort()

            val amp = greens[sampleCount/3].toFloat() + greens[sampleCount/2].toFloat() + greens[(2*sampleCount)/3].toFloat()
            val now = HelioLaserApplication.helioLaserApplicationInstance?.applicationAgeSeconds
            loudnessFilter.addAmplitude(amp)
            val filtered = loudnessFilter.loudnessEstimate
            val threshold = loudnessFilter.filterThresholdEstimate
            doAsync {
                info("HelioCamera Measurement: $now,$amp,$filtered,$threshold")
            }
        }
    }

    fun flipVisualisationCamera(motionEvent: MotionEvent) {
            tryOrContinue {
                val params = camera?.parameters
                params?.autoExposureLock = true
                camera?.parameters = params
            }
            tryOrContinue {
                val params = camera?.parameters
                params?.autoWhiteBalanceLock = true
                camera?.parameters = params
            }

    }

    fun prepareCameraTexture(helioGLRenderer: HelioGLRenderer, camera: Camera, info: Camera.CameraInfo) {
        checkedGL {
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, openGLTexture)
        }
        val cameraFrameCount = AtomicLong()
        cameraTexture = SurfaceTexture(openGLTexture)
        cameraTexture?.setOnFrameAvailableListener { surfaceTexture ->
            if (cameraFrameCount.getAndIncrement() == 0L) {
                info { "camera $this first onFrameAvailable params ${camera.parameters.flatten()}"}
            }
            helioGLRenderer.surfacesTexturesToUpdateBeforeDrawing.add(surfaceTexture)
        }
        camera.setPreviewTexture(cameraTexture)
        previewSize = camera.parameters.previewSize
        tryOrContinue {
            camera.autoFocus { success, camera -> info { "camera $this autofocus $success" } }
        }
        info { "camera preview requested for $this" }
        camera.startPreview()
        this.camera = camera
    }


    fun morseSignalOn() {
        background.fill(0f)

        camera?.let {
            val params = it.parameters
            params.flashMode = FLASH_MODE_TORCH
            it.parameters = params
        }
    }

    fun morseSignalOff() {
        background.fill(1f)

        camera?.let {
            val params = it.parameters
            params.flashMode = FLASH_MODE_OFF
            it.parameters = params
        }
    }


}