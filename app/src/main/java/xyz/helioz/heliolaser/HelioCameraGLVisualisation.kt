package xyz.helioz.heliolaser

import android.graphics.SurfaceTexture
import android.media.MediaRecorder
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20
import android.opengl.GLES20.GL_CLAMP_TO_EDGE
import android.opengl.GLES20.GL_COLOR_BUFFER_BIT
import android.opengl.GLES20.GL_DEPTH_BUFFER_BIT
import android.opengl.GLES20.GL_FRAGMENT_SHADER
import android.opengl.GLES20.GL_LINEAR
import android.opengl.GLES20.GL_TEXTURE0
import android.opengl.GLES20.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES20.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES20.GL_TEXTURE_WRAP_S
import android.opengl.GLES20.GL_TEXTURE_WRAP_T
import android.opengl.GLES20.GL_TRIANGLES
import android.opengl.GLES20.GL_VERTEX_SHADER
import android.opengl.GLES20.glActiveTexture
import android.opengl.GLES20.glBindTexture
import android.opengl.GLES20.glClear
import android.opengl.GLES20.glClearColor
import android.opengl.GLES20.glTexParameteri
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info

class HelioCameraGLVisualisation : AnkoLogger {
    var fullScreenBufferIndex: Int = -1
    var openGLTexture: Int = -1
    val visualisationProgram by lazy {
        HelioGLProgram()
    }
    val cameraDownsampledProgram by lazy {
        HelioGLProgram()
    }
    var cameraTexture: SurfaceTexture? = null
    var mediaRecorder: MediaRecorder? = null
    val sampleDimensionPixels = 4
    val sampleBuffer = ByteBuffer.allocateDirect(sampleDimensionPixels * sampleDimensionPixels * 4).order(ByteOrder.nativeOrder())!!
    val background = FloatArray(3)

    fun prepareVisualisationShaders(helioGLRenderer: HelioGLRenderer) {
        info { "GL_VERSION ${GLES20.glGetString(GLES20.GL_VERSION)}" }

        cameraDownsampledProgram.openGLCompileAndAttachShader(GL_VERTEX_SHADER, """
    precision mediump float;
    
    attribute vec2 position;
    uniform vec2 resolution;
    uniform float proportionDownsampleSquare;

    void main() {
       float ratio = resolution.x/resolution.y;
    
       gl_Position = vec4(position.x*proportionDownsampleSquare -1. + proportionDownsampleSquare, position.y*proportionDownsampleSquare*ratio + 1. - proportionDownsampleSquare*ratio, 0.0, 1.0);
    }
    """)
        cameraDownsampledProgram.openGLCompileAndAttachShader(GL_FRAGMENT_SHADER, """
#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES textureSamplerForFragmentShader;
            
void main() {
  vec4 c = texture2D(textureSamplerForFragmentShader, vec2(0.5,0.5));
  float brightness = 0.2126*c.r + 0.7152*c.g + 0.0722*c.b;
  gl_FragColor = vec4(brightness, brightness, brightness, brightness); 
}
            
        """)
        cameraDownsampledProgram.openGLLinkAllShaders()

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
  float blend = clamp(distance*distance*distance*distance + 0.1, 0.9, 1.0);
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
        fullScreenBufferIndex = visualisationProgram.openGLTransferFloatsToGPUHandle(floatArrayOf(
                -1f, -1f,
                -1f, +1f,
                +1f, +1f,
                +1f, +1f,
                +1f, -1f,
                -1f, -1f
        ))
        openGLTexture = helioGLRenderer.allocateGLTexture()

        background.fill(1f)
    }

    fun releaseVisualisationResources() {
        tryOrContinue {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        }
        tryOrContinue {
            HelioCameraSource.cameraInterface?.disposeCamera()
            HelioCameraSource.cameraInterface = null
        }
        tryOrContinue {
            visualisationProgram.openGLDeleteProgram()
        }
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
                    ((System.currentTimeMillis() * 0.001) % (2.0 * Math.PI)).toFloat()))
            val pixels = HelioCameraSource.cameraInterface?.previewTextureSize()
            visualisationProgram.openGLSetUniformFloat("previewSize",
                    floatArrayOf(pixels?.first?.toFloat() ?: 0f, pixels?.second?.toFloat()
                            ?: 0f))
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
        }

        checkedGL {
            visualisationProgram.openGLShadeVerticesFromBuffer(
                    "position",
                    fullScreenBufferIndex,
                    GL_TRIANGLES,
                    2,
                    6
            )
        }
// this never works; GL error 0x502        checkedGL { glGenerateMipmap(GL_TEXTURE_2D) }

        checkedGL {
            cameraDownsampledProgram.openGLAttachProgram()
            cameraDownsampledProgram.openGLSetUniformFloat("proportionDownsampleSquare", floatArrayOf(0.1f))
            cameraDownsampledProgram.openGLSetUniformFloat("resolution",
                    floatArrayOf(helioGLRenderer.surfaceWidth.toFloat(), helioGLRenderer.surfaceHeight.toFloat()))

            cameraDownsampledProgram.openGLShadeVerticesFromBuffer(
                    "position",
                    fullScreenBufferIndex,
                    GL_TRIANGLES,
                    2,
                    6
            )
        }

        checkedGL {
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0)
        }
    }

    fun flipVisualisationCamera(motionEvent: MotionEvent) {
        tryOrContinue {
            HelioCameraSource.cameraInterface?.lockFocusAllSettings()
        }
    }

    fun prepareCameraTexture(helioGLRenderer: HelioGLRenderer) {
        checkedGL {
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, openGLTexture)
        }
        val cameraFrameCount = AtomicLong()
        val texture = SurfaceTexture(openGLTexture)
        texture.setOnFrameAvailableListener { surfaceTexture ->
            if (cameraFrameCount.getAndIncrement() == 0L) {
                info { "camera $this first onFrameAvailable" }
            }
            synchronized(helioGLRenderer.surfacesTexturesToUpdateBeforeDrawingLock) {
                helioGLRenderer.surfacesTexturesToUpdateBeforeDrawing.add(surfaceTexture)
            }
        }
        cameraTexture = texture
        HelioCameraSource.prepareCameraAsync(texture)
    }

    fun stopRecording() {
        tryOrContinue {
            requireMainThread()
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    /*
    fun startRecording() {
        requireMainThread()
        val recorder = MediaRecorder()
        recorder.setOnErrorListener({ mr, what, extra ->
            info { "MediaRecorder OnErrorListener $what $extra" }
        })
        recorder.setOnInfoListener { mr, what, extra ->
            info { "MediaRecorder OnInfoListener $what $extra" }
        }
        tryOrContinue {
            camera?.lock()
            camera?.unlock()
        }
        recorder.setCamera(camera)
        mediaRecorder = recorder
        doAsync {
            tryOrContinue {
                continueMediaRecorderSetup(recorder)
            }
        }
    }

    fun continueMediaRecorderSetup(recorder: MediaRecorder) {
        recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA)
        tryOrContinue {
            recorder.setProfile(camcorderProfile)
        }
        tryOrContinue {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && camcorderProfile?.videoCodec == MediaRecorder.VideoEncoder.H264) {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
            }
        }
        val cameraSize = previewSize!!
        val frameRate = max(floor(cameraFps).toInt(),camcorderProfile?.videoFrameRate ?: 0)
        tryOrContinue {
            recorder.setVideoSize(cameraSize.width, cameraSize.height)
        }
        tryOrContinue {
            recorder.setVideoFrameRate(frameRate)
        }
        tryOrContinue {
            recorder.setVideoEncodingBitRate(
                    max(camcorderProfile?.videoBitRate?.toDouble() ?: 0.0, cameraSize.width*cameraSize.height*frameRate* HelioPref("camera_video_bitrate_bits_per_pixel_minimum", 0.4)).toInt()
            )
        }
        val outputFileName: String = (HelioLaserApplication.helioLaserApplicationInstance?.getExternalFilesDir(Environment.DIRECTORY_DCIM).toString()
                ?: "") + "/morseRecording-${System.currentTimeMillis() / 1000}.vid"
        recorder.setOutputFile(outputFileName)
        recorder.prepare()
        recorder.start()
        info { "HelioCameraGLVisualisation-recording $outputFileName at framerate $frameRate with camera fps $cameraFps" }
    }
*/

    fun morseSignalOn() {
        background.fill(0f)

        stopRecording()

        tryOrContinue {
            HelioCameraSource.cameraInterface?.flashlight(on = true)
        }
    }

    fun morseSignalOff() {
        background.fill(1f)

        tryOrContinue {
            HelioCameraSource.cameraInterface?.flashlight(on = false)
        }
    }
}
