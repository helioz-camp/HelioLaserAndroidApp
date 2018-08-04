package xyz.helioz.heliolaser

import io.kotlintest.specs.StringSpec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HelioMorseCodeAudioTest : StringSpec({
    "read pcm" {
        for ((k,v) in HelioMorseCodec.characterToMorse) {
            println("{'$k': \"$v\"},")
        }
        val readBuffer = ByteBuffer.wrap(File("/home/john/Junk/morse.raw").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val floatArray = FloatArray(readBuffer.remaining())
        readBuffer.get(floatArray)
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