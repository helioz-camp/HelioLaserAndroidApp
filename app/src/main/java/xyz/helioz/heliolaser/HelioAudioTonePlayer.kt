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

    val audioTrack by lazy {
        val audioStreamType = AudioManager.STREAM_DTMF
        val nativeOutputSampleRate = AudioTrack.getNativeOutputSampleRate(audioStreamType)
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        // cannot use floats as they were added in API 21
        val buffer = ShortArray(((nativeOutputSampleRate*2)/toneHertz).toInt())
        for (i in buffer.indices) {
            val time = i/nativeOutputSampleRate.toDouble()
            buffer[i] = Math.max(
                    Math.min(
                            Short.MAX_VALUE.toFloat(),
                            0x10000 * Math.sin(time * toneHertz * 2.0 * Math.PI).toFloat()),
                    Short.MIN_VALUE.toFloat())
                    .toShort()
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
        track.setLoopPoints(0, buffer.size, -1)

        track
    }

    fun startPlayingTone() {
        doAsync { audioTrack.play() }
    }

    fun stopPlayingTone() {
        // reduce the popping sound on pause by lowering the volume instead, which Android
        // seems to know to do smoothly
        audioTrack.setStereoVolume(0f, 0f)
        doAsync {
            audioTrack.pause()
            audioTrack.setStereoVolume(1f, 1f)
            audioTrack.reloadStaticData()
        }
    }
}