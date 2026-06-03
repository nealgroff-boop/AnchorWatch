package com.anchorwatch.app.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * AnchorWatch diagnostic logger.
 *
 * Writes timestamped entries to  <filesDir>/logs/anchorwatch.log
 * Rotation: when the file exceeds MAX_BYTES it is renamed to anchorwatch.log.1
 * and a fresh file is started.  Only one backup is kept, so total disk use
 * is capped at 2 × MAX_BYTES (≈ 10 MB).
 *
 * Thread-safe.  All writes go through a single ReentrantLock so there is no
 * interleaving between the GPS/IMU threads.
 *
 * Usage:
 *   AnchorLogger.init(context)          // call once from Application.onCreate()
 *   AnchorLogger.enabled = true/false   // toggled from the menu
 *   AnchorLogger.log("TAG", "message")  // write an entry
 *   AnchorLogger.logFile                // File reference for sharing/reading
 */
object AnchorLogger {

    private const val TAG        = "AnchorLogger"
    private const val LOG_DIR    = "logs"
    private const val LOG_FILE   = "anchorwatch.log"
    private const val BACKUP     = "anchorwatch.log.1"
    private const val MAX_BYTES  = 5 * 1024 * 1024L   // 5 MB per file, 10 MB total

    private val lock      = ReentrantLock()
    private val _enabled  = AtomicBoolean(false)
    private val dateFmt   = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private lateinit var logFile: File
    private lateinit var backupFile: File
    private var writer: PrintWriter? = null
    private var initialised = false

    /** Whether logging is active.  Setting this persists until the next init(). */
    var enabled: Boolean
        get()  = _enabled.get()
        set(v) {
            _enabled.set(v)
            if (v) {
                log("LOGGER", "─── Logging ENABLED ───")
            } else {
                lock.withLock {
                    writer?.flush()
                }
                Log.i(TAG, "Logging disabled")
            }
        }

    /**
     * Initialise the logger.  Safe to call multiple times; subsequent calls
     * are no-ops unless [forceReinit] is true.
     */
    fun init(context: Context, forceReinit: Boolean = false) {
        if (initialised && !forceReinit) return
        lock.withLock {
            val dir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
            logFile    = File(dir, LOG_FILE)
            backupFile = File(dir, BACKUP)
            openWriter()
            initialised = true
            Log.i(TAG, "Logger initialised → ${logFile.absolutePath}")
        }
    }

    /**
     * Write a log entry.  Does nothing if logging is disabled or not initialised.
     * Also mirrors to Android logcat at DEBUG level.
     */
    fun log(tag: String, message: String) {
        Log.d(tag, message)
        if (!_enabled.get() || !initialised) return
        lock.withLock {
            rotateIfNeeded()
            val ts = dateFmt.format(Date())
            writer?.println("$ts  [$tag]  $message")
            writer?.flush()
        }
    }

    /** Flush and close — call from Application.onTerminate() or tests. */
    fun close() {
        lock.withLock {
            writer?.flush()
            writer?.close()
            writer = null
        }
    }

    /** The current log file, for sharing via intent. */
    fun getLogFile(): File = logFile

    // ── Private ───────────────────────────────────────────────────────────────

    private fun openWriter() {
        try {
            writer?.close()
            writer = PrintWriter(FileWriter(logFile, true /* append */))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open log file: ${e.message}")
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_BYTES) return
        Log.i(TAG, "Rotating log (${logFile.length() / 1024} KB)")
        writer?.close()
        writer = null
        if (backupFile.exists()) backupFile.delete()
        logFile.renameTo(backupFile)
        openWriter()
        writer?.println("─── Log rotated ${dateFmt.format(Date())} " +
                         "(previous → ${BACKUP}) ───")
    }
}
