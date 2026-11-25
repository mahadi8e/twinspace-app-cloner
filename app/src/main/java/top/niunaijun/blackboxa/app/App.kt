package top.niunaijun.blackboxa.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
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

    override fun attachBaseContext(base: Context?) {
        try {
            super.attachBaseContext(base)

            // TwinSpace diagnostic: catch startup crashes and remember them,
            // so the next launch can show the exact cause instead of
            // crashing again before anyone can see it.
            try {
                CrashReporter.install(this)
            } catch (e: Exception) {
                Log.e("App", "Error installing crash reporter: ${e.message}")
            }
            try {
                CrashReporter.markStarted(this)
            } catch (e: Exception) {
                Log.e("App", "Error marking launch start: ${e.message}")
            }
            // If the previous launch crashed, skip the heavy engine init
            // so MainActivity can show the saved crash report instead of
            // crashing again before the dialog appears.
            if (base != null) {
                try {
                    if (CrashReporter.hasReport(this)) {
                        mContext = base
                        return
                    }
                } catch (e: Exception) {
                    Log.e("App", "Error checking crash report: ${e.message}")
                }
            }

            try {
                BlackBoxCore.get().closeCodeInit()
            } catch (e: Exception) {
                Log.e("App", "Error in closeCodeInit: ${e.message}")
            }

            try {
                BlackBoxCore.get().onBeforeMainApplicationAttach(this, base)
            } catch (e: Exception) {
                Log.e("App", "Error in onBeforeMainApplicationAttach: ${e.message}")
            }

            mContext = base!!

            try {
                AppManager.doAttachBaseContext(base)
            } catch (e: Exception) {
                Log.e("App", "Error in doAttachBaseContext: ${e.message}")
            }

            try {

                BlackBoxCore.get().onAfterMainApplicationAttach(this, base)

            } catch (e: Exception) {

                Log.e("App", "Error in onAfterMainApplicationAttach: ${e.message}")

            }
        } catch (e: Exception) {
            Log.e("App", "Critical error in attachBaseContext: ${e.message}")
            if (base != null) {
                mContext = base
            }
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            // Previous launch crashed; MainActivity will show the report.
            // Skip the engine init so it can actually appear.
            if (CrashReporter.hasReport(this)) {
                return
            }
            AppManager.doOnCreate(mContext)
        } catch (e: Exception) {
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