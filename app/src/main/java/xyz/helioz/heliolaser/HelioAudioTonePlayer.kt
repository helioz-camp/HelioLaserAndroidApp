package xyz.helioz.heliolaser

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.jetbrains.anko.doAsync

class HelioAudioTonePlayer(toneHertz:Double=780.0) : AutoCloseable {
    override fun close() {
        doAsync {
            audioTrack.release()
        }
    }
    var samplesInTone:Int = 0

    val audioTrack by lazy {
        val audioStreamType = AudioManager.STREAM_DTMF
        val nativeOutputSampleRate = AudioTrack.getNativeOutputSampleRate(audioStreamType)
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        // cannot use floats as they were added in API 21
        val buffer = ShortArray((nativeOutputSampleRate/toneHertz).toInt())
        for (i in buffer.indices) {
            val time = i/nativeOutputSampleRate.toDouble()
            buffer[i] = (Short.MAX_VALUE.toFloat() * Math.sin(time * toneHertz * 2.0 * Math.PI).toFloat()).toShort()
        }

        val track = AudioTrack(
                audioStreamType,
                nativeOutputSampleRate,
                channelConfig,
                audioFormat,
                Math.max(AudioTrack.getMinBufferSize(audioStreamType, channelConfig, audioFormat), buffer.size*4),
                AudioTrack.MODE_STATIC
        )

        track.write(buffer, 0, buffer.size)
        samplesInTone = buffer.size

        track
    }

    fun startPlayingTone(durationSeconds:Double) {
        doAsync {
            if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.pause()
                audioTrack.reloadStaticData()
            }
            audioTrack.setLoopPoints(0, samplesInTone,
                    Math.floor(durationSeconds/samplesInTone*audioTrack.sampleRate).toInt())

            audioTrack.play()
        }
    }

    fun stopPlayingTone() {
        doAsync {
            audioTrack.pause()
            audioTrack.reloadStaticData()
        }
    }
}