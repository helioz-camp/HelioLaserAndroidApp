package xyz.helioz.heliolaser

import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

class HelioMorseCodeLettersToSymbolsTest : StringSpec({
    fun convertBackAndForth(text: String) {
        val morse = HelioMorseCodec.convertTextToMorse(text)
        val textConverted = HelioMorseCodec.convertMorseToText(morse)
        println("'$text' converted to $morse which output '$textConverted'")
        textConverted shouldBe text.toUpperCase()
    }
    "all letters should convert back and forth" {
        convertBackAndForth("a")

        convertBackAndForth(
                "the quick brown fox jumped over the lazy dog"
        )
    }

    "all known characters should convert back and forth" {
        for (char in HelioMorseCodec.characterToMorse.keys) {
            convertBackAndForth(char.toString())
        }
    }

    "all pairs of characters should convert back and forth" {
        for (char in HelioMorseCodec.characterToMorse.keys) {
            for (nextChar in HelioMorseCodec.characterToMorse.keys) {
                convertBackAndForth(char.toString() + nextChar.toString())
            }
        }
    }

    "sos" {
        HelioMorseCodec.convertTextToMorse("sos") shouldBe "... --- ..."
    }

    "a" {
        HelioMorseCodec.convertTextToMorse("a") shouldBe ".-"
    }
})
