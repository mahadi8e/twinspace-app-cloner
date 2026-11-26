package top.niunaijun.blackboxa.location

import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackbox.fake.frameworks.BLocationManager
import top.niunaijun.blackboxa.data.FakeLocationRepository

/**
 * TwinSpace fake location (TwinSpace addition, 2026-11-26).
 *
 * A SEPARATE location setting, independent of the VPN. The user picks a
 * location here (a preset city or custom coordinates) and every clone in
 * every space gets that location. Turning the VPN on or off no longer
 * changes the location — it is controlled only from this setting.
 *
 * How it works: the chosen location is stored in SharedPreferences and
 * applied to every clone with the engine's fake-location API
 * (FakeLocationRepository: OWN_MODE + BLocation). The engine saves it
 * (BLocationManagerService.save), so it survives app restarts. Turning
 * it off switches every clone back to its real location (CLOSE_MODE).
 *
 * Default: ON with Los Angeles, California, USA — the same location the
 * VPN used to force, so nothing changes for users who never touch this
 * setting.
 */
object TwinSpaceLocation {

    private const val TAG = "TwinSpaceLocation"
    private const val PREFS_NAME = "TwinSpaceLocation"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"
    private const val KEY_NAME = "name"

    /** Default location: Los Angeles, California, USA. */
    const val DEFAULT_LATITUDE = 34.0522
    const val DEFAULT_LONGITUDE = -118.2437
    const val DEFAULT_NAME = "Los Angeles, California, USA"

    /** Preset places the user can pick from (name, latitude, longitude). */
    val PRESETS: List<Triple<String, Double, Double>> = listOf(
        Triple("Los Angeles, California, USA", 34.0522, -118.2437),
        Triple("New York, USA", 40.7128, -74.0060),
        Triple("London, UK", 51.5074, -0.1278),
        Triple("Paris, France", 48.8566, 2.3522),
        Triple("Berlin, Germany", 52.5200, 13.4050),
        Triple("Tokyo, Japan", 35.6762, 139.6503),
        Triple("Singapore", 1.3521, 103.8198),
        Triple("Sydney, Australia", -33.8688, 151.2093),
        Triple("Dubai, UAE", 25.2048, 55.2708)
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun getName(context: Context): String =
        prefs(context).getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME

    fun getLatitude(context: Context): Double =
        prefs(context).getFloat(KEY_LATITUDE, DEFAULT_LATITUDE.toFloat()).toDouble()

    fun getLongitude(context: Context): Double =
        prefs(context).getFloat(KEY_LONGITUDE, DEFAULT_LONGITUDE.toFloat()).toDouble()

    /** Save a new location, turn fake location on, and apply it to all clones. */
    fun setLocation(context: Context, latitude: Double, longitude: Double, name: String) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putFloat(KEY_LATITUDE, latitude.toFloat())
            .putFloat(KEY_LONGITUDE, longitude.toFloat())
            .putString(KEY_NAME, name)
            .apply()
        Log.d(TAG, "Location set to $name ($latitude, $longitude)")
        applyLocation(context.applicationContext)
    }

    /** Turn fake location on (re-apply the stored location) or off (real location). */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            Log.d(TAG, "Fake location on: ${getName(context)}")
            applyLocation(context.applicationContext)
        } else {
            Log.d(TAG, "Fake location off: clones use real location")
            clearLocation()
        }
    }

    /** Give one clone (space [userId], package [pkg]) the stored location. */
    fun applyLocationTo(context: Context, userId: Int, pkg: String) {
        val appContext = context.applicationContext
        Thread {
            try {
                val repo = FakeLocationRepository()
                repo.setPattern(userId, pkg, BLocationManager.OWN_MODE)
                repo.setLocation(userId, pkg, BLocation(getLatitude(appContext), getLongitude(appContext)))
                Log.d(TAG, "Location set for $pkg (user $userId)")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting location for $pkg: ${e.message}")
            }
        }.start()
    }

    /** Give every clone in every space the stored location. */
    fun applyLocation(context: Context) {
        val appContext = context.applicationContext
        Thread {
            try {
                val repo = FakeLocationRepository()
                val core = BlackBoxCore.get()
                val lat = getLatitude(appContext)
                val lng = getLongitude(appContext)
                for (user in core.users) {
                    val userId = user.id
                    for (app in core.getInstalledApplications(0, userId)) {
                        try {
                            repo.setPattern(userId, app.packageName, BLocationManager.OWN_MODE)
                            repo.setLocation(userId, app.packageName, BLocation(lat, lng))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting location for ${app.packageName}: ${e.message}")
                        }
                    }
                }
                Log.d(TAG, "Location applied to all clones: $lat, $lng")
            } catch (e: Exception) {
                Log.e(TAG, "Error applying location: ${e.message}")
            }
        }.start()
    }

    /** Switch every clone back to its real location (fake location off). */
    fun clearLocation() {
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
                Log.d(TAG, "Location cleared: clones use real location")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing location: ${e.message}")
            }
        }.start()
    }
}
