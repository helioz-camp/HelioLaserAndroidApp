package xyz.helioz.heliolaser

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.warn

class HelioAudioRecorder : AnkoLogger {
    val audioRecord by lazy {
        // note there are allegedly some weird phones that do not actually support this
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                channelConfig,
                audioFormat,
                AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        )
    }

    fun startRecording() {
        require(audioRecord.state == AudioRecord.STATE_INITIALIZED)
        audioRecord.startRecording()
        Thread(javaClass.simpleName).run {
            val buffer = ShortArray(audioRecord.sampleRate * 10)
            while (true) {
                var offset = 0
                while (offset < buffer.size) {
                    val amountRead = audioRecord.read(buffer, offset, buffer.size - offset)
                    require(amountRead >= 0) { "read $amountRead after $offset from $audioRecord" }
                    offset += amountRead
                }
                val floatArray = FloatArray(buffer.size)
                for (i in buffer.indices) {
                    floatArray[i] = buffer[i].toFloat()
                }
                val signedDurations = HelioMorseCodec.signedDurationsFromAmplitudes(floatArray, audioRecord.sampleRate.toDouble())
                val timings = HelioMorseCodec.guessMorseTimings(signedDurations)
                val morseCode = HelioMorseCodec.convertSignedDurationsToMorse(signedDurations, timings)
                warn("recording received $morseCode; ${HelioMorseCodec.convertMorseToText(morseCode)}")
                warn("timings guessed as $timings")
                warn("done")
            }
        }
    }
}