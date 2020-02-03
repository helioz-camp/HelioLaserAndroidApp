package xyz.helioz.heliolaser

import java.util.HashMap
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sqrt
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info
import org.jetbrains.anko.warn
import org.jtransforms.fft.FloatFFT_1D

object HelioMorseCodec : AnkoLogger {
    val characterToMorse = {
        val hash = HashMap<Char, String>()
        val alphabet = arrayOf(
                ".-", // A
                "-...", // B
                "-.-.", // C
                "-..", // D
                ".", // E
                "..-.", // F
                "--.", // G
                "....", // H
                "..", // I
                ".---", // J
                "-.-", // K
                ".-..", // L
                "--", // M
                "-.", // N
                "---", // O
                ".--.", // P
                "--.-", // Q
                ".-.", // R
                "...", // S
                "-", // T
                "..-", // U
                "...-", // V
                ".--", // W
                "-..-", // X
                "-.--", // Y
                "--.." // Z
        )
        val digits = arrayOf(
                "-----", // 0
                ".----", // 1
                "..---", // 2
                "...--", // 3
                "....-", // 4
                ".....", // 5
                "-....", // 6
                "--...", // 7
                "---..", // 8
                "----." // 9
        )

        for (c in 'A'..'Z') {
            hash[c] = alphabet[c - 'A']
        }
        for (c in '0'..'9') {
            hash[c] = digits[c - '0']
        }
        hash += hashMapOf(
                '.' to ".-.-.-",
                ',' to "--..--",
                ':' to "---...",
                ';' to "-.-.-.",
                '?' to "..--..",
                '\'' to ".----.",
                '-' to "-....-",
                '/' to "-..-.",
                '"' to ".-..-.",
                '@' to ".--.-.",
                '=' to "-...-",
                '\n' to ".-.-",
                '+' to ".-.-.",
                '!' to "---.",
                '(' to "-.--.",
                ')' to "-.--.-",
                '&' to ".-...",
                '$' to "...-..-"
        )
        hash
    }()

    val morseToCharacter = {
        val hash = HashMap<String, Char>()
        for ((char, morse) in characterToMorse) {
            hash[morse] = char
        }
        hash
    }()

    fun convertTextToMorse(text: String): String {
        with(StringBuilder()) {
            var first = true
            for (letter in text) {
                if (!first) {
                    append(" ")
                } else {
                    first = false
                }
                val upperLetter = letter.toUpperCase()
                val morseString = characterToMorse[upperLetter]
                if (morseString.isNullOrEmpty()) {
                    if (!upperLetter.isWhitespace()) {
                        warn("HelioMorseCodec does not know character '$upperLetter'")
                    }
                } else {
                    append(morseString)
                }
            }
            return toString()
        }
    }

    data class HelioMorseTimings(
        val ditSeconds: Double = 0.03,
        val dahSeconds: Double = ditSeconds * 3.0,
        val symbolSpaceSeconds: Double = ditSeconds,
        val letterSpaceSeconds: Double = dahSeconds,
        val ditDahThresholdSeconds: Double = (ditSeconds + dahSeconds) / 2,
        val symbolLetterSpaceThresholdSeconds: Double = (symbolSpaceSeconds + letterSpaceSeconds) / 2
    ) {
        val wordSpaceThresholdSeconds: Double
            get() = letterSpaceSeconds + symbolLetterSpaceThresholdSeconds
    }

    private class MorseDurationsBuffer(val shortestPossibleSignalSeconds: Double = 0.005) : ArrayList<Double>() {
        fun addSignedDuration(signedSeconds: Double) {
            if (signedSeconds == 0.0) return
            if (isNotEmpty() && signedSeconds.sign == last().sign) {
                this[size - 1] = last() + signedSeconds
            } else {
                // drop all tiny spikes in either direction
                if (isNotEmpty() && this[size - 1].absoluteValue < shortestPossibleSignalSeconds) {
                    this[size - 1] = signedSeconds
                } else {
                    add(signedSeconds)
                }
            }
        }
    }

    fun convertMorseToSignedDurations(
        morse: String,
        timings: HelioMorseTimings
    ): DoubleArray {
        with(MorseDurationsBuffer()) {
            for (symbol in morse) {
                when (symbol) {
                    ' ' -> {
                        addSignedDuration(-timings.letterSpaceSeconds)
                    }
                    '-' -> {
                        addSignedDuration(timings.dahSeconds)
                        addSignedDuration(-timings.symbolSpaceSeconds)
                    }
                    '.' -> {
                        addSignedDuration(timings.ditSeconds)
                        addSignedDuration(-timings.symbolSpaceSeconds)
                    }
                    else -> {
                        throw IllegalArgumentException("unknown morse code symbol: $symbol")
                    }
                }
            }
            return toDoubleArray()
        }
    }

    fun convertSignedDurationsToMorse(signedSeconds: DoubleArray, helioMorseTimings: HelioMorseTimings): String {
        with(StringBuilder()) {
            for (signedSecond in signedSeconds) {
                if (signedSecond < 0) {
                    if (signedSecond.absoluteValue > helioMorseTimings.symbolLetterSpaceThresholdSeconds) {
                        append(' ')
                        if (signedSecond.absoluteValue > helioMorseTimings.wordSpaceThresholdSeconds) {
                            append(' ')
                        }
                    }
                } else if (signedSecond > helioMorseTimings.ditDahThresholdSeconds) {
                    append('-')
                } else {
                    append('.')
                }
            }
            return toString()
        }
    }

    fun scoreDecodedText(decodedText: String): Double {
        val seenLetters = HashSet<Char>()
        var score = 0.0
        var wordLength = 0
        for (char in decodedText) {
            if (char.isWhitespace()) {
                if (wordLength > 2 && wordLength < 10) {
                    score += 1
                }
                wordLength = 0
                continue
            }
            if (seenLetters.add(char)) {
                if (char == '\uFFFD') {
                    score -= 1
                } else {
                    wordLength += 1
                    score += 1
                }
            }
        }
        return score
    }

    fun convertMorseToText(morse: String): String {
        with(StringBuilder()) {
            var firstWord = true
            for (word in morse.split("  ")) {
                if (firstWord) {
                    firstWord = false
                } else {
                    append(' ')
                }
                for (letter in word.split(' ')) {
                    if (letter.isBlank()) {
                        continue
                    }
                    append(morseToCharacter[letter] ?: '\uFFFD')
                }
            }
            return toString().trim()
        }
    }

    fun guessMorseTimings(signedSeconds: DoubleArray): HelioMorseTimings {
        val onSeconds = ArrayList<Double>()
        val offSeconds = ArrayList<Double>()

        for (duration in signedSeconds) {
            if (duration > 0) {
                onSeconds.add(duration)
            } else {
                offSeconds.add(-duration)
            }
        }

        fun separatePeaks(durations: ArrayList<Double>, longQuantile: Double = .5): Pair<Double, Double> {
            val mean = durations.sum() / durations.size
            durations.sort()
            val durationsMeanIndex = durations.binarySearch(mean).absoluteValue
            return Pair(durations[(durationsMeanIndex * .75).toInt()], durations[durationsMeanIndex + ((durations.size - 1 - durationsMeanIndex) * longQuantile).toInt()])
        }

        val (dit, dah) = separatePeaks(onSeconds, longQuantile = .5) // about half dots and half dashes
        val (symbolSpace, letterSpace) = separatePeaks(offSeconds, longQuantile = .8) // about .8 letterspaces

        return HelioMorseTimings(ditSeconds = (dit + dah / 3) / 2,
                dahSeconds = (dah + 3 * dit) / 2,
                symbolSpaceSeconds = (symbolSpace + letterSpace / 3) / 2,
                letterSpaceSeconds = (symbolSpace * 3 + letterSpace) / 2)
    }

    fun signedDurationsFromAmplitudes(amplitude: FloatArray, sampleRateHertz: Double, shortestPossibleSignalSeconds: Double = 0.005): DoubleArray {
        val filterLevel = amplitude.sum() / amplitude.size

        val lookbackSamples = (shortestPossibleSignalSeconds * sampleRateHertz).toInt() + 1
        var lastLowSample = -lookbackSamples - 1
        with(MorseDurationsBuffer()) {
            for (i in amplitude.indices) {
                val a = amplitude[i]
                if (a < filterLevel) {
                    lastLowSample = i
                }
                if (i - lastLowSample > lookbackSamples) {
                    addSignedDuration(1 / sampleRateHertz)
                } else {
                    addSignedDuration(-1 / sampleRateHertz)
                }
            }
            return toDoubleArray()
        }
    }
}

class AudioSamplesMorseDecoder(
    val samplesPerSecond: Double,
    val minFrequencyHz: Double = 20.0,
    val maxFrequencyHz: Double = 2000.0
) : AnkoLogger {
    val samplesPerTransform = ceil(maxOf(samplesPerSecond * 3 * (1.0 / minFrequencyHz), sqrt(2.0 * maxFrequencyHz * samplesPerSecond))).toInt()
    val secondsPerTransform = samplesPerTransform / (samplesPerSecond * 1.0)
    val fft = FloatFFT_1D(samplesPerTransform.toLong())
    val accumulatedFFTArray = FloatArray(samplesPerTransform * 2)
    val fftBuffer = FloatArray(samplesPerTransform * 2)

    fun decodeMorseFromAudio(samples: FloatArray, samplesSize: Int = samples.size): String {
        addSamplesForFFT(samples, samplesSize)
        val toneEstimate = toneEstimateFromFFT()
        info { "decodeMorseFromAudio toneEstimate $toneEstimate" }
        val filter = GoertzelFilter(targetFrequencyHz = toneEstimate.frequencyHz.toDouble(), samplingHz = samplesPerSecond.toDouble())

        val samplingPointSeparation = max(1, ((filter.samplingHz + filter.targetFrequencyHz / 2) / filter.targetFrequencyHz).toInt())
        val sampleWindow = round((2.0 * filter.samplingHz) / filter.targetFrequencyHz).toInt()
        val transformedFloatArray = FloatArray((samplesSize - sampleWindow) / samplingPointSeparation)
        for (i in transformedFloatArray.indices) {
            for (j in (i * samplingPointSeparation)..(i * samplingPointSeparation + sampleWindow)) {
                filter.processNextSample(samples[j].toDouble())
            }
            transformedFloatArray[i] = filter.magnitude().toFloat()
            filter.resetFilter()
        }
        val durations = HelioMorseCodec.signedDurationsFromAmplitudes(transformedFloatArray, samplesPerSecond.toDouble() / samplingPointSeparation)
        val guessTimings = HelioMorseCodec.guessMorseTimings(durations)

        var bestMessage: String = ""
        var bestScore = Double.NEGATIVE_INFINITY
        for (ditDahScale in 1..4) {
            val ditDahRatio = ditDahScale / 2.0
            for (spaceScale in 1..4) {
                val spaceRatio = spaceScale / 2.0
                val timings = HelioMorseCodec.HelioMorseTimings(
                        ditSeconds = guessTimings.ditSeconds * ditDahRatio,
                        dahSeconds = guessTimings.dahSeconds * ditDahRatio,
                        letterSpaceSeconds = guessTimings.letterSpaceSeconds * spaceRatio,
                        symbolSpaceSeconds = guessTimings.symbolSpaceSeconds * spaceRatio
                )
                val symbols = HelioMorseCodec.convertSignedDurationsToMorse(durations, timings)
                val message = HelioMorseCodec.convertMorseToText(symbols)
                val score = HelioMorseCodec.scoreDecodedText(message)
                if (score > bestScore) {
                    info { "decodeMorseFromAudio improvedDecode space=$spaceRatio ditDah=$ditDahRatio message=$message" }
                    bestScore = score
                    bestMessage = message
                }
            }
        }

        if (bestScore > 0) {
            return bestMessage
        }
        return ""
    }

    fun addSamplesForFFT(samples: FloatArray, samplesSize: Int) {
        var inputOffset = 0
        while (inputOffset + samplesPerTransform < samplesSize) {
            var i = 0

            while (i < samplesPerTransform) {
                fftBuffer[i] = samples[inputOffset]
                i += 1
                inputOffset += 1
            }

            fft.realForward(fftBuffer)
            for (i in accumulatedFFTArray.indices) {
                accumulatedFFTArray[i] += fftBuffer[i]
            }
        }
    }

    fun toneEstimateFromFFT(): ToneEstimate {
        var highestIndex = -1
        var nextToHighestIndex = -1
        var nextAmplitude = Float.NEGATIVE_INFINITY

        val minBucketIndex = floor(minFrequencyHz / secondsPerTransform).toInt()
        val maxBucketIndex = ceil(maxFrequencyHz / secondsPerTransform).toInt()
        var peakAmpltiude = Float.NEGATIVE_INFINITY

        val amplitudes = FloatArray(maxBucketIndex + 1)

        fun Re(i: Int): Float = accumulatedFFTArray[2 * i]
        fun Im(i: Int): Float = accumulatedFFTArray[2 * i + 1]

        for (i in minBucketIndex..maxBucketIndex) {
            amplitudes[i] = sqrt(Re(i) * Re(i) + Im(i) * Im(i))
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
                    neighbourAmplitude = amplitudes[i - 1]
                    j = i - 1
                }
                if (i < amplitudes.lastIndex && amplitudes[(i + 1)] > neighbourAmplitude) {
                    neighbourAmplitude = amplitudes[i + 1]
                    j = i + 1
                }
                if (neighbourAmplitude > nextAmplitude) {
                    highestIndex = i
                    nextToHighestIndex = j
                    nextAmplitude = neighbourAmplitude
                }
            }
        }
        fun frequencyEstimate(index: Int): Float {
            return (index / secondsPerTransform).toFloat()
        }

        val frequencyGuess = (frequencyEstimate(highestIndex) * peakAmpltiude + frequencyEstimate(nextToHighestIndex) * nextAmplitude) / (peakAmpltiude + nextAmplitude)
        return ToneEstimate(frequencyHz = frequencyGuess, amplitude = peakAmpltiude)
    }
}

data class ToneEstimate(
    val frequencyHz: Float,
    val amplitude: Float
)

data class GoertzelFilter(
    val targetFrequencyHz: Double,
    val samplingHz: Double
) {
    // The Goertzel Algorithm
    // Kevin Banks, August 28, 2002
    // https://www.embedded.com/design/configurable-systems/4024443/The-Goertzel-Algorithm
    // unlike the presentation there does not discretize omega to make it exact for N samples
    val omega = (2 * PI * targetFrequencyHz) / samplingHz
    val decay = 2 * cos(omega)
    var Q1 = 0.0
    var Q2 = 0.0
    var sampleCount = 0L

    fun resetFilter() {
        Q1 = 0.0
        Q2 = 0.0
        sampleCount = 0
    }

    fun processNextSample(sample: Double) {
        val Q0 = decay * Q1 - Q2 + sample
        Q2 = Q1
        Q1 = Q0
        sampleCount += 1
    }

    fun magnitude(): Double {
        // the magnitude asymptotically approaches 2*signal magnitude*sampleCount for a stationary wave
        return 2 * sqrt(Q1 * Q1 + Q2 * Q2 - Q1 * Q2 * decay) / sampleCount
    }
}
