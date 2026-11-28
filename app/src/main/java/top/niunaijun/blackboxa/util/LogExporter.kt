package top.niunaijun.blackboxa.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TwinSpace, 2026-11-28: "Export log" (Settings).
 *
 * Dumps this app's own TS_BOOT / TS_ACCT log lines plus the crash
 * buffer to a file in app-private storage
 * (getCacheDir()/twinspace-logs/) and shares it through a
 * FileProvider, so the log can be sent without a PC. It used to be
 * debug-only; it is now shown in release builds too because Md has
 * no PC and this is the only way to get a guest-crash log off the
 * phone.
 *
 * Privacy: the file lives in app-private cache only — never public
 * Downloads, never external storage — and it is shared only through
 * the share sheet the user chooses. Nothing is uploaded anywhere.
 *
 * An app can read its OWN logs via `logcat -d` without READ_LOGS
 * (that permission is only needed for other UIDs' logs), and the
 * host, :black server and guest processes all run under the host UID,
 * so their TS_BOOT / TS_ACCT lines are all readable here.
 */
object LogExporter {

    private const val TAG = "LogExporter"

    /**
     * TwinSpace, 2026-11-28: caps for the exported file. The log
     * buffer holds hours of lines and the exported file grows with
     * every export -- Md uploads it over chat, so it must stay
     * small. Only the most recent lines are kept; they are the ones
     * that matter for diagnosis.
     */
    private const val MAX_LOG_LINES = 800
    private const val MAX_CRASH_LINES = 150

    /**
     * Writes the TS_BOOT / TS_ACCT lines to
     * getCacheDir()/twinspace-logs/twinspace-log-<timestamp>.txt and
     * returns the file, or null on failure. When logcat returns no
     * matching lines (e.g. the buffer was cleared), the file is still
     * written with a note saying so, plus the time.
     *
     * Must be called off the main thread: `logcat` and file I/O block.
     */
    fun export(context: Context): File? {
        return try {
            val lines = readLogLines()
            val crashLines = readCrashLines()
            val dir = File(context.cacheDir, "twinspace-logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "twinspace-log-$stamp.txt")
            val header = StringBuilder()
                .append("TwinSpace log export — ")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                .append("\nTags: TS_BOOT, TS_ACCT\n")
            if (lines.isEmpty()) {
                header.append("No TS_BOOT / TS_ACCT lines were captured — ")
                    .append("the log buffer may have been cleared, or the app ")
                    .append("has not logged anything under these tags since boot.\n")
            }
            header.append("\n")
            val body = StringBuilder(header)
                .append(lines.joinToString("\n"))
            // The crash buffer is where the actual crash lives: a Java
            // crash shows as FATAL EXCEPTION (AndroidRuntime) and a
            // native crash as "Fatal signal" (DEBUG). Neither is ever
            // in the TS_BOOT / TS_ACCT lines above, so without this
            // section a guest that dies before handleBindApplication
            // leaves no clue why it died.
            body.append("\n\n--------- beginning of crash\n")
            if (crashLines.isEmpty()) {
                body.append("No crash-buffer lines were captured.\n")
            } else {
                body.append(crashLines.joinToString("\n"))
            }
            body.append("\n")
            file.writeText(body.toString())
            file
        } catch (e: Exception) {
            Log.e(TAG, "Export log failed: ${e.message}")
            null
        }
    }

    /**
     * TwinSpace, 2026-11-28: "Clear log" (Settings). Deletes the old
     * logs so the next export stays small and easy to upload.
     *
     * The exported file grows because the crash buffer keeps every
     * old crash (hours of FATAL EXCEPTIONs pile up), and Md uploads
     * the file over chat. Clearing wipes the device's log buffers
     * (`logcat -c`, main + crash) and deletes the previously
     * exported .txt files in getCacheDir()/twinspace-logs/, so the
     * next export only has fresh lines.
     *
     * `logcat -c` is best-effort: clearing the buffer can fail
     * without READ_LOGS (an app may only read its own logs). The old
     * exported files are always deleted, and the export itself caps
     * its sections (see below), so the file stays small even when
     * the buffer clear is refused.
     *
     * Must be called off the main thread: `logcat` and file I/O block.
     * Returns true when the log buffer was cleared.
     */
    fun clear(context: Context): Boolean {
        var cleared = false
        try {
            // -c clears the buffer; -b crash clears the crash buffer.
            val main = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
            main.waitFor()
            val crash = Runtime.getRuntime().exec(arrayOf("logcat", "-b", "crash", "-c"))
            crash.waitFor()
            cleared = main.exitValue() == 0 && crash.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "logcat -c failed: ${e.message}")
        }
        // Always delete the previously exported files, even when the
        // buffer clear was refused -- they are the files Md uploads.
        try {
            val dir = File(context.cacheDir, "twinspace-logs")
            dir.listFiles()?.forEach { file ->
                try { file.delete() } catch (e: Exception) { /* keep going */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleting old log files failed: ${e.message}")
        }
        return cleared
    }

    /**
     * Shares [file] with an ACTION_SEND intent through the app's
     * FileProvider (${applicationId}.fileprovider). Returns false if
     * the share could not be started.
     *
     * NOTE: callers pass the APPLICATION context (SettingFragment
     * uses applicationContext), so the chooser intent MUST carry
     * FLAG_ACTIVITY_NEW_TASK — without it, startActivity() from a
     * non-Activity context throws AndroidRuntimeException and the
     * share silently fails with "Could not export the log".
     * (Fixed 2026-11-28 ~19:35 +06: the first release build with
     * Export log failed exactly this way.)
     */
    fun share(context: Context, file: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share TwinSpace log").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Share log failed: ${e.message}")
            false
        }
    }

    /**
     * Reads the log buffer, keeping only TS_BOOT / TS_ACCT lines.
     *
     * TwinSpace, 2026-11-28: capped to the most recent 800 lines.
     * The buffer holds hours of lines, and the exported file grows
     * with every export -- Md uploads it over chat, so a huge file
     * is a problem. The most recent lines are the ones that matter
     * for diagnosis (the sign-in attempts are minutes old), so older
     * lines are dropped.
     */
    private fun readLogLines(): List<String> {
        val out = ArrayList<String>()
        try {
            // -s keeps only these two tags; -d dumps and exits.
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-s", "TS_BOOT", "TS_ACCT")
            )
            proc.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    if (line.isNotBlank()) out.add(line)
                }
            }
            proc.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "logcat failed: ${e.message}")
        }
        return if (out.size > MAX_LOG_LINES) out.subList(out.size - MAX_LOG_LINES, out.size) else out
    }

    /**
     * Reads the crash buffer (-b crash), which holds the actual crash
     * for this UID: FATAL EXCEPTION (Java) and "Fatal signal"
     * (native). An app can read its own crashes here without
     * READ_LOGS, and the guest processes run under the host UID, so
     * their crashes are included. Returns an empty list on failure.
     *
     * TwinSpace, 2026-11-28: capped to the most recent 150 lines.
     * The crash buffer is the part that grows fastest -- every old
     * crash (hours back) is kept, and the same crash repeats dozens
     * of times. Only the most recent crashes matter, and capping
     * keeps the exported file small enough to upload.
     */
    private fun readCrashLines(): List<String> {
        val out = ArrayList<String>()
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-b", "crash", "-v", "time")
            )
            proc.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    if (line.isNotBlank()) out.add(line)
                }
            }
            proc.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "logcat -b crash failed: ${e.message}")
        }
        return if (out.size > MAX_CRASH_LINES) out.subList(out.size - MAX_CRASH_LINES, out.size) else out
    }
}
