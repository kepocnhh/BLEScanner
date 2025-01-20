package test.android.bles

import android.app.Application
import test.android.bles.provider.FinalLoggers
import test.android.bles.provider.Logger

internal class App : Application() {
    override fun onCreate() {
        super.onCreate()
        _loggers = FinalLoggers
    }

    companion object {
        private var _loggers: Logger.Factory? = null
        val loggers: Logger.Factory get() = checkNotNull(_loggers)
    }
}
