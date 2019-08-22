package xyz.helioz.heliolaser

import android.os.Build.VERSION_CODES.N
import io.kotlintest.specs.StringSpec
import org.jtransforms.fft.FloatFFT_1D
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class ToneEstimate(
    val frequencyHz:Float,
    val amplitude:Float) {}

data class GoertzelFilter(
        val targetFrequencyHz: Double,
        val samplingHz: Double
) {
    // The Goertzel Algorithm
    // Kevin Banks, August 28, 2002
    // https://www.embedded.com/design/configurable-systems/4024443/The-Goertzel-Algorithm
    // unlike the presentation there does not discretize omega to make it exact for N samples
    val omega = (2 * PI * targetFrequencyHz)/samplingHz
    val c = cos(omega)
    val s = sin(omega)
    val decay = 2*c
    var Q1 = 0.0
    var Q2 = 0.0

    fun resetFilter() {
        Q1 = 0.0
        Q2 = 0.0
    }

    fun processNextSample(sample:Double) {
        val Q0 = decay * Q1 - Q2 + sample
        Q2 = Q1
        Q1 = Q0
    }

    fun magnitude():Double {
        return sqrt(Q1 * Q1 + Q2 * Q2 - Q1 * Q2 * decay)
    }
}

class HelioMorseCodeAudioTest : StringSpec({
    "read pcm" {
        val samplesPerSecond = 48000
        val minFrequencyHz = 20
        val maxFrequencyHz = 2000
        val samplesPerTransform = ceil(maxOf(samplesPerSecond*3*(1.0/minFrequencyHz), sqrt(2.0*maxFrequencyHz*samplesPerSecond))).toInt()
        val secondsPerTransform = samplesPerTransform/(samplesPerSecond*1.0)

        fun estimateFrequency(fftArray: FloatArray):ToneEstimate {
            var highestIndex = -1
            var nextToHighestIndex = -1
            var nextAmplitude = Float.NEGATIVE_INFINITY

            val minBucketIndex = floor(minFrequencyHz/ secondsPerTransform).toInt()
            val maxBucketIndex = ceil(maxFrequencyHz/secondsPerTransform).toInt()
            var peakAmpltiude = Float.NEGATIVE_INFINITY

            val amplitudes = FloatArray(maxBucketIndex+1)

            fun Re(i:Int):Float = fftArray[2*i]
            fun Im(i:Int):Float = fftArray[2*i+1]

            for (i in minBucketIndex..maxBucketIndex) {
                amplitudes[i] = sqrt(Re(i)*Re(i) + Im(i)*Im(i))
            }

            for (i in minBucketIndex..maxBucketIndex) {
                peakAmpltiude = maxOf(peakAmpltiude, amplitudes[i])
            }

            for (i in minBucketIndex..maxBucketIndex) {
                val bucketAmplitude = amplitudes[i]
                if (bucketAmplitude >= peakAmpltiude) {
                    var neighbourAmplitude = Float.NEGATIVE_INFINITY
                    var j = -1
                    if (i >= 1) {
                        neighbourAmplitude = amplitudes[i-1]
                        j = i-1
                    }
                    if (i < amplitudes.lastIndex && amplitudes[(i+1)] > neighbourAmplitude) {
                        neighbourAmplitude = amplitudes[i+1]
                        j = i+1
                    }
                    if (neighbourAmplitude > nextAmplitude) {
                        highestIndex = i
                        nextToHighestIndex = j
                        nextAmplitude = neighbourAmplitude
                    }
                }
            }
            fun frequencyEstimate(index:Int):Float {
                return (index/secondsPerTransform).toFloat()
            }

            val frequencyGuess = (frequencyEstimate(highestIndex)*peakAmpltiude + frequencyEstimate(nextToHighestIndex)*nextAmplitude)/(peakAmpltiude+nextAmplitude)
            return ToneEstimate(frequencyHz = frequencyGuess, amplitude = peakAmpltiude)
        }


        val oldFile = "/home/john/Downloads/output.raw"
        val filename = "/home/john/Junk/morse.raw"
        val readBuffer = ByteBuffer.wrap(File(filename).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val floatArray = FloatArray(readBuffer.remaining())
        readBuffer.get(floatArray)

        /*
        val targetFrequency = 130
        val nu = (2 * PI * targetFrequency)/samplesPerSecond
        for (i in floatArray.indices) {
            floatArray[i] = sin(i*nu).toFloat()
        }
        */

        val fft = FloatFFT_1D(samplesPerTransform.toLong())

        var inputOffset = 0
        while (inputOffset + samplesPerTransform < floatArray.size-2) {
            val buffer = FloatArray(samplesPerTransform*2)
            var i = 0
            var lowest = Float.POSITIVE_INFINITY
            var highest = Float.NEGATIVE_INFINITY

            while (i < samplesPerTransform) {
                lowest = minOf(lowest, floatArray[inputOffset])
                highest = maxOf(highest, floatArray[inputOffset])
                buffer[i] = floatArray[inputOffset] //+ floatArray[inputOffset+1] + floatArray[inputOffset + 2] - maxOf(floatArray[inputOffset], floatArray[inputOffset+1], floatArray[inputOffset+2]) - minOf(floatArray[inputOffset], floatArray[inputOffset+1], floatArray[inputOffset+2])
                i += 1
                inputOffset += 1
            }

            val origBuffer = buffer.copyOf()
            fft.realForward(buffer)
            val toneEstimate = estimateFrequency(buffer)

            val toneFilters = ArrayList<GoertzelFilter>()
            for (freq in listOf(toneEstimate.frequencyHz/2, toneEstimate.frequencyHz*.75, toneEstimate.frequencyHz, toneEstimate.frequencyHz*1.25, toneEstimate.frequencyHz*2)) {

                toneFilters.add(GoertzelFilter(targetFrequencyHz = freq.toDouble(), samplingHz = samplesPerSecond.toDouble()))
            }

            println("${toneEstimate} from ${lowest} to ${highest}")

            for (s in origBuffer) {
                for (toneFilter in toneFilters) {
                    toneFilter.processNextSample(s.toDouble())
                }
            }
            for (toneFilter in toneFilters) {
                println("${toneFilter} magnitude ${toneFilter.magnitude()}")
            }

        }


        val durations = HelioMorseCodec.signedDurationsFromAmplitudes(floatArray, 48000.0)
        println("durations are ${durations.size}")
        val guessTimings = HelioMorseCodec.guessMorseTimings(durations)
        println("timings $guessTimings")
        val symbols = HelioMorseCodec.convertSignedDurationsToMorse(durations, guessTimings)
        println("symbols $symbols")
        val message = HelioMorseCodec.convertMorseToText(symbols)
        println("message $message")
    }
})