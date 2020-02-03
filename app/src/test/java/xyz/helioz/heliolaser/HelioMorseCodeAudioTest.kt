package xyz.helioz.heliolaser

import io.kotlintest.specs.StringSpec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals

class HelioMorseCodeAudioTest : StringSpec({
    "read pcm" {
        val samplesPerSecond = 48000

        val filename = "/home/john/Junk/morse.raw"
        val readBuffer = ByteBuffer.wrap(File(filename).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val floatArray = FloatArray(readBuffer.remaining())
        readBuffer.get(floatArray)

        val toneEstimator = AudioSamplesMorseDecoder(samplesPerSecond = samplesPerSecond.toDouble())
        val message = toneEstimator.decodeMorseFromAudio(floatArray).trim()

        assertEquals("= NOW 18 WPM TRANSITION FILE FOLLOWS= ARRL WANTS THE FCC TO FACILITATE BONA FIDE AMATEUR SATELLITE EXPERIMENTATION BY EDUCATIONAL INSTITUTIONS UNDER PART 97 AMATEUR SERVICE RULES, WHILE PRECLUDING THE EXPLOITATION OF AMATEUR SPECTRUM BY COMMERCIAL, SMALL SATELLITE USERS AUTHORIZED UNDER PART 5 EXPERIMENTAL RULES. IN COMMENTS FILED ON JULY 9 IN AN FCC PROCEEDING TO STREAMLINE LICENSING PROCEDURES FOR SMALL SATELLITES, ARRL SUGGESTED THAT THE FCC ADOPT A BRIGHT LINE TEST TO DEFINE AND DISTINGUISH SATELLITES THAT SHOULD BE PERMITTED TO OPERATE UNDER AMATEUR SATELLITE RULES, AS OPPOSED TO NON AMATEUR SATELLITES THAT COULD BE AUTHORIZED UNDER PART 5 EXPERIMENTAL RULES. SPECIFICALLY, IT IS POSSIBLE TO CLARIFY WHICH TYPES OF SATELLITE OPERATIONS ARE PROPERLY CONSIDERED AMATEUR EXPERIMENTS CONDUCTED PURSUANT TO A PART 97 AMATEUR RADIO LICENSE, AND THOSE WHICH SHOULD BE CONSIDERED EXPERIMENTAL, NON AMATEUR FACILITIES, PROPERLY AUTHORIZED BY A PART 5 AUTHORIZATION. ARRL SAID IT VIEWS AS INCORRECT AND OVERLY STRICT THE STANDARD THE FCC HAS APPLIED SINCE 2013 TO DEFINE WHAT CONSTITUTES AN AMATEUR SATELLITE, FORCING ACADEMIC PROJECTS THAT ONCE WOULD HAVE BEEN OPERATED IN THE AMATEUR SATELLITE SERVICE TO APPLY FOR A PART 5 EXPERIMENTAL AUTHORIZATION INSTEAD. THIS APPROACH WAS BASED, ARRL SAID, ON THE FALSE RATIONAL THAT A SATELLITE LAUNCHED BY AN EDUCATIONAL INSTITUTION MUST BE NON AMATEUR BECAUSE INSTRUCTORS WERE BEING COMPENSATED AND WOULD THUS HAVE A PECUNIARY INTEREST IN THE SATELLITE PROJECT. ARRL SAID WELL ESTABLISHED COMMISSION JURISPRUDENCE CONTRADICTS THIS VIEW. ARRL TOLD THE FCC THAT JUSTIFICATION EXISTS TO EXPAND THE CATEGORY OF SATELLITE EXPERIMENTS CONDUCTED UNDER AN AMATEUR RADIO LICENSE, ESPECIALLY THOSE IN WHICH A COLLEGE, UNIVERSITY, OR SECONDARY SCHOOL TEACHER IS A SPONSOR. BUT, ARRL CONTINUED, A COMPELLING NEED EXISTS TO DISCOURAGE PART 5 EXPERIMENTAL AUTHORIZATIONS FOR SATELLITES INTENDED TO OPERATE IN AMATEUR ALLOCATIONS BY NON AMATEUR SPONSORS, ABSENT COMPELLING SHOWINGS OF NEED. THERE IS NO DOUBT BUT THAT AMATEUR RADIO SHOULD BE PROTECTED AGAINST EXPLOITATION BY COMMERCIAL ENTITIES, AND THERE SHOULD BE A COMPELLING JUSTIFICATION FOR A PART 5 EXPERIMENTAL LICENSE ISSUED FOR A SATELLITE EXPERIMENT TO BE CONDUCTED IN AMATEUR SPECTRUM, ARRL SAID. A DEFINING CRITERION FOR THIS LATTER CATEGORY SHOULD BE THAT THERE IS NO OTHER SPECTRUM PRACTICALLY AVAILABLE IN LIEU OF AMATEUR RADIO ALLOCATIONS. JAPANS SPACE AGENCY JAXA HAS ANNOUNCED THAT NINE CUBESATS WILL BE DEPLOYED FROM THE INTERNATIONAL SPACE STATION ON JULY 13. THREE OF THE SATELLITES, NAMED ENDUROSAT AD, EQUISAT, AND MEMSAT, WILL TRANSMIT TELEMETRY IN THE 70 CENTIMETER AMATEUR RADIO BAND. ENDUROSAT AD WILL TRANSMIT ON 437.050 MHZ, USING CW, AND 9.6 KB GFSK. EQUISAT WILL TRANSMIT ON 435.550 MHZ, USING CW, AND 9.6 KB FSK, AND MEMSAT WILL TRANSMIT ON 437.350 MHZ WITH 9.6 KB BPSK. = END OF 18 WPM TRANSITION FILE &", message)
        println(message)

        /*
        with (File("/home/john/Junk/debug-morse.csv").printWriter()) {
            write("time,signal,filtered\n")

            for (i in floatArray.indices) {
                write("${i/(1.0*samplesPerSecond)},${floatArray[i]},${transformedFloatArray[i]}\n")
            }

            close()
        }

         */

/*
        val outputBuffer = ByteBuffer.allocate(transformedFloatArray.size*java.lang.Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val minFloat = transformedFloatArray.min()!!
        val maxFloat = transformedFloatArray.max()!!
        for (float in transformedFloatArray) {
            val xformed = (float - minFloat)/(maxFloat-minFloat)
            outputBuffer.putFloat(xformed)
        }
        File(goertzelFilterOutput).writeBytes(outputBuffer.array())

 */
        /*
        with (File("/home/john/Junk/debug-morse-durations.csv").printWriter()) {
            write("time,duration,state\n")
            var time = 0.0
            for (d in durations) {
                write("${time},${d.absoluteValue},${if(d>0)1 else 0}\n")
                time += d.absoluteValue
            }

            close()
        }

         */

        /*
        val targetFrequency = 130
        val nu = (2 * PI * targetFrequency)/samplesPerSecond
        for (i in floatArray.indices) {
            floatArray[i] = sin(i*nu).toFloat()
        }
        */

/*
            val toneFilters = arrayListOf<Float>(toneEstimate.frequencyHz*.4f, toneEstimate.frequencyHz*.75f, toneEstimate.frequencyHz, toneEstimate.frequencyHz*1.25f, toneEstimate.frequencyHz*2).map {
                freq ->
                val filter = GoertzelFilter(targetFrequencyHz = freq.toDouble(), samplingHz = samplesPerSecond.toDouble())
                var totalMagnitude = 0.0

                object : java.util.function.Function<Double, Void?> {

                    override fun apply(sample: Double): Void? {
                        filter.processNextSample(sample)
                        totalMagnitude += filter.magnitude()
                        return null
                    }

                    override fun toString(): String {
                        return "${filter} magnitude ${totalMagnitude}"
                    }
                }
            }.toList()

            println("${toneEstimate} from ${lowest} to ${highest}")

            for (s in origBuffer) {
                for (toneFilter in toneFilters) {
                    toneFilter.apply(s.toDouble())
                }
            }
            for (toneFilter in toneFilters) {
                println(toneFilter)
            }

        }
*/
    }
})
