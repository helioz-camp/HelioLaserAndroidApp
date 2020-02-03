package xyz.helioz.heliolaser

import io.kotlintest.specs.StringSpec
import kotlin.test.assertEquals

class MinimalExtraStringTest : StringSpec({
    "no extra" {
        for (base in listOf("", "a", "bbbbbbbbb", "cc", "?c")) {
            for (extra in listOf("", "?", "??????", "??")) {
                assertEquals(minimalExtraString(base, extra), extra)
            }
        }
    }
    "all suffix" {
        for (base in listOf("", "cc", "bbbbbbbbb", "cc", "?c")) {
            for (extra in listOf("", "?", "??????", "??")) {
                assertEquals(minimalExtraString(base + extra, extra), "")
            }
        }
    }
    "proper suffix" {
        assertEquals(minimalExtraString("extra", "extra extra"), " extra")
    }
})
