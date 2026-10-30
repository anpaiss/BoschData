package com.anpaiss.boschdata

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger su file (stesso schema di BetterEta, con mirror su Logcat).
 *
 * Recupero: adb pull /sdcard/Android/data/com.anpaiss.boschdata/files/bosch_data.log
 * Live:     adb logcat -s PoiAhead
 */
class BoschDataLogger(context: Context) {

    private val file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var writeFailLogged = false

    companion object {
        /** Logger dell'estensione, per i receiver (manifest) che vivono nello stesso processo. */
        @Volatile var shared: BoschDataLogger? = null

        private const val MAX_SIZE_BYTES = 2 * 1024 * 1024L  // 2 MB: a ride with the bike logs every % and every event
        private const val LOGCAT_TAG     = "BoschData"
    }

    init {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "bosch_data.log")
        log("SYS", "=== BoschData avviato ===")
        log("SYS", "Log: ${file.absolutePath}")
    }

    /** Scrive una riga timestampata. Thread-safe. */
    @Synchronized
    fun log(tag: String, message: String) {
        Log.d(LOGCAT_TAG, "[${tag.trim()}] $message")
        runCatching {
            if (file.length() > MAX_SIZE_BYTES) rotate()
            file.appendText("${fmt.format(Date())} [${tag.padEnd(5)}] $message\n")
            writeFailLogged = false
        }.onFailure { e ->
            if (!writeFailLogged) {
                writeFailLogged = true
                Log.e(LOGCAT_TAG, "Scrittura log su file fallita: ${file.absolutePath}", e)
            }
        }
    }

    /** Come [log], ma accoda anche lo stacktrace dell'eccezione. */
    fun logError(tag: String, message: String, t: Throwable) {
        log(tag, "$message: ${t::class.simpleName}: ${t.message}")
        t.stackTraceToString().lineSequence()
            .filter { it.isNotBlank() }
            .take(15)
            .forEach { log(tag, "    ${it.trim()}") }
    }

    private fun rotate() {
        val old = File(file.parent, "bosch_data.old.log")
        old.delete()
        file.renameTo(old)
        file.createNewFile()
        file.appendText("${fmt.format(Date())} [SYS  ] === Log ruotato ===\n")
    }
}
