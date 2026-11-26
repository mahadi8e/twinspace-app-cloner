package top.niunaijun.blackboxa.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import top.niunaijun.blackboxa.view.main.BlackBoxLoader


object AppManager {
    private const val TAG = "AppManager"

    @JvmStatic
    val mBlackBoxLoader by lazy {
        try {
            BlackBoxLoader()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating BlackBoxLoader: ${e.message}")

            BlackBoxLoader() 
        }
    }

    @JvmStatic
    val mRemarkSharedPreferences: SharedPreferences by lazy {
        try {
            App.getContext().getSharedPreferences("UserRemark", Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating SharedPreferences: ${e.message}")
            throw e 
        }
    }

    fun doAttachBaseContext(context: Context) {
        Log.e("TS_BOOT", "AppManager.doAttachBaseContext start")
        try {
            mBlackBoxLoader.attachBaseContext(context)
            mBlackBoxLoader.addLifecycleCallback()
        } catch (e: Throwable) {
            Log.e("TS_BOOT", "AppManager.doAttachBaseContext FAILED: " + e)
            Log.e(TAG, "Error in doAttachBaseContext: ${e.message}")

        }
        Log.e("TS_BOOT", "AppManager.doAttachBaseContext done")
    }

    fun doOnCreate(context: Context) {
        Log.e("TS_BOOT", "AppManager.doOnCreate start")
        try {
            mBlackBoxLoader.doOnCreate(context)
        } catch (e: Throwable) {
            Log.e("TS_BOOT", "AppManager.doOnCreate FAILED: " + e)
            Log.e(TAG, "Error in doOnCreate: ${e.message}")

        }
        Log.e("TS_BOOT", "AppManager.doOnCreate done")
    }
}
