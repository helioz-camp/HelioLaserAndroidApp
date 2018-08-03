package xyz.helioz.heliolaser

import android.app.ActionBar
import android.content.Context
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.support.v4.widget.TextViewCompat
import android.support.v4.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.AppCompatTextView
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import org.jetbrains.anko.*
import org.jetbrains.anko.appcompat.v7.tintedButton
import java.util.*
import kotlin.math.absoluteValue

class HelioLaserActivity : AppCompatActivity(), AnkoLogger {

    var sendingMessage = ""
    var activelySendingNow = false
    private lateinit var mainHandler: Handler
    private lateinit var letterSendingBox: TextView
    private lateinit var messageSendingBox: TextView
    private lateinit var visualSendingBox: View
    val morseTimings: HelioMorseCodec.HelioMorseTimings = HelioMorseCodec.HelioMorseTimings()
    val audioTonePlayer = HelioAudioTonePlayer()

    private fun animateBackgroundMorseCode(message: String) {
        sendingMessage = HelioMorseCodec.convertTextToMorse(message)
        maybeSendOneLetter()
    }

    private fun maybeSendOneLetter() {
        if (activelySendingNow) {
            return
        }
        visualSendingBox.backgroundColor = Color.WHITE
        audioTonePlayer.stopPlayingTone()
        messageSendingBox.text = sendingMessage

        if (sendingMessage.isEmpty()) {
            letterSendingBox.text = ""
            return
        }

        val firstLetter = sendingMessage.first()
        letterSendingBox.text = firstLetter.toString()
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
                visualSendingBox.backgroundColor = if (signedTiming > 0) Color.BLACK else Color.WHITE

                if (signedTiming > 0) {
                    audioTonePlayer.startPlayingTone(signedTiming)
                }

                mainHandler.postDelayed({
                    popTiming()
                }, Math.ceil(1000.0 * signedTiming.absoluteValue).toLong())
            }
        }

        popTiming()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportGlobalEvent()
        mainHandler = Handler(getMainLooper())
        fun beam(message: String?) {
            animateBackgroundMorseCode("CQ ${message} K")
        }

        val fullLayout = verticalLayout {
            val sendingBox = AppCompatTextView(context)
            with (sendingBox) {
                val lp = LinearLayout.LayoutParams(displayMetrics.widthPixels/2, displayMetrics.widthPixels/2)
                lp.bottomMargin = lp.height/10
                lp.topMargin = lp.height/10
                lp.gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = lp
                gravity = Gravity.CENTER
                textColor = Color.WHITE

                setAutoSizeTextTypeUniformWithConfiguration(1, displayMetrics.widthPixels, 1, TypedValue.COMPLEX_UNIT_PX)
            }
            letterSendingBox = sendingBox
            visualSendingBox = sendingBox
            addView(sendingBox)

            val editorWidget = editText {
                hint = "Message to translate to morse code"
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

                gravity = Gravity.CENTER
                textSize = 40f
            }

            imageButton(R.drawable.ic_present_to_all_black_120dp) {
                onClick {
                    beam(editorWidget.text.toString())
                }
                backgroundColor = Color.TRANSPARENT
            }

            messageSendingBox = textView {
                textSize = 64f
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        setContentView(fullLayout)

        val audioRecorder = HelioAudioRecorder()
        doAsync {
            audioRecorder.startRecording()
        }
    }

    override fun onDestroy() {
        reportGlobalEvent()
        super.onDestroy()
    }
}