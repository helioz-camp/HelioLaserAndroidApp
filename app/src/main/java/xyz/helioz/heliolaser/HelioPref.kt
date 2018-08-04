package xyz.helioz.heliolaser

import com.google.gson.JsonElement

fun HelioPrefFromServerAsJSON(name:String):JsonElement? {
    return null
}

fun HelioPref(name: String, defaultValue: Long):Long {
    return System.getProperty(name)?.toLong()
            ?: HelioPrefFromServerAsJSON(name)?.asLong
            ?: HelioLaserApplication.helioLaserApplicationInstance?.buildProperties?.get(name)?.asLong
            ?: defaultValue
}
fun HelioPref(name: String, defaultValue: Double):Double {
    return System.getProperty(name)?.toDouble()
            ?: HelioPrefFromServerAsJSON(name)?.asDouble
            ?: HelioLaserApplication.helioLaserApplicationInstance?.buildProperties?.get(name)?.asDouble
            ?: defaultValue
}
fun HelioPref(name: String, defaultValue: Float):Float {
    return System.getProperty(name)?.toFloat()
            ?: HelioPrefFromServerAsJSON(name)?.asFloat
            ?: HelioLaserApplication.helioLaserApplicationInstance?.buildProperties?.get(name)?.asFloat
            ?: defaultValue
}
fun HelioPref(name: String, defaultValue: Int):Int {
    return System.getProperty(name)?.toInt()
            ?: HelioPrefFromServerAsJSON(name)?.asInt
            ?: HelioLaserApplication.helioLaserApplicationInstance?.buildProperties?.get(name)?.asInt
            ?: defaultValue
}
fun HelioPref(name: String, defaultValue: Boolean):Boolean {
    return System.getProperty(name)?.toBoolean()
            ?: HelioPrefFromServerAsJSON(name)?.asBoolean
            ?: HelioLaserApplication.helioLaserApplicationInstance?.buildProperties?.get(name)?.asBoolean
            ?: defaultValue
}
fun HelioPref(name: String, defaultValue: String):String {
    return System.getProperty(name)
            ?: HelioPrefFromServerAsJSON(name)?.asString
            ?: HelioLaserApplication.helioLaserApplicationInstance?.buildProperties?.get(name)?.asString
            ?: defaultValue
}
