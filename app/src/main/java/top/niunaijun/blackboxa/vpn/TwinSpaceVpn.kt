package top.niunaijun.blackboxa.vpn

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackbox.fake.frameworks.BLocationManager
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.data.FakeLocationRepository

/**
 * TwinSpace USA VPN (WireGuard) + California location (TwinSpace addition, 2026-11-25).
 *
 * The old ProxyVpnService only opened a TUN and never forwarded a packet, so
 * it could not change the IP at all — and if it ran, it would blackhole the
 * clones' traffic. The real tunnel is built here with the official WireGuard
 * tunnel library (GoBackend, wireguard-go): it reads the user's own
 * WireGuard .conf (a free Proton VPN USA server), brings the tunnel up, and
 * forwards packets to that server.
 *
 * Scoping (permanent, by design): the .conf is forced to carry
 * `IncludedApplications = <this app's package>`, which GoBackend turns into
 * VpnService.Builder.addAllowedApplication(). Only TwinSpace — and the clones
 * running inside it — ever enter the tunnel. No other app on the phone is
 * routed through the VPN, and turning the VPN off leaves every other app
 * untouched.
 *
 * Location: while the VPN is on, every clone gets a fixed fake location in
 * California, USA (Los Angeles). It is set once and stored by the engine
 * (BLocationManagerService.save), so it survives app restarts; turning the
 * VPN off switches the clones back to their real location (CLOSE_MODE).
 *
 * The WireGuard .conf itself is the user's own: a free Proton VPN account
 * (Downloads -> WireGuard configuration -> USA server) produces it, and the
 * user pastes it in Settings -> VPN config. Nothing is hard-coded here, so
 * no account key ever ships inside the APK.
 */
object TwinSpaceVpn {

    private const val TAG = "TwinSpaceVpn"
    private const val TUNNEL_NAME = "twinspace"
    private const val PREFS_NAME = "TwinSpaceVpn"
    private const val KEY_WG_CONFIG = "wireguard_config"

    /** Los Angeles, California, USA — the fixed fake location for all clones while VPN is on. */
    const val CALIFORNIA_LATITUDE = 34.0522
    const val CALIFORNIA_LONGITUDE = -118.2437

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isRunning = false
        private set

    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var tunnel: Tunnel? = null

    // ------------------------------------------------------------------
    // WireGuard .conf storage (the user's own Proton USA config)
    // ------------------------------------------------------------------

    fun getConfig(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WG_CONFIG, null)?.takeIf { it.isNotBlank() }
    }

    fun hasConfig(context: Context): Boolean = getConfig(context) != null

    fun saveConfig(context: Context, config: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_WG_CONFIG, config.trim()).apply()
    }

    /**
     * Returns the .conf text with this app forced into the tunnel. Any
     * IncludedApplications line the user may already have is replaced, so a
     * stale value can never widen the tunnel to other apps.
     */
    fun withIncludedApp(configText: String, packageName: String): String {
        val lines = configText.lines()
            .filterNot { it.trim().startsWith("IncludedApplications", ignoreCase = true) }
            .toMutableList()
        val ifaceIndex = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (ifaceIndex == -1) return configText
        lines.add(ifaceIndex + 1, "IncludedApplications = $packageName")
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------
    // Tunnel control (all blocking work runs on a background thread;
    // results are always delivered on the main thread)
    // ------------------------------------------------------------------

    private class SimpleTunnel(private val name: String) : Tunnel {
        override fun getName(): String = name

        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "Tunnel state: $newState")
            isRunning = newState == Tunnel.State.UP
        }
    }

    /**
     * Start the tunnel. The [onResult] callback receives (success, errorCode)
     * where errorCode is one of "no_config", "not_authorized" or the
     * exception message from the WireGuard backend.
     */
    fun start(context: Context, onResult: (Boolean, String?) -> Unit) {
        Thread {
            val result = startBlocking(context.applicationContext)
            mainHandler.post { onResult(result.first, result.second) }
        }.start()
    }

    private fun startBlocking(context: Context): Pair<Boolean, String?> {
        if (isRunning) return true to null
        val raw = getConfig(context) ?: return false to "no_config"
        if (VpnService.prepare(context) != null) return false to "not_authorized"
        return try {
            val confText = withIncludedApp(raw, context.packageName)
            val config = Config.parse(ByteArrayInputStream(confText.toByteArray(Charsets.UTF_8)))
            val be = backend ?: GoBackend(context).also { backend = it }
            val t = tunnel ?: SimpleTunnel(TUNNEL_NAME).also { tunnel = it }
            be.setState(t, Tunnel.State.UP, config)
            isRunning = true
            Log.d(TAG, "USA VPN tunnel is up")
            applyCaliforniaLocation()
            true to null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}")
            false to (e.message ?: "start_failed")
        }
    }

    fun stop(context: Context, onResult: (Boolean, String?) -> Unit) {
        Thread {
            val result = stopBlocking(context.applicationContext)
            mainHandler.post { onResult(result.first, result.second) }
        }.start()
    }

    private fun stopBlocking(context: Context): Pair<Boolean, String?> {
        val be = backend
        val t = tunnel
        if (be == null || t == null || !isRunning) {
            isRunning = false
            clearCaliforniaLocation()
            return true to null
        }
        return try {
            be.setState(t, Tunnel.State.DOWN, null)
            isRunning = false
            Log.d(TAG, "USA VPN tunnel is down")
            clearCaliforniaLocation()
            true to null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN: ${e.message}")
            false to (e.message ?: "stop_failed")
        }
    }

    /**
     * Auto-connect when the app opens, if the user left the VPN on. Only
     * starts when a config is saved and Android has already granted VPN
     * permission (MainActivity asks for it on launch; this is also called
     * again from the permission-result callback for the first grant).
     */
    fun maybeAutoStart(activity: Activity) {
        try {
            if (!AppManager.mBlackBoxLoader.useVpnNetwork()) return
            if (isRunning) return
            if (!hasConfig(activity)) return
            if (VpnService.prepare(activity) != null) return
            start(activity) { ok, err ->
                Log.d(TAG, "Auto-start VPN: ok=$ok err=$err")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error auto-starting VPN: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // California location for clones
    // ------------------------------------------------------------------

    /** Give one clone (space [userId], package [pkg]) the California location. */
    fun applyCaliforniaLocationTo(userId: Int, pkg: String) {
        Thread {
            try {
                val repo = FakeLocationRepository()
                repo.setPattern(userId, pkg, BLocationManager.OWN_MODE)
                repo.setLocation(userId, pkg, BLocation(CALIFORNIA_LATITUDE, CALIFORNIA_LONGITUDE))
                Log.d(TAG, "California location set for $pkg (user $userId)")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting California location for $pkg: ${e.message}")
            }
        }.start()
    }

    /** Give every clone in every space the California location (VPN turned on). */
    fun applyCaliforniaLocation() {
        Thread {
            try {
                val repo = FakeLocationRepository()
                val core = BlackBoxCore.get()
                for (user in core.users) {
                    val userId = user.id
                    for (app in core.getInstalledApplications(0, userId)) {
                        try {
                            repo.setPattern(userId, app.packageName, BLocationManager.OWN_MODE)
                            repo.setLocation(
                                userId,
                                app.packageName,
                                BLocation(CALIFORNIA_LATITUDE, CALIFORNIA_LONGITUDE)
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting location for ${app.packageName}: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying California location: ${e.message}")
            }
        }.start()
    }

    /** Switch every clone back to its real location (VPN turned off). */
    fun clearCaliforniaLocation() {
        Thread {
            try {
                val repo = FakeLocationRepository()
                val core = BlackBoxCore.get()
                for (user in core.users) {
                    val userId = user.id
                    for (app in core.getInstalledApplications(0, userId)) {
                        try {
                            repo.setPattern(userId, app.packageName, BLocationManager.CLOSE_MODE)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error clearing location for ${app.packageName}: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing California location: ${e.message}")
            }
        }.start()
    }
}
