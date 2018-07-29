package xyz.helioz.heliolaser

import android.content.Context
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.AppCompatActivity
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import org.jetbrains.anko.*
import java.util.*
import kotlin.math.absoluteValue

class HelioLaserActivity : AppCompatActivity(), AnkoLogger {

    var sendingMessage = ""
    var activelySendingNow = false
    private lateinit var mainHandler:Handler
    private lateinit var letterSendingBox:TextView
    private lateinit var messageSendingBox: TextView
    val morseTimings: HelioMorseCodec.HelioMorseTimings = HelioMorseCodec.HelioMorseTimings()
    val audioTonePlayer = HelioAudioTonePlayer()

    private fun animateBackgroundMorseCode(message:String, view: View) {
        sendingMessage = HelioMorseCodec.convertTextToMorse(message)
        maybeSendOneLetter(view)
    }

    private fun maybeSendOneLetter(view: View) {
        if (activelySendingNow) {
            return
        }
        view.backgroundColor = Color.WHITE
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
                maybeSendOneLetter(view)
            } else {
                val signedTiming = signedTimings.removeFirst()
                view.backgroundColor = if (signedTiming > 0) Color.BLACK else Color.WHITE

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
        fun beam(message:String?) {
            animateBackgroundMorseCode("CQ ${message} K", contentView!!)
        }

        val fullLayout = verticalLayout {

            val editorWidget = editText {
                hint = "Message to Heliobeam in morse code!"
                inputType = InputType.TYPE_CLASS_TEXT
                setImeActionLabel("Send", EditorInfo.IME_ACTION_SEND)
                imeOptions = EditorInfo.IME_ACTION_SEND

                onEditorAction { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND) {
                        beam(text.toString())
                        true
                    } else {
                        false
                    }
                }
            }

            button("Beam") {
                onClick {
                    beam(editorWidget.text.toString())
                }
                backgroundColor = Color.TRANSPARENT
            }

            letterSendingBox = textView {
                textSize = 140f
                gravity = Gravity.CENTER_HORIZONTAL
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