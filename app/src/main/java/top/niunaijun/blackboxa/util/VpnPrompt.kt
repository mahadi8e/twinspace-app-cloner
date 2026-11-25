package top.niunaijun.blackboxa.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.vpn.TwinSpaceVpn

/**
 * Clone-time USA VPN prompt (TwinSpace addition, 2026-11-25).
 *
 * Mirrors [GmsPrompt]: after an app is cloned, it offers to turn the USA VPN
 * on for the clone (USA IP + California location). Nothing is ever turned on
 * without the user tapping "Turn on VPN".
 *
 * If the VPN is already on, there is no dialog — the new clone simply gets
 * the California location, like every other clone.
 */
object VpnPrompt {

    private const val TAG = "VpnPrompt"

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Call after a SUCCESSFUL clone of [clonedPackage] into space [userId].
     */
    fun maybePrompt(activity: Activity, userId: Int, clonedPackage: String) {
        if (AppManager.mBlackBoxLoader.useVpnNetwork()) {
            TwinSpaceVpn.applyCaliforniaLocationTo(userId, clonedPackage)
            return
        }
        mainHandler.post { showPrompt(activity, userId, clonedPackage) }
    }

    private fun showPrompt(activity: Activity, userId: Int, clonedPackage: String) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        try {
            MaterialDialog(activity).show {
                title(R.string.vpn_prompt_title)
                message(R.string.vpn_prompt_message)
                positiveButton(R.string.vpn_prompt_turn_on) { turnOn(activity, userId, clonedPackage) }
                negativeButton(R.string.vpn_prompt_not_now)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing VPN prompt: ${e.message}")
        }
    }

    private fun turnOn(activity: Activity, userId: Int, clonedPackage: String) {
        AppManager.mBlackBoxLoader.invalidUseVpnNetwork(true)
        TwinSpaceVpn.applyCaliforniaLocationTo(userId, clonedPackage)
        if (!TwinSpaceVpn.hasConfig(activity)) {
            toast(R.string.vpn_no_config)
            return
        }
        TwinSpaceVpn.start(activity) { ok, err ->
            when {
                ok -> toast(R.string.vpn_started)
                err == "not_authorized" -> toast(R.string.vpn_not_authorized)
                else -> toast(R.string.vpn_start_failed)
            }
        }
    }
}
