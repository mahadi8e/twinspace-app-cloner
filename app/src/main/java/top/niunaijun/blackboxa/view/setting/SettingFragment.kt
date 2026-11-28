package top.niunaijun.blackboxa.view.setting

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.BuildConfig
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.location.TwinSpaceLocation
import top.niunaijun.blackboxa.util.LogExporter
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity
import top.niunaijun.blackboxa.vpn.TwinSpaceVpn

class SettingFragment : PreferenceFragmentCompat() {

    /**
     * TwinSpace, 2026-11-27: the system VPN consent dialog, launched from
     * Settings when the user turns the VPN on. A fresh install wipes the
     * VPN consent, so VpnService.prepare() returns a non-null Intent and
     * TwinSpaceVpn.start() can only report "not_authorized" — it never
     * launches the consent dialog itself. Previously the toggle just
     * toasted vpn_not_authorized and left the switch ON with the tunnel
     * down, and the only place the dialog ever appeared was
     * MainActivity.checkVpnPermission() on a cold start. Now the toggle
     * asks for consent right here: on RESULT_OK the tunnel starts, and
     * on denial the switch reverts to OFF so it stops claiming the VPN
     * is on.
     */
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                if (result.resultCode == Activity.RESULT_OK) {
                    Log.d(TAG, "VPN permission granted from Settings")
                    startVpn()
                } else {
                    Log.w(TAG, "VPN permission denied by user")
                    // Revert the switch so it stops claiming the VPN is on.
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(false)
                    try {
                        val pref = findPreference<SwitchPreferenceCompat>("use_vpn_network")
                        pref?.isChecked = false
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reverting VPN switch: ${e.message}")
                    }
                    refreshVpnStatus()
                    toast(R.string.vpn_not_authorized)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling VPN permission result: ${e.message}")
            }
        }

    /**
     * Start the VPN, asking for the system VPN consent first when it has
     * not been granted yet (fresh install, or consent revoked). When the
     * consent dialog is shown, the tunnel starts from the launcher
     * callback above; otherwise it starts immediately.
     */
    private fun startVpnWithPermission() {
        val ctx = requireContext()
        try {
            val intent = VpnService.prepare(ctx)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing VPN: ${e.message}")
        }
        startVpn()
    }

    private fun startVpn() {
        TwinSpaceVpn.start(requireContext()) { ok, err ->
            refreshVpnStatus()
            when {
                ok -> toast(R.string.vpn_started)
                err == "no_config" -> toast(R.string.vpn_no_config)
                err == "not_authorized" -> {
                    // Consent was revoked between prepare() and start() —
                    // ask again instead of leaving the switch on with the
                    // tunnel down.
                    startVpnWithPermission()
                }
                else -> {
                    // Show the real error (e.g. a bad
                    // config) instead of a generic toast.
                    try {
                        MaterialDialog(requireContext()).show {
                            title(R.string.vpn_start_failed)
                            message(text = err ?: getString(R.string.vpn_start_failed))
                            positiveButton(R.string.done)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing VPN start error: ${e.message}")
                        toast(R.string.vpn_start_failed)
                    }
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.setting, rootKey)

        initGms()

        initVpnConfig()

        initVpnStatus()

        initFakeLocation()

        initExportLog()

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

    /**
     * VPN status line in Settings: shows whether the tunnel is really
     * connected, not just whether the switch is on. Refreshed whenever
     * the screen opens and after the VPN starts or stops.
     */
    private fun initVpnStatus() {
        val vpnStatusPreference: Preference = (findPreference("vpn_status")!!)
        updateVpnStatus(vpnStatusPreference)
    }

    private fun updateVpnStatus(pref: Preference) {
        val ctx = requireContext()
        pref.summary = when {
            !TwinSpaceVpn.hasConfig(ctx) -> getString(R.string.vpn_status_no_config)
            !AppManager.mBlackBoxLoader.useVpnNetwork() -> getString(R.string.vpn_status_off)
            TwinSpaceVpn.isRunning -> getString(R.string.vpn_status_connected)
            else -> getString(R.string.vpn_status_not_connected)
        }
    }

    private fun refreshVpnStatus() {
        try {
            val pref: Preference? = findPreference("vpn_status")
            if (pref != null) updateVpnStatus(pref)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing VPN status: ${e.message}")
        }
    }

    /**
     * Fake location, separate from the VPN: the user picks a place here
     * (a preset city or custom coordinates) and every clone gets it.
     * Turning the VPN on or off no longer changes the location.
     */
    private fun initFakeLocation() {
        val fakeLocationPreference: Preference = (findPreference("fake_location")!!)
        updateFakeLocationSummary(fakeLocationPreference)
        fakeLocationPreference.setOnPreferenceClickListener {
            showFakeLocationDialog(fakeLocationPreference)
            true
        }
    }

    private fun updateFakeLocationSummary(pref: Preference) {
        val ctx = requireContext()
        pref.summary = if (TwinSpaceLocation.isEnabled(ctx)) {
            TwinSpaceLocation.getName(ctx)
        } else {
            getString(R.string.fake_location_off)
        }
    }

    private fun refreshFakeLocation() {
        try {
            val pref: Preference? = findPreference("fake_location")
            if (pref != null) updateFakeLocationSummary(pref)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing fake location: ${e.message}")
        }
    }

    private fun showFakeLocationDialog(pref: Preference) {
        val ctx = requireContext()
        val presets = TwinSpaceLocation.PRESETS
        val items = presets.map { it.name } +
            getString(R.string.fake_location_custom) +
            getString(R.string.fake_location_off)
        val currentIndex = if (TwinSpaceLocation.isEnabled(ctx)) {
            presets.indexOfFirst { it.name == TwinSpaceLocation.getName(ctx) }
        } else {
            -1
        }
        try {
            MaterialDialog(ctx).show {
                title(R.string.fake_location_title)
                listItemsSingleChoice(items = items, initialSelection = currentIndex) { _, index, _ ->
                    when {
                        index < presets.size -> {
                            val preset = presets[index]
                            TwinSpaceLocation.setLocation(ctx, preset.latitude, preset.longitude, preset.name, preset.timezone, preset.country)
                            updateFakeLocationSummary(pref)
                            toast(getString(R.string.fake_location_set, preset.name))
                        }
                        index == presets.size -> {
                            showCustomLocationDialog(pref)
                        }
                        else -> {
                            TwinSpaceLocation.setEnabled(ctx, false)
                            updateFakeLocationSummary(pref)
                            toast(R.string.fake_location_turned_off)
                        }
                    }
                }
                positiveButton(R.string.done)
                negativeButton(R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing fake location dialog: ${e.message}")
        }
    }

    /** Custom coordinates: the user types "latitude, longitude". */
    private fun showCustomLocationDialog(pref: Preference) {
        val ctx = requireContext()
        var text = ""
        try {
            MaterialDialog(ctx).show {
                title(R.string.fake_location_custom)
                message(R.string.fake_location_custom_hint)
                input(
                    prefill = text,
                    inputType = InputType.TYPE_CLASS_TEXT,
                    allowEmpty = false
                ) { _, input -> text = input.toString() }
                positiveButton(R.string.done) {
                    val parts = text.split(",").map { it.trim() }
                    val lat = parts.getOrNull(0)?.toDoubleOrNull()
                    val lng = parts.getOrNull(1)?.toDoubleOrNull()
                    if (parts.size == 2 && lat != null && lng != null &&
                        lat in -90.0..90.0 && lng in -180.0..180.0
                    ) {
                        val name = "$lat, $lng"
                        val timezone = TwinSpaceLocation.timezoneForLongitude(lng)
                        // Custom coordinates cannot reliably tell the
                        // country, so the region defaults to US.
                        TwinSpaceLocation.setLocation(ctx, lat, lng, name, timezone, TwinSpaceLocation.DEFAULT_COUNTRY)
                        updateFakeLocationSummary(pref)
                        toast(getString(R.string.fake_location_set, name))
                    } else {
                        try {
                            MaterialDialog(ctx).show {
                                title(R.string.fake_location_custom)
                                message(R.string.fake_location_invalid)
                                positiveButton(R.string.done)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error showing location error: ${e.message}")
                            toast(R.string.fake_location_invalid)
                        }
                    }
                }
                negativeButton(R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing custom location dialog: ${e.message}")
        }
    }

    /**
     * TwinSpace, 2026-11-28: debug-only "Export log" (see LogExporter).
     * Dumps this app's own TS_BOOT / TS_ACCT lines to app-private
     * storage and shares the file, so the log can be sent without a
     * PC. Hidden in release builds — BuildConfig.DEBUG is false there,
     * so the release APK never shows this row at all.
     */
    private fun initExportLog() {
        val pref: Preference = (findPreference("export_log")!!)
        if (!BuildConfig.DEBUG) {
            pref.isVisible = false
            return
        }
        pref.setOnPreferenceClickListener {
            exportLog()
            true
        }
    }

    /** Runs the export off the main thread (logcat + file I/O block). */
    private fun exportLog() {
        val ctx = requireContext().applicationContext
        Thread {
            val file = LogExporter.export(ctx)
            try {
                requireActivity().runOnUiThread {
                    if (file == null) {
                        toast(R.string.export_log_failed)
                        return@runOnUiThread
                    }
                    if (!LogExporter.share(ctx, file)) {
                        toast(R.string.export_log_failed)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error showing export result: ${e.message}")
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshVpnStatus()
        refreshFakeLocation()
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
                    // Reject a wrong config here with a clear error,
                    // instead of saving it silently and failing later.
                    val error = TwinSpaceVpn.validateConfig(text)
                    if (error != null) {
                        try {
                            MaterialDialog(ctx).show {
                                title(R.string.vpn_config_invalid)
                                message(text = error)
                                positiveButton(R.string.done)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error showing VPN config error: ${e.message}")
                            toast(R.string.vpn_config_invalid)
                        }
                        return@positiveButton
                    }
                    TwinSpaceVpn.saveConfig(ctx, text)
                    updateVpnConfigSummary(pref)
                    refreshVpnStatus()
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
                        // Asks for the system VPN consent first when needed
                        // (fresh install); see startVpnWithPermission().
                        startVpnWithPermission()
                    } else {
                        TwinSpaceVpn.stop(requireContext()) { ok, _ ->
                            refreshVpnStatus()
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

