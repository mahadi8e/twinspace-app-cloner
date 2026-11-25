package top.niunaijun.blackboxa.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TwinSpace startup-crash diagnostic (added 2026-11-25).
 *
 * The app was crashing a few seconds after launch (splash, then straight
 * back to the home screen) with no visible error, so the exact cause could
 * not be seen from the outside. This reporter catches any uncaught Java
 * error, saves the full stack trace plus the device/Android details, and
 * on the next launch MainActivity shows it in a dialog with a Share
 * button instead of starting normally (the normal start is what crashed).
 *
 * A crash that kills the process natively (a signal in a .so, no Java
 * exception) never reaches this handler. For that case a "started" marker
 * file is written at the very beginning of every launch and removed only
 * once MainActivity has finished starting: if the marker is still there
 * on the next launch, the previous launch died natively, and MainActivity
 * shows that instead, with the device details.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val REPORT_FILE = "crash-report.txt"
    private const val MARKER_FILE = "crash-marker.txt"
    private const val DOWNLOADS_FILE = "TwinSpace-crash-report.txt"

    /** True when the previous launch died before MainActivity finished starting. */
    @Volatile
    var previousLaunchDied = false
        private set

    fun deviceInfo(context: Context): String {
        val appVersion = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        } catch (e: Exception) {
            "unknown"
        }
        return "TwinSpace $appVersion\n" +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
            "Device ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Time " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    /**
     * Install the handler that saves the crash. Call this first, before any
     * other init. The previous default handler is still invoked afterwards,
     * so the system behaves exactly as before (the process still dies).
     */
    fun install(context: Context) {
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    saveReport(context.applicationContext, throwable)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save crash report: ${e.message}")
                }
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in previous handler: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install crash handler: ${e.message}")
        }
    }

    private fun saveReport(context: Context, throwable: Throwable) {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val text = deviceInfo(context) + "\n\n" + writer.toString()
        try {
            File(context.filesDir, REPORT_FILE).writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write report file: ${e.message}")
        }
        // Best effort: also leave a copy in Downloads, so the file can be
        // found in the Files app even if the dialog never appears. This
        // needs the storage permission, so it may silently fail on the
        // very first crash; the dialog's Share button is the reliable path.
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloads, DOWNLOADS_FILE).writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write Downloads copy: ${e.message}")
        }
    }

    fun hasReport(context: Context): Boolean =
        try {
            File(context.filesDir, REPORT_FILE).exists()
        } catch (e: Exception) {
            false
        }

    fun readReport(context: Context): String =
        try {
            File(context.filesDir, REPORT_FILE).readText()
        } catch (e: Exception) {
            "Could not read crash report: ${e.message}"
        }

    fun clearReport(context: Context) {
        try {
            File(context.filesDir, REPORT_FILE).delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Record that this launch has started. Returns true when a marker from
     * a previous launch was still present, meaning that launch died before
     * MainActivity finished starting (native crash or the process was
     * killed). Call [markClean] once MainActivity has started fine.
     */
    fun markStarted(context: Context): Boolean {
        val marker = File(context.filesDir, MARKER_FILE)
        val died = try {
            marker.exists()
        } catch (e: Exception) {
            false
        }
        previousLaunchDied = died
        try {
            marker.writeText("started")
        } catch (e: Exception) {
            // ignore
        }
        return died
    }

    fun markClean(context: Context) {
        try {
            File(context.filesDir, MARKER_FILE).delete()
        } catch (e: Exception) {
            // ignore
        }
        previousLaunchDied = false
    }

    fun clearMarker(context: Context) = markClean(context)

    fun shareReport(context: Context, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "TwinSpace crash report")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(intent, "Share crash report"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share crash report: ${e.message}")
        }
    }
}
