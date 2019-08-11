package xyz.helioz.heliolaser

import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.warn
import java.util.*
import kotlin.math.*

object HelioMorseCodec : AnkoLogger {
    val characterToMorse = {
        val hash = HashMap<Char, String>()
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
                ')' to "-.--.-"
        )
        hash
    }()

    val morseToCharacter = {
        val hash = HashMap<String, Char>()
        for ((char,morse) in characterToMorse) {
            hash[morse] = char
        }
        hash
    }()

    fun convertTextToMorse(text:String):String {
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
        val ditSeconds:Double = 0.03,
        val dahSeconds:Double = ditSeconds*3.0,
        val symbolSpaceSeconds:Double = ditSeconds,
        val letterSpaceSeconds:Double = dahSeconds,
        val ditDahThresholdSeconds:Double = (ditSeconds+dahSeconds)/2,
        val symbolLetterSpaceThresholdSeconds: Double = (symbolSpaceSeconds+letterSpaceSeconds)/2
        )  {
        val wordSpaceThresholdSeconds:Double
            get() = letterSpaceSeconds + symbolLetterSpaceThresholdSeconds
    }

    private class MorseDurationsBuffer : ArrayList<Double>() {
        fun addSignedDuration(signedSeconds:Double) {
            if (signedSeconds == 0.0) return
            if (isNotEmpty() && signedSeconds.sign == last().sign) {
                this[size-1] = last() + signedSeconds
            } else {
                add(signedSeconds)
            }
        }
    }

    fun convertMorseToSignedDurations(
            morse:String,
            timings: HelioMorseTimings
            ):DoubleArray {
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

    fun convertSignedDurationsToMorse(signedSeconds: DoubleArray, helioMorseTimings: HelioMorseTimings):String {
        with (StringBuilder()) {
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

    fun convertMorseToText(morse: String):String {
        with (StringBuilder()) {
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
            return toString()
        }
    }

    fun guessMorseTimings(signedSeconds: DoubleArray, shortestPossibleSignalSeconds:Double = 0.005):HelioMorseTimings {
        fun separatePeaks(samples:ArrayList<Double>): Pair<Double, Double> {
            if (samples.size < 9) {
                throw IllegalArgumentException("unable to guess timing as too few samples; ${signedSeconds.size} originally, now ${samples.size} with $shortestPossibleSignalSeconds second smoothing")
            }
            samples.sort()
            val roughMedian = samples[samples.size/2]

            // as dah is customarily 3*dit, the median is hopefully inside (dit, 3 * dit)
            // therefore, dit is hopefully (roughMedian/3, roughMedian)
            // and dah is hopefully within (roughMedian, roughMedian*3)

            val ditStartIndex = samples.binarySearch(roughMedian/3 - shortestPossibleSignalSeconds).absoluteValue
            val ditEndIndex = samples.binarySearch(roughMedian + shortestPossibleSignalSeconds).absoluteValue
            val dahStartIndex = samples.binarySearch(roughMedian - shortestPossibleSignalSeconds).absoluteValue
            val dahEndIndex = samples.binarySearch(roughMedian*3 + shortestPossibleSignalSeconds).absoluteValue

            if (ditStartIndex + 1 >= ditEndIndex) {
                throw IllegalArgumentException("too few short samples; estimating separation at $roughMedian seconds")
            }
            if (dahStartIndex + 1 >= dahEndIndex) {
                throw IllegalArgumentException("too few long samples (${ditStartIndex-ditEndIndex+1} short samples); estimating separation at $roughMedian seconds")
            }

            val ditEstimate = samples[(ditStartIndex+ditEndIndex)/2]
            val dahEstimate = samples[(dahStartIndex+dahEndIndex)/2]

            return Pair(ditEstimate, dahEstimate)
        }

        val onSeconds = ArrayList<Double>()
        val offSeconds = ArrayList<Double>()

        for (duration in signedSeconds) {
            if (duration > 0) {
                onSeconds.add(duration)
            } else {
                offSeconds.add(-duration)
            }
        }

        val (dit, dah) = separatePeaks(onSeconds)
        val (symbolSpace, _) = separatePeaks(offSeconds)

        return HelioMorseTimings(ditSeconds = dit,
                dahSeconds = max(dah, dit*3),
                symbolSpaceSeconds = min(symbolSpace, dit))
    }

    class LoudnessFilter(sampleRateHertz: Double, lookbackSeconds: Double) {
        val amplitudesSquared = FloatArray(((lookbackSeconds + 1.0/sampleRateHertz)*sampleRateHertz).toInt())
        var currentAmplitudeIndex = 0L
        var totalLoudnessSquared = 0.0
        var loudnessEstimate = 0.0
        var meanLoadness = 0.0
        var maxLoudness = 0.0

        val filterThresholdEstimate:Double
            get() = max((meanLoadness+maxLoudness)/2, maxLoudness-meanLoadness)

        fun addAmplitude(amp:Float) {
            require(!amp.isNaN()) { "attempting to add a NaN to LoudnessFilter after $currentAmplitudeIndex samples" }
            val index = (currentAmplitudeIndex % amplitudesSquared.size).toInt()
            if (currentAmplitudeIndex >= amplitudesSquared.size) {
                totalLoudnessSquared -= amplitudesSquared[index]
            }
            val squared = amp*amp
            amplitudesSquared[index] = squared
            totalLoudnessSquared += squared
            currentAmplitudeIndex ++

            loudnessEstimate = sqrt(totalLoudnessSquared / min(amplitudesSquared.size.toLong(), currentAmplitudeIndex))
            meanLoadness += (loudnessEstimate - meanLoadness) / currentAmplitudeIndex
            maxLoudness = max(loudnessEstimate, maxLoudness)
        }

    }

    class GoertzelFilter(frequencyToDetectAsProportionOfSampling:Double, outputFunction:(Double)->Unit) {
        val goertzelCoefficient = 2 * cos(2 * Math.PI * frequencyToDetectAsProportionOfSampling)

    }

    fun signedDurationsFromAmplitudes(amplitude:FloatArray, sampleRateHertz:Double, lookbackSeconds:Double = max(16.0/sampleRateHertz, 0.003)):DoubleArray {
        val loudness = FloatArray(amplitude.size)
        val filter = LoudnessFilter(sampleRateHertz, lookbackSeconds)
        for (i in 0 until amplitude.size) {
            filter.addAmplitude(amplitude[i])
            val estimate = filter.loudnessEstimate
            loudness[i] = estimate.toFloat()
        }
        with (MorseDurationsBuffer()) {
            for (level in loudness) {
                if (level > filter.filterThresholdEstimate) {
                    addSignedDuration(1/sampleRateHertz)
                } else {
                    addSignedDuration(-1/sampleRateHertz)
                }
            }
            return toDoubleArray()
        }
    }
}