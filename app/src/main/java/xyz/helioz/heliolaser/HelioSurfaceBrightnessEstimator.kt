package xyz.helioz.heliolaser

import android.view.Surface
import androidx.renderscript.Allocation
import androidx.renderscript.Element
import androidx.renderscript.RenderScript
import androidx.renderscript.Type

class HelioSurfaceBrightnessEstimator(val surfaceWidth: Int, val surfaceHeight: Int) {
    val rs by lazy {
        RenderScript.create(HelioLaserApplication.helioLaserApplicationInstance!!)
    }
    val brightnessAllocation by lazy {
        // Element.YUV is only supported after API 19
        val rgbTypeBuilder: Type.Builder = Type.Builder(rs, Element.RGB_888(rs)).apply {
            setX(surfaceWidth)
            setY(surfaceHeight)
        }
        Allocation.createTyped(rs, rgbTypeBuilder.create(),
                Allocation.USAGE_IO_INPUT or Allocation.USAGE_SCRIPT)
    }
    val script by lazy {
        ScriptC_HelioSurfaceBrightnessEstimatorRenderScript(rs)
    }

    fun estimateBrightnessForSurface(surface: Surface): () -> Float {
        brightnessAllocation.setSurface(surface)
        brightnessAllocation.ioReceive()

        return { script.reduce_brightnessAccumulatorFunction(brightnessAllocation).get() }
    }
}
