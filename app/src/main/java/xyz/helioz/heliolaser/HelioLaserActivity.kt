package xyz.helioz.heliolaser

import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import androidx.appcompat.app.AppCompatActivity
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import org.jetbrains.anko.*
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.min

class HelioLaserActivity : AppCompatActivity(), AnkoLogger {

    private var sendingMessage = ""
    private var activelySendingNow = false
    private lateinit var mainHandler: Handler
    private lateinit var messageSendingBox: TextView
    private lateinit var editorWidget: EditText
    private val morseTimings: HelioMorseCodec.HelioMorseTimings = HelioMorseCodec.HelioMorseTimings()
    private val audioTonePlayer = HelioAudioTonePlayer()
    private var helioGLSurfaceView: GLSurfaceView? = null
    var helioGLRenderer: HelioGLRenderer? = null
    val helioCameraGLVisualisation : HelioCameraGLVisualisation = HelioCameraGLVisualisation()


    private fun animateBackgroundMorseCode(message: String) {
        Audiomixclient.audiomixclient.play_morse_message(message)
        sendingMessage = HelioMorseCodec.convertTextToMorse(message)
        maybeSendOneLetter()
    }

    private fun maybeSendOneLetter() {
        if (activelySendingNow) {
            return
        }
        helioCameraGLVisualisation.morseSignalOff()
        audioTonePlayer.stopPlayingTone()
        messageSendingBox.text = sendingMessage

        if (sendingMessage.isEmpty()) {
            return
        }

        val firstLetter = sendingMessage.first()
        sendingMessage = sendingMessage.removeRange(0, 1)
        messageSendingBox.text = sendingMessage
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

    private fun makeCameraSurfaceView():GLSurfaceView {
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
                info{"GLSurfaceView onTouchEvent $motionEvent"}
                return if (motionEvent.action == MotionEvent.ACTION_BUTTON_RELEASE) {
                    helioCameraGLVisualisation.flipVisualisationCamera(motionEvent)
                    performClick()
                    true
                } else {
                    super.onTouchEvent(motionEvent)
                }
            }

            override fun performClick(): Boolean {
                info{"GLSurfaceView performClick"}
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
        val surfaceView = makeCameraSurfaceView()
        helioGLRenderer = HelioGLRenderer(helioCameraGLVisualisation)
        helioGLRenderer?.setupSurfaceViewForGL(surfaceView)
        helioGLSurfaceView = surfaceView

        val fullLayout = relativeLayout {
                backgroundColor = Color.WHITE
                with (surfaceView) {
                    val dim = min(displayMetrics.widthPixels, displayMetrics.heightPixels)
                    val lp = RelativeLayout.LayoutParams(dim, dim)
                    lp.centerHorizontally()
                    layoutParams = lp
                }
                addView(surfaceView)
                editorWidget = object : EditText(context) {
                    override fun onKeyPreIme(keyCode: Int, keyEvent: KeyEvent?): Boolean {
                        info { "onKeyPreIme ${keyCode} ${keyEvent}"}
                        return super.onKeyPreIme(keyCode, keyEvent)
                    }

                }
                verticalLayout {
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    gravity = Gravity.CENTER
                    imageButton(R.drawable.ic_present_to_all_black_120dp) {
                        onClick {
                            beam(editorWidget.text.toString())
                        }
                        backgroundColor = Color.TRANSPARENT
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

                        inputType=InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT

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

        val audioRecorder = HelioAudioRecorder()
        doAsync {
            audioRecorder.startRecording()
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