package xyz.helioz.heliolaser

import android.graphics.Color
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

class HelioLaserActivity : AppCompatActivity(), AnkoLogger {
    val alphabet = arrayOf(
            ".-",   //A
            "-...", //B
            "-.-.", //C
            "-..",  //D
            ".",    //E
            "..-.", //F
            "--.",  //G
            "....", //H
            "..",   //I
            ".---", //J
            "-.-",  //K
            ".-..", //L
            "--",   //M
            "-.",   //N
            "---",  //O
            ".--.", //P
            "--.-", //Q
            ".-.",  //R
            "...",  //S
            "-",    //T
            "..-",  //U
            "...-", //V
            ".--",  //W
            "-..-", //X
            "-.--", //Y
            "--.." //Z
    )
    val digits = arrayOf(
            "-----", //0
            ".----", //1
            "..---", //2
            "...--", //3
            "....-", //4
            ".....", //5
            "-....", //6
            "--...", //7
            "---..", //8
            "----." //9
    )

    private fun convertTextToMorse(text:String):String {
        with(StringBuilder()) {
            for (letter in text) {
                val code = letter.toLowerCase().toInt()
                if ('0'.toInt() <= code && '9'.toInt() >= code) {
                    append(digits[code - '0'.toInt()])
                } else if ('a'.toInt() <= code && 'z'.toInt() >= code) {
                    append(alphabet[code - 'a'.toInt()])
                } else if (!letter.isWhitespace()) {
                    warn("convertTextToMorse does not know $code $letter")
                }
                append(" ")
            }
            return toString()
        }
    }

    var sendingMessage = ""
    var activelySendingNow = false
    private lateinit var mainHandler:Handler
    private lateinit var letterSendingBox:TextView
    private lateinit var messageSendingBox: TextView

    private fun animateBackgroundMorseCode(message:String, view: View) {
        sendingMessage = convertTextToMorse(message)
        maybeSendOneLetter(view)
    }

    private fun maybeSendOneLetter(view: View) {
        if (activelySendingNow) {
            return
        }
        view.backgroundColor = Color.WHITE
        messageSendingBox.text = sendingMessage
        if (sendingMessage.isEmpty()) {
            letterSendingBox.text = ""
            return
        }
        val firstLetter = sendingMessage.first()
        letterSendingBox.text = firstLetter.toString()
        sendingMessage = sendingMessage.removeRange(0, 1)
        activelySendingNow = true
        mainHandler.postDelayed({
            var delay = 180L
            messageSendingBox.text = sendingMessage
            when (firstLetter) {
                ' ' -> {}
                '-' -> { view.backgroundColor = Color.BLACK }
                '.' -> { view.backgroundColor = Color.BLACK
                    delay = 60L
                }
            }
            mainHandler.postDelayed({
                activelySendingNow = false
                maybeSendOneLetter(view)
            }, delay)

        }, 100)
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
                setImeActionLabel("Beam", KeyEvent.KEYCODE_ENTER)
                onEditorAction { _, actionId, keyEvent ->
                    if (actionId == EditorInfo.IME_NULL
                            && keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        beam(text.toString())
                    }
                    true
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
    }

    override fun onDestroy() {
        reportGlobalEvent()
        super.onDestroy()
    }
}