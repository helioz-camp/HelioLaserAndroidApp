package xyz.helioz.heliolaser

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info
import org.jetbrains.anko.warn
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.opengles.GL10

fun AnkoLogger.clearGLError(errorMessage:String="error"): Int {
    var glErrorCode = GLES20.glGetError()
    var lastErrorCode = GLES20.GL_NO_ERROR
    while (glErrorCode != GLES20.GL_NO_ERROR) {
        warn("OpenGL: $errorMessage ${GLUtils.getEGLErrorString(glErrorCode)}", Throwable())
        lastErrorCode = glErrorCode
        glErrorCode = GLES20.glGetError()
    }
    return lastErrorCode
}

fun AnkoLogger.throwOnGLError() {
    val errorCode = clearGLError()
    if (errorCode != GLES20.GL_NO_ERROR) {
        throw GLException(errorCode, "GL error ${GLUtils.getEGLErrorString(errorCode)}")
    }
}

inline fun<T> AnkoLogger.checkedGL(lambda: () -> T): T {
    clearGLError()
    val ret = lambda()

    throwOnGLError()
    return ret
}

class HelioGLRenderer(val helioCameraGLVisualisation: HelioCameraGLVisualisation): GLSurfaceView.Renderer, AnkoLogger {
    var surfaceHeight = 0
    var surfaceWidth = 0
    var drawFrameStartSystemNanos = 0L
    val drawingFramesPerSecond = AtomicReference<Double>()
    val surfacesTexturesToUpdateBeforeDrawing = Collections.synchronizedSet(HashSet<SurfaceTexture>())!!
    val surfaceAttached = AtomicBoolean(false)
    private val glHelperThreadHandler by lazy {
        val helperThread = HandlerThread(javaClass.canonicalName)
        helperThread.start()
        Handler(helperThread.looper)
    }

    fun disposeOfRenderer() {
        reportGlobalEvent()
        tryOrContinue {
            glHelperThreadHandler.post { Looper.myLooper()?.quit() }
        }
        tryOrContinue {
            helioCameraGLVisualisation.releaseVisualisationResources()
        }
    }


    private fun drawFrame() {
        val surfaceTextures = HashSet<SurfaceTexture>()
        surfaceTextures.addAll(surfacesTexturesToUpdateBeforeDrawing)
        for (texture in surfaceTextures) {
            surfacesTexturesToUpdateBeforeDrawing.remove(texture)
            tryOrContinue {
                texture.updateTexImage()
            }
        }
        helioCameraGLVisualisation.renderVisualisationFrame(this)
    }

    override fun onDrawFrame(gl: GL10?) {
        val previousDrawFrameStartSystemNanos = drawFrameStartSystemNanos
        drawFrameStartSystemNanos = System.nanoTime()
        if (previousDrawFrameStartSystemNanos != 0L) {
            val nanosTaken = drawFrameStartSystemNanos - previousDrawFrameStartSystemNanos
            drawingFramesPerSecond.set(1e9 / nanosTaken)
        }
        tryOrContinue {
            drawFrame()
            GLES20.glFinish()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        reportGlobalEvent()
        info { "OpenGL onSurfaceChanged ${w}x$h"}
        tryOrContinue {
            checkedGL {
                GLES20.glViewport(0, 0, w, h)
            }
            surfaceWidth = w
            surfaceHeight = h
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig) {
        reportGlobalEvent()
        tryOrContinue {
            GLES20.glDisable(GLES20.GL_DITHER)
            clearGLError()
        }

        tryOrContinue {
            GLES20.glEnable(GLES20.GL_BLEND)
            clearGLError()
        }

        tryOrContinue {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            clearGLError()
        }

        tryOrContinue {
            Thread.currentThread().priority = Thread.MAX_PRIORITY
        }

        tryOrContinue {
            generateGLTextures()
        }

        tryOrContinue {
            setupEGLHelperThread(config)
        }

        helioCameraGLVisualisation.prepareVisualisationShaders(this)

        surfaceAttached.set(true)
    }

    private fun generateGLTextures() {
        val array = IntArray(textureHandles.remainingCapacity())

        checkedGL { GLES20.glGenTextures(array.size, array, 0) }
        val uniqueSize = array.toList().toSet().size
        if (uniqueSize != array.size) {
            // this happens if started not on an OpenGL thread; actually a really bad error as we probably can't draw anything after
            throw RuntimeException("OpenGL GullyGLTextures only got $uniqueSize but expected ${array.size} textures; FATAL ERROR")
        }
        textureHandles.addAll(array.asList())
    }

    fun reportEGLError():Int {
        val egl = EGLContext.getEGL() as EGL10
        val error = egl.eglGetError()
        if (error != EGL10.EGL_SUCCESS) {
            warn { "OpenGL EGL error: $error "}
            reportEGLError()
        }
        return error
    }
    fun checkEGLError() {
        val error = reportEGLError()
        if (error != EGL10.EGL_SUCCESS) {
            throw RuntimeException("OpenGL EGL error: $error")
        }
    }
    private fun setupEGLHelperThread(config: EGLConfig) {
        val egl = EGLContext.getEGL() as EGL10
        val eglDisplay = egl.eglGetCurrentDisplay()
        checkEGLError()
        val eglContext = egl.eglGetCurrentContext()
        checkEGLError()
        val freshEglContext =  egl.eglCreateContext(eglDisplay, config, eglContext, intArrayOf(0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, 2, EGL10.EGL_NONE))
        checkEGLError()

        glHelperThreadHandler.post { tryOrContinue {
            // as some phones don't support pbuffers; we must create the surface on this thread as it grabs the
            // looper
            val surfaceTexture = SurfaceTexture(textureHandles.take())
            tryOrContinue {
                surfaceTexture.setDefaultBufferSize(1, 1)
            }
            val eglSurface = egl.eglCreateWindowSurface(eglDisplay, config, surfaceTexture, null)
            checkEGLError()
            val binding = egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, freshEglContext)
            checkEGLError()
            if (!binding) {
                throw RuntimeException("OpenGL background thread could not set context")
            }
        }
        }
    }

    override fun toString(): String {
        return "${javaClass.simpleName}(fps=$drawingFramesPerSecond)"
    }

    private val textureHandles: ArrayBlockingQueue<Int> = ArrayBlockingQueue(8)

    fun allocateGLTexture():Int {
        return textureHandles.take()
    }

    fun returnGLTexture(openGLTexture: Int) {
        backgroundGLAction { // need to do this on a GL thread
            checkedGL {
                val intBuffer = ByteBuffer.allocateDirect(Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer().put(openGLTexture)
                intBuffer.position(0)

                GLES20.glDeleteTextures(1, intBuffer)
            }
            textureHandles.add(openGLTexture)
        }
    }

    fun backgroundGLAction(action:()->Unit) {
        glHelperThreadHandler.post {
            tryOrContinue {
                action()
                if (HelioPref("opengl_background_force_gl_finish", true)) {
                    checkedGL {
                        GLES20.glFinish() // theoretically, even background drawing operations may be batched up
                    }
                }
            }
        }
    }

    fun setupSurfaceViewForGL(surfaceView: GLSurfaceView) {
        tryOrContinue {
            surfaceView.preserveEGLContextOnPause = true
        }

        tryOrContinue {
            surfaceView.setEGLContextClientVersion(HelioPref("opengl_config_client_version", 2))
            surfaceView.setEGLConfigChooser(
                    HelioPref("opengl_config_red_size", 8),
                    HelioPref("opengl_config_green_size", 8),
                    HelioPref("opengl_config_blue_size", 8),
                    HelioPref("opengl_config_alpha_size", 8),
                    HelioPref("opengl_config_depth_size", 0),
                    HelioPref("opengl_config_stencil_size", 1)
            )
        }
        surfaceView.setRenderer(this)
    }
}

