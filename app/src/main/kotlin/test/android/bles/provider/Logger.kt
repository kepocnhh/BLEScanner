package test.android.bles.provider

internal interface Logger {
    interface Factory {
        fun create(tag: String): Logger
    }

    fun debug(message: String)
}
