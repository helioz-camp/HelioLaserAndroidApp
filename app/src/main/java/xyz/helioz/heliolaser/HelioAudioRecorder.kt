package xyz.helioz.heliolaser

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.anko.AnkoLogger

class HelioAudioRecorder(val decodedMorseTextCallback: (AudioRecord, String) -> Unit, val fileCreator: () -> File?) : AnkoLogger {
    val audioRecord by lazy {
        // note there are allegedly some weird phones that do not actually support this
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT // as float is only supported in Android 21

        AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                channelConfig,
                audioFormat,
                AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        )
    }
    val audioBuffer by lazy {
        FloatArray(audioRecord.sampleRate * 5 * 60)
    }
    val decoder by lazy {
        AudioSamplesMorseDecoder(audioRecord.sampleRate.toDouble())
    }
    var bufferIndex = 0
    val recordingSessionNumber = AtomicLong(0L)

    fun writeWAVToStream(outputStream: FileOutputStream) {
        val bitDepth = 32.toShort()
        val channelCount = 1.toShort()
        val totalSampleCount = bufferIndex
        val header = ByteBuffer.allocate(36)
        header.order(ByteOrder.LITTLE_ENDIAN)

        with(header) {
            put("RIFF".toByteArray())
            putInt((bitDepth / 8 * totalSampleCount + capacity()))
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(3) // floating point
            putShort(channelCount)
            putInt(audioRecord.sampleRate)
            putInt(audioRecord.sampleRate * bitDepth / 8)
            putShort((channelCount * bitDepth / 8).toShort())

            putShort(bitDepth)
            put("data".toByteArray())
            putInt(bitDepth / 8 * totalSampleCount)
        }
        assert(header.capacity() == header.position())
        outputStream.write(header.array())

        val singleFloat = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSampleCount) {
            val f = audioBuffer[i]
            singleFloat.rewind()
            singleFloat.putFloat(f)
            outputStream.write(singleFloat.array())
        }
    }

    fun decodingThread(sessionNumber: Long) {
        tryOrContinue {
            android.os.Process
                    .setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }
        while (sessionNumber == recordingSessionNumber.get()) {
            Thread.sleep(10)
            if ((10 * bufferIndex) % audioRecord.sampleRate == 0 || bufferIndex >= audioBuffer.size) {
                tryOrContinue {
                    val decodeText = decoder.decodeMorseFromAudio(audioBuffer, samplesSize = bufferIndex)

                    decodedMorseTextCallback(audioRecord, decodeText)
                }
            }
        }

        tryOrContinue {
            val audioFile = fileCreator()
            if (audioFile != null) {
                FileOutputStream(audioFile).use {
                    writeWAVToStream(it)
                }
            }
        }
    }

    fun recordingThread(sessionNumber: Long) {
        tryOrContinue {
            android.os.Process
                    .setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
        bufferIndex = 0
        val shortBuffer = ShortArray(audioRecord.sampleRate / 5 + 1)
        while (true) {
            val amountRead = audioRecord.read(shortBuffer, 0, shortBuffer.size)
            for (i in 0 until amountRead) {
                audioBuffer[bufferIndex] = shortBuffer[i].toFloat()
                bufferIndex += 1

                if (recordingSessionNumber.get() != sessionNumber || bufferIndex >= audioBuffer.size) {
                    tryOrContinue {
                        audioRecord.stop()
                    }
                    return
                }
            }
            Thread.sleep(1)
        }
    }

    fun stopRecording() {
        recordingSessionNumber.incrementAndGet()
    }

    fun startRecording() {
        require(audioRecord.state == AudioRecord.STATE_INITIALIZED)
        audioRecord.startRecording()
        val newSession = recordingSessionNumber.incrementAndGet()
        Thread("recordingThread ${javaClass.simpleName}").run {
            tryOrContinue {
                recordingThread(newSession)
            }
        }
        Thread("decodingThread ${javaClass.simpleName}").run {
            tryOrContinue {
                decodingThread(newSession)
            }
        }
    }
}
