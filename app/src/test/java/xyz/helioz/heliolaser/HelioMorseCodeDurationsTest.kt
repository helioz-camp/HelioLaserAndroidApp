package xyz.helioz.heliolaser

import io.kotlintest.data.forall
import io.kotlintest.matchers.plusOrMinus
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec
import io.kotlintest.tables.row

class HelioMorseCodeDurationsTest : StringSpec({
    fun convertBackAndForth(symbols:String, readScale:Double = 1.0, shouldNotFail:Boolean = true) {
        val generateTimings = HelioMorseCodec.HelioMorseTimings()
        val readTimings = HelioMorseCodec.HelioMorseTimings(ditSeconds = generateTimings.ditSeconds * readScale)

        val signedDurations = HelioMorseCodec.convertMorseToSignedDurations(symbols, generateTimings)
        val symbolsReconverted = HelioMorseCodec.convertSignedDurationsToMorse(signedDurations, readTimings)

        if (shouldNotFail) {
            symbolsReconverted shouldBe symbols
        } else {
            symbolsReconverted shouldNotBe symbols
        }
    }

    "convert symbols" {
        forall(row("."),
                row("-")) {
            symbol -> convertBackAndForth(symbol)
        }
    }

    "convert at different scales" {
        forall(row(1.0),
                row(0.75),
                row(0.85),
                row(0.95),
                row(1.05),
                row(1.1),
                row(1.3)) {
            scale -> convertBackAndForth(".-", scale)
        }
    }

    "conversion at insane scales must fail" {
        forall(row(10000.0),
                row(0.00001)) {
            scale -> convertBackAndForth(".-", scale, shouldNotFail = false)
        }
    }


    "convert simple phrases" {
        forall(row(".-"),
                row("... --- ..."),
                row("-.-. --.-")) {
            symbol -> convertBackAndForth(symbol)
        }
    }

    "recover sending scale" {
        val message = HelioMorseCodec.convertTextToMorse("it escapes, irretrievable time")

        forall(row(1.0),
                row(0.00001),
                row(10000.0)) {
            dit ->
            val signedDurations = HelioMorseCodec.convertMorseToSignedDurations(message, HelioMorseCodec.HelioMorseTimings(ditSeconds = dit))
            val guessedTimings = HelioMorseCodec.guessMorseTimings(signedDurations)
            guessedTimings.ditSeconds shouldBe (dit plusOrMinus dit/100)
            val symbolsReconverted = HelioMorseCodec.convertSignedDurationsToMorse(signedDurations, guessedTimings)
            symbolsReconverted shouldBe message
        }
    }

}) {}