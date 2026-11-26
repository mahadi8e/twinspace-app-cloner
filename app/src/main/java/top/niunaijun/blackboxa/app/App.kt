package top.niunaijun.blackboxa.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.app.rocker.RockerManager
import top.niunaijun.blackboxa.data.AppsRepository
import top.niunaijun.blackboxa.util.CrashReporter


class App : Application() {

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private lateinit var mContext: Context

        @JvmStatic
        fun getContext(): Context {
            return mContext
        }
    }

    /**
     * TwinSpace: true only in the main process. CrashReporter and the
     * "skip engine init when a report exists" shortcut must run ONLY
     * there — the :black server and :p0..:pN guest processes share the
     * same filesDir, so the old code skipped engine init in EVERY
     * process whenever a report existed, and every process installed
     * its own handler and marker on the same files. Guest/server
     * processes must always run full init.
     */
    private fun isMainProcess(context: Context): Boolean {
        val processName = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                // Pre-P: read /proc/self/cmdline (first NUL-terminated token).
                val bytes = File("/proc/self/cmdline").readBytes()
                val end = bytes.indexOf(0.toByte()).takeIf { it >= 0 } ?: bytes.size
                String(bytes, 0, end, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
        // Unknown process name: assume NOT main, so the engine always
        // runs full init (the safe side for guest/server processes).
        return processName != null && processName == context.packageName
    }

    override fun attachBaseContext(base: Context?) {
        try {
            super.attachBaseContext(base)

            // TwinSpace diagnostic (MAIN process only — see isMainProcess):
            // catch startup crashes and remember them, so the next launch
            // can show the exact cause instead of crashing again before
            // anyone can see it.
            val mainProcess = base != null && isMainProcess(base)
            Log.e("TS_BOOT", "attachBaseContext start main=" + mainProcess)
            if (mainProcess) {
                try {
                    CrashReporter.install(this)
                } catch (e: Throwable) {
                    Log.e("App", "Error installing crash reporter: ${e.message}")
                }
                try {
                    CrashReporter.markStarted(this)
                } catch (e: Throwable) {
                    Log.e("App", "Error marking launch start: ${e.message}")
                }
                // If the previous launch crashed, skip the heavy engine init
                // so MainActivity can show the saved crash report instead of
                // crashing again before the dialog appears.
                try {
                    if (CrashReporter.hasReport(this)) {
                        Log.e("TS_BOOT", "attachBaseContext skipping engine init (crash report exists)")
                        mContext = base!!
                        return
                    }
                } catch (e: Throwable) {
                    Log.e("App", "Error checking crash report: ${e.message}")
                }
            }

            try {
                BlackBoxCore.get().closeCodeInit()
            } catch (e: Throwable) {
                Log.e("TS_BOOT", "closeCodeInit FAILED: " + e)
                Log.e("App", "Error in closeCodeInit: ${e.message}")
            }

            try {
                BlackBoxCore.get().onBeforeMainApplicationAttach(this, base)
            } catch (e: Throwable) {
                Log.e("TS_BOOT", "onBeforeMainApplicationAttach FAILED: " + e)
                Log.e("App", "Error in onBeforeMainApplicationAttach: ${e.message}")
            }

            mContext = base!!

            Log.e("TS_BOOT", "AppManager.doAttachBaseContext start")
            try {
                AppManager.doAttachBaseContext(base)
            } catch (e: Throwable) {
                Log.e("TS_BOOT", "AppManager.doAttachBaseContext FAILED: " + e)
                Log.e("App", "Error in doAttachBaseContext: ${e.message}")
            }
            Log.e("TS_BOOT", "AppManager.doAttachBaseContext done")

            try {

                BlackBoxCore.get().onAfterMainApplicationAttach(this, base)

            } catch (e: Throwable) {

                Log.e("TS_BOOT", "onAfterMainApplicationAttach FAILED: " + e)
                Log.e("App", "Error in onAfterMainApplicationAttach: ${e.message}")

            }
            Log.e("TS_BOOT", "attachBaseContext done")
        } catch (e: Throwable) {
            Log.e("TS_BOOT", "attachBaseContext FAILED: " + e)
            Log.e("App", "Critical error in attachBaseContext: ${e.message}")
            if (base != null) {
                mContext = base
            }
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            Log.e("TS_BOOT", "onCreate start main=" + isMainProcess(this))
            // Previous launch crashed; MainActivity will show the report.
            // Skip the engine init so it can actually appear. Main
            // process only — guest/server processes always init fully.
            if (isMainProcess(this) && CrashReporter.hasReport(this)) {
                Log.e("TS_BOOT", "onCreate skipping engine init (crash report exists)")
                return
            }
            AppManager.doOnCreate(mContext)
            Log.e("TS_BOOT", "onCreate done")
        } catch (e: Throwable) {
            Log.e("TS_BOOT", "onCreate FAILED: " + e)
            Log.e("App", "Error in onCreate: ${e.message}")
        }
    }

    /**
     * TwinSpace: the host app previously never released anything when the
     * system ran low on memory. When memory pressure is moderate or worse,
     * drop the cached app icons (they reload from PackageManager on the
     * next refresh); at complete level also unregister the floating
     * rocker's activity callbacks, since the process may be killed anyway.
     * Cloned apps are untouched: their own trim events reach them through
     * the engine's dispatchers.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                AppsRepository.clearIconCache()
                Log.d("App", "Trim memory level $level: cleared icon cache")
            }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                RockerManager.cleanup()
            }
        } catch (e: Exception) {
            Log.e("App", "Error in onTrimMemory: ${e.message}")
        }
    }
}