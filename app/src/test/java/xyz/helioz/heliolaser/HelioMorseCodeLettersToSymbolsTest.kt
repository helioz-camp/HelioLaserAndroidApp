package xyz.helioz.heliolaser
import io.kotlintest.shouldBe
import io.kotlintest.specs.*

class HelioMorseCodeTest : StringSpec({
    fun convertBackAndForth(text:String) {
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

    "all characters should convert back and forth" {

        for (char in HelioMorseCodec.characterToMorse.keys) {
            convertBackAndForth(char.toString())
        }
    }

    "test should run" {
        println("hello")
        throw RuntimeException("test did run; be happy that the other tests were run too")
    }
}) {}