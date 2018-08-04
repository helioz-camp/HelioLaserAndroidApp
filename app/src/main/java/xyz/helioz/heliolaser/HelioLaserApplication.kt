package xyz.helioz.heliolaser

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import com.google.gson.JsonObject
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info
import org.jetbrains.anko.warn
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


class HelioLaserApplication: Application(), AnkoLogger {
    private val startTimeMillis = System.currentTimeMillis()

    val applicationAgeSeconds:Double
        get() = 0.001 * (System.currentTimeMillis() - startTimeMillis)

    init {
        helioLaserApplicationInstance = this
    }

    override fun onCreate() {
        super.onCreate()
        reportGlobalEvent()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        reportGlobalEvent()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        reportGlobalEvent()
    }

    override fun onTerminate() {
        reportGlobalEvent()
        super.onTerminate()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        reportGlobalEvent()
    }

    companion object {
        var helioLaserApplicationInstance: HelioLaserApplication? = null
            private set

    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        reportGlobalEvent()
    }

    val buildProperties = JsonObject()
}

@Suppress("NOTHING_TO_INLINE") // inline just to avoid extra stack frame
inline fun AnkoLogger.reportGlobalEvent() {
    val stack = Throwable().stackTrace[0]
    tryOrContinue {
        try {
            val bundle = Bundle()
            bundle.putString("threadName", Thread.currentThread().name)
            bundle.putString("eventClassName", stack.className)
            info{"reportGlobalEvent ${stack.methodName} ${stack.className} on ${Thread.currentThread().name}"}

        } catch (e:Exception) {
            info("reportGlobalEvent scheduling exception for Firebase failed", e)
        }
    }
}


inline fun AnkoLogger.tryOrContinue(lambda: () -> Unit) {
    try {
        lambda()
    } catch (e:Exception) {
        warn("tryOrContinue failed on ${Throwable().stackTrace[1]}", e)

        logException(e)
    }
}

val activeExceptionReports = AtomicInteger(0)
val totalExceptionReports = AtomicLong(0)

fun AnkoLogger.logException(e:Throwable) {
    warn("HelioLaserApplication exception after ${HelioLaserApplication.helioLaserApplicationInstance?.applicationAgeSeconds}s", e)
}

fun requireMainThread() {
    require(Looper.getMainLooper().thread == Thread.currentThread())
    { "need to be on the main Android UI thread; instead on ${Thread.currentThread()}"}
}