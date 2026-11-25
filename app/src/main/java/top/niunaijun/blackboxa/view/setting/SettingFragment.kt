package top.niunaijun.blackboxa.view.setting

import android.os.Bundle
import android.text.InputType
import android.util.Log
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity
import top.niunaijun.blackboxa.vpn.TwinSpaceVpn

class SettingFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.setting, rootKey)

        initGms()

        initVpnConfig()

        invalidHideState {
            val rootHidePreference: Preference = (findPreference("root_hide")!!)
            val hideRoot = AppManager.mBlackBoxLoader.hideRoot()
            rootHidePreference.setDefaultValue(hideRoot)
            rootHidePreference
        }

        invalidHideState {
            val daemonPreference: Preference = (findPreference("daemon_enable")!!)
            val mDaemonEnable = AppManager.mBlackBoxLoader.daemonEnable()
            daemonPreference.setDefaultValue(mDaemonEnable)
            daemonPreference
        }

        invalidHideState {
            val vpnPreference: Preference = (findPreference("use_vpn_network")!!)
            val mUseVpnNetwork = AppManager.mBlackBoxLoader.useVpnNetwork()
            vpnPreference.setDefaultValue(mUseVpnNetwork)
            vpnPreference
        }

        invalidHideState {
            val disableFlagSecurePreference: Preference = (findPreference("disable_flag_secure")!!)
            val mDisableFlagSecure = AppManager.mBlackBoxLoader.disableFlagSecure()
            disableFlagSecurePreference.setDefaultValue(mDisableFlagSecure)
            disableFlagSecurePreference
        }

    }

    private fun initGms() {
        val gmsManagerPreference: Preference = (findPreference("gms_manager")!!)

        if (BlackBoxCore.get().isSupportGms) {

            gmsManagerPreference.setOnPreferenceClickListener {
                GmsManagerActivity.start(requireContext())
                true
            }
        } else {
            gmsManagerPreference.summary = getString(R.string.no_gms)
            gmsManagerPreference.isEnabled = false
        }
    }

    /**
     * USA VPN config: the user's own WireGuard .conf (free Proton VPN, USA
     * server). Without it the VPN cannot connect — the toggle alone does
     * nothing. The summary shows whether a config is saved.
     */
    private fun initVpnConfig() {
        val vpnConfigPreference: Preference = (findPreference("vpn_config")!!)
        updateVpnConfigSummary(vpnConfigPreference)
        vpnConfigPreference.setOnPreferenceClickListener {
            showVpnConfigDialog(vpnConfigPreference)
            true
        }
    }

    private fun updateVpnConfigSummary(pref: Preference) {
        pref.summary =
            if (TwinSpaceVpn.hasConfig(requireContext())) getString(R.string.vpn_config_set)
            else getString(R.string.vpn_config_summary)
    }

    private fun showVpnConfigDialog(pref: Preference) {
        val ctx = requireContext()
        var text = TwinSpaceVpn.getConfig(ctx) ?: ""
        try {
            MaterialDialog(ctx).show {
                title(R.string.vpn_config)
                message(R.string.vpn_config_hint)
                input(
                    prefill = text,
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                    allowEmpty = true
                ) { _, input -> text = input.toString() }
                positiveButton(R.string.vpn_config_save) {
                    TwinSpaceVpn.saveConfig(ctx, text)
                    updateVpnConfigSummary(pref)
                    toast(R.string.vpn_config_saved)
                }
                negativeButton(R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing VPN config dialog: ${e.message}")
        }
    }

    private fun invalidHideState(block: () -> Preference) {
        val pref = block()
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val tmpHide = (newValue == true)
            var showRestartToast = true
            when (preference.key) {
                "root_hide" -> {

                    AppManager.mBlackBoxLoader.invalidHideRoot(tmpHide)
                }
                "daemon_enable" -> {
                    AppManager.mBlackBoxLoader.invalidDaemonEnable(tmpHide)
                }
                "use_vpn_network" -> {
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(tmpHide)
                    // The VPN switch applies immediately — no restart needed.
                    // The flag stays on even without a config, so the tunnel
                    // auto-starts once the user pastes one in VPN config.
                    showRestartToast = false
                    if (tmpHide) {
                        TwinSpaceVpn.start(requireContext()) { ok, err ->
                            when {
                                ok -> toast(R.string.vpn_started)
                                err == "no_config" -> toast(R.string.vpn_no_config)
                                err == "not_authorized" -> toast(R.string.vpn_not_authorized)
                                else -> toast(R.string.vpn_start_failed)
                            }
                        }
                    } else {
                        TwinSpaceVpn.stop(requireContext()) { ok, _ ->
                            if (ok) toast(R.string.vpn_stopped)
                        }
                    }
                }
                "disable_flag_secure" -> {
                    AppManager.mBlackBoxLoader.invalidDisableFlagSecure(tmpHide)
                }
            }

            if (showRestartToast) {
                toast(R.string.restart_module)
            }
            return@setOnPreferenceChangeListener true
        }
    }

    companion object {
        private const val TAG = "SettingFragment"
    }
}

