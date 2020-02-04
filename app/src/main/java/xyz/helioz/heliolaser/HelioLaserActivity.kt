package xyz.helioz.heliolaser

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioRecord
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.LinkedList
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.min
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.backgroundColor
import org.jetbrains.anko.centerHorizontally
import org.jetbrains.anko.centerInParent
import org.jetbrains.anko.displayMetrics
import org.jetbrains.anko.imageButton
import org.jetbrains.anko.info
import org.jetbrains.anko.inputMethodManager
import org.jetbrains.anko.linearLayout
import org.jetbrains.anko.onClick
import org.jetbrains.anko.onEditorAction
import org.jetbrains.anko.relativeLayout
import org.jetbrains.anko.textView
import org.jetbrains.anko.verticalLayout

fun minimalExtraString(base: String, extra: String): String {

    for (i in 0..base.length - 1) {
        if (extra.startsWith(base.subSequence(i, base.length))) {
            return extra.substring(base.length - i)
        }
    }
    return extra
}

class HelioLaserActivity : AppCompatActivity(), AnkoLogger {

    private var sendingMessage = ""
    private var activelySendingNow = false
    private lateinit var mainHandler: Handler
    private lateinit var messageSendingBox: TextView
    private lateinit var editorWidget: EditText
    private lateinit var recordingView: ImageButton
    private val morseTimings: HelioMorseCodec.HelioMorseTimings = HelioMorseCodec.HelioMorseTimings()
    private val audioTonePlayer = HelioAudioTonePlayer()
    private var helioGLSurfaceView: GLSurfaceView? = null
    var helioGLRenderer: HelioGLRenderer? = null
    val helioCameraGLVisualisation: HelioCameraGLVisualisation = HelioCameraGLVisualisation()
    val activePermissionRequestCodes = HashMap<Int, (permissions: Array<out String>, grants: IntArray) -> Unit>()
    var nextPermissionRequestCode = 0
    private fun makePermissionsCallback(callback: (permissions: Array<out String>, grants: IntArray) -> Unit): Int {
        requireMainThread()
        val ret = nextPermissionRequestCode++
        activePermissionRequestCodes[ret] = callback
        return ret
    }

    fun doWithPermissions(permissions: Array<String>, callback: () -> Unit) {
        val missingPermissions = ArrayList<String>()
        for (perm in permissions) {
            if (ContextCompat.checkSelfPermission(this,
                            Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(perm)
            }
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this,
                    missingPermissions.toTypedArray(),
                    makePermissionsCallback({ permissionsGranted, grants ->
                        var denied = 0
                        for (i in grants.indices) {
                            if (grants[i] != PackageManager.PERMISSION_GRANTED) {
                                info { "doWithPermissions ${permissionsGranted[i]} denied" }
                                denied++
                            }
                        }
                        if (0 == denied) {
                            tryOrContinue {
                                callback()
                            }
                        }
                    }))
        } else {
            tryOrContinue {
                callback()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val func = activePermissionRequestCodes.remove(requestCode)
        if (func != null) {
            tryOrContinue {
                func(permissions, grantResults)
            }
        }
    }

    private fun animateBackgroundMorseCode(message: String) {
        Audiomixclient.audiomixclient.play_morse_message(message)
        sendingMessage = HelioMorseCodec.convertTextToMorse(message)
        maybeSendOneLetter()
    }

    private fun updateSendingBox() {
        messageSendingBox.text = sendingMessage.replace('.', '·')
    }

    private fun maybeSendOneLetter() {
        if (activelySendingNow) {
            return
        }
        helioCameraGLVisualisation.morseSignalOff()
        audioTonePlayer.stopPlayingTone()
        updateSendingBox()

        if (sendingMessage.isEmpty()) {
            return
        }

        val firstLetter = sendingMessage.first()
        sendingMessage = sendingMessage.removeRange(0, 1)
        updateSendingBox()
        activelySendingNow = true

        val signedTimings = LinkedList(HelioMorseCodec.convertMorseToSignedDurations(firstLetter.toString(), morseTimings).toList())

        fun popTiming() {
            if (signedTimings.isEmpty()) {
                activelySendingNow = false
                maybeSendOneLetter()
            } else {
                val signedTiming = signedTimings.removeFirst()
                if (signedTiming > 0) {
                    helioCameraGLVisualisation.morseSignalOn()
                } else {
                    helioCameraGLVisualisation.morseSignalOff()
                }

                if (signedTiming > 0) {
                    audioTonePlayer.startPlayingTone(signedTiming)
                }

                mainHandler.postDelayed({
                    popTiming()
                }, ceil(1000.0 * signedTiming.absoluteValue).toLong())
            }
        }

        popTiming()
    }

    private fun makeCameraSurfaceView(): GLSurfaceView {
        return object : GLSurfaceView(this@HelioLaserActivity) {
            var h = 0
            var w = 0

            override fun onMeasure(ws: Int, hs: Int) {
                w = MeasureSpec.getSize(ws)
                h = MeasureSpec.getSize(hs)
                // never reduce size even when status bar comes along
                setMeasuredDimension(w, h)
            }

            override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
                info { "GLSurfaceView onTouchEvent $motionEvent" }
                return if (motionEvent.action == MotionEvent.ACTION_BUTTON_RELEASE) {
                    helioCameraGLVisualisation.lockAllSettingsOnCamera(motionEvent)
                    performClick()
                    true
                } else {
                    super.onTouchEvent(motionEvent)
                }
            }

            override fun performClick(): Boolean {
                info { "GLSurfaceView performClick" }
                return super.performClick()
            }

            override fun toString(): String {
                return "${javaClass.simpleName}{w=$w,h=$h,renderer=$helioGLRenderer}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportGlobalEvent()
        mainHandler = Handler(mainLooper)
        fun beam(message: String?) {
            animateBackgroundMorseCode("CQ $message K")
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), makePermissionsCallback({ _, grants -> info { "camera permission $grants" } }))
            // can continue without camera permission
        }
        val surfaceView = makeCameraSurfaceView()
        helioGLRenderer = HelioGLRenderer(helioCameraGLVisualisation)
        helioGLRenderer?.setupSurfaceViewForGL(surfaceView)

        val updateRecordingUI = { audioRecord: AudioRecord, msg: String? ->
            runOnUiThread() {
                tryOrContinue {
                    recordingView.setImageResource(if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) R.drawable.ic_mic_none_black_120dp else R.drawable.ic_mic_off_black_120dp)
                }
                tryOrContinue {
                    if (!msg.isNullOrEmpty()) {
                        editorWidget.text.append(minimalExtraString(editorWidget.text.toString(), msg))
                    }
                }
            }
        }

        helioGLSurfaceView = surfaceView
        val audioRecorder = HelioAudioRecorder(updateRecordingUI, fileCreator = {
            val now = SimpleDateFormat("yyyyMMddhhmmss", Locale.US).format(Date())
            File(getExternalFilesDir("HelioMorseAudioDir"), "recording-$now.wav")
        })

        fun toggleAudioRecording() {
            tryOrContinue {
                if (audioRecorder.audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecorder.stopRecording()
                } else {
                    audioRecorder.startRecording()
                }
            }
            tryOrContinue {
                updateRecordingUI(audioRecorder.audioRecord, null)
            }
        }

        val fullLayout = relativeLayout {
            backgroundColor = Color.WHITE
            with(surfaceView) {
                val dim = min(displayMetrics.widthPixels, displayMetrics.heightPixels)
                val lp = RelativeLayout.LayoutParams(dim, dim)
                lp.centerHorizontally()
                layoutParams = lp
            }
            addView(surfaceView)
            editorWidget = object : EditText(context) {
                override fun onKeyPreIme(keyCode: Int, keyEvent: KeyEvent?): Boolean {
                    info { "onKeyPreIme $keyCode $keyEvent" }
                    return super.onKeyPreIme(keyCode, keyEvent)
                }
            }
            verticalLayout() {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                gravity = Gravity.CENTER

                linearLayout() {
                    imageButton(R.drawable.ic_present_to_all_black_120dp) {
                        onClick {
                            beam(editorWidget.text.toString())
                        }
                        backgroundColor = Color.TRANSPARENT
                    }
                    recordingView = imageButton(R.drawable.ic_mic_off_black_120dp) {
                        onClick {
                            doWithPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), {
                                toggleAudioRecording()
                            })
                        }
                        backgroundColor = Color.TRANSPARENT
                    }
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    gravity = Gravity.CENTER
                }

                with(editorWidget) {
                    hint = "Message to morse"
                    inputType = InputType.TYPE_CLASS_TEXT
                    setImeActionLabel("Send", EditorInfo.IME_ACTION_SEND)
                    imeOptions = EditorInfo.IME_ACTION_SEND

                    onEditorAction { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_UNSPECIFIED || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND) {
                            beam(text.toString())
                            true
                        } else {
                            false
                        }
                    }
                    val lp = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT)
                    lp.centerInParent()
                    layoutParams = lp

                    gravity = Gravity.CENTER
                    textSize = 40f
                }
                addView(editorWidget)
                messageSendingBox = textView {
                    textSize = 40f
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
        }

        setContentView(fullLayout)

        tryOrContinue {
            editorWidget.requestFocus()
        }
        tryOrContinue {
            inputMethodManager.showSoftInput(editorWidget, 0)
        }
        tryOrContinue {
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
        }
        tryOrContinue {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        tryOrContinue {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onPause() {
        reportGlobalEvent()

        tryOrContinue {
            helioGLSurfaceView?.onPause()
        }

        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        reportGlobalEvent()

        tryOrContinue {
            helioGLSurfaceView?.onResume()
        }
    }

    override fun onStop() {
        reportGlobalEvent()
        super.onStop()
    }

    override fun onDestroy() {
        reportGlobalEvent()

        tryOrContinue {
            helioGLRenderer?.disposeOfRenderer()
        }
        helioGLRenderer = null

        super.onDestroy()
    }
}
