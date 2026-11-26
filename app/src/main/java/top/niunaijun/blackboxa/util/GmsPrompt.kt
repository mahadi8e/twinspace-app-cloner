package top.niunaijun.blackboxa.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R

/**
 * Clone-time Google services prompt (TwinSpace addition, 2026-11-19).
 *
 * The manual per-space install in Settings -> GMS Manager is unchanged. This
 * helper only ADDS an offer: after an app is cloned into a space that does
 * not have Google services yet, it asks whether to install them (Play Store,
 * Google Play Services, Google Services Framework) into that space. Nothing
 * is ever installed without the user tapping Install.
 *
 * Engine checks and the install itself run on a background thread (they are
 * IPC into the BlackBox server process); the dialog and toasts are posted
 * back to the main thread, mirroring how the clone flow's repository posts
 * its results through LiveData.
 */
object GmsPrompt {

    private const val TAG = "GmsPrompt"

    /**
     * Google app/service packages, mirroring GmsCore.GOOGLE_APP +
     * GmsCore.GOOGLE_SERVICE in :Bcore (those sets are private to the
     * engine, so they are duplicated here). Cloning one of these packages
     * itself never triggers the prompt.
     */
    private val GOOGLE_PACKAGES =
            setOf(
                    // GmsCore.GOOGLE_APP
                    "com.android.vending",
                    "com.google.android.play.games",
                    "com.google.android.wearable.app",
                    "com.google.android.wearable.app.cn",
                    // GmsCore.GOOGLE_SERVICE
                    "com.google.android.gms",
                    "com.google.android.gsf",
                    "com.google.android.gsf.login",
                    "com.google.android.backuptransport",
                    "com.google.android.backup",
                    "com.google.android.configupdater",
                    "com.google.android.syncadapters.contacts",
                    "com.google.android.feedback",
                    "com.google.android.onetimeinitializer",
                    "com.google.android.partnersetup",
                    "com.google.android.setupwizard",
                    "com.google.android.syncadapters.calendar"
            )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Call after a SUCCESSFUL clone of [clonedPackage] into space [userId].
     * Shows the prompt only when the host phone supports GMS and the space
     * does not have Google services installed yet.
     *
     * [onGmsInstalled] runs on the main thread right after a successful
     * GMS install, so the caller can refresh the Space grid live instead
     * of making the user leave and re-enter the screen to see the new
     * Google apps.
     */
    fun maybePrompt(
            activity: Activity,
            userId: Int,
            clonedPackage: String,
            onGmsInstalled: (() -> Unit)? = null
    ) {
        if (GOOGLE_PACKAGES.contains(clonedPackage)) {
            return
        }
        Thread {
            val shouldPrompt =
                    try {
                        val core = BlackBoxCore.get()
                        core.isSupportGms() && !core.isInstallGms(userId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking GMS state for user $userId: ${e.message}")
                        false
                    }
            if (shouldPrompt) {
                mainHandler.post { showPrompt(activity, userId, onGmsInstalled) }
            }
        }.start()
    }

    private fun showPrompt(activity: Activity, userId: Int, onGmsInstalled: (() -> Unit)?) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        try {
            MaterialDialog(activity).show {
                title(R.string.gms_prompt_title)
                message(R.string.gms_prompt_message)
                positiveButton(R.string.gms_prompt_install) { installGms(userId, onGmsInstalled) }
                negativeButton(R.string.gms_prompt_not_now)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing GMS prompt: ${e.message}")
        }
    }

    private fun installGms(userId: Int, onGmsInstalled: (() -> Unit)?) {
        toast(R.string.gms_installing)
        Thread {
            val result =
                    try {
                        BlackBoxCore.get().installGms(userId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error installing GMS for user $userId: ${e.message}")
                        null
                    }
            mainHandler.post {
                when {
                    result == null -> toast(R.string.install_fail_no_msg)
                    result.success -> {
                        toast(R.string.install_success)
                        // Refresh the Space grid live so the newly installed
                        // Google apps (Play Store, Play Services, ...) show
                        // immediately, without leaving and re-entering.
                        onGmsInstalled?.invoke()
                    }
                    else -> toast(getString(R.string.install_fail, result.msg ?: ""))
                }
            }
        }.start()
    }
}
