package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.databinding.ActivityMainBinding
import top.niunaijun.blackboxa.util.CrashReporter
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.fake.FakeManagerActivity
import top.niunaijun.blackboxa.view.list.ListActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity
import top.niunaijun.blackboxa.vpn.TwinSpaceVpn

/**
 * TwinSpace home: a vertically scrolling list of Space cards (see
 * [SpacesAdapter]). This replaces the upstream ViewPager-of-pages home; the
 * engine calls and the per-space data flow are unchanged.
 */
class MainActivity : LoadingActivity(), SpacesAdapter.Callbacks {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var spacesAdapter: SpacesAdapter

    /**
     * TwinSpace: onStart() used to reload every Space on every return to
     * the home screen, even when nothing changed. Remember the last
     * loaded user list and skip the reload when it is unchanged. A new
     * app still shows up: installs go through installIntoSpace(), and a
     * new or deleted Space changes the user list.
     */
    private var lastRefreshedUserIds: List<Int> = emptyList()
    private var spacesLoaded = false

    /**
     * A1: keep the engine callback in a field so it can be removed.
     * Previously initSpaces() registered a new anonymous callback on every
     * onCreate() and never removed it, so dead MainActivity copies piled up
     * in BlackBoxCore and each one refreshed every Space when services
     * became available. The same instance is now removed in onDestroy().
     */
    private val serviceAvailableCallback = Runnable {
        Log.d(TAG, "Services became available, refreshing spaces")
        runOnUiThread { refreshSpaces() }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1001
        private const val VPN_PERMISSION_REQUEST_CODE = 1002

        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("TS_BOOT", "MainActivity.onCreate start")

        // TwinSpace diagnostic: if the previous launch crashed, show the
        // saved report instead of starting normally (the normal start is
        // what crashed). If it died with no Java trace, it was a native
        // crash, and that is shown instead.
        try {
            if (CrashReporter.hasReport(this)) {
                showCrashReportDialog()
                return
            }
            if (CrashReporter.previousLaunchDied) {
                showNativeCrashDialog()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking crash report: ${e.message}")
        }
        Log.e("TS_BOOT", "MainActivity.onCreate reaching initNormal")
        initNormal()
    }

    private fun initNormal() {
        Log.e("TS_BOOT", "MainActivity.initNormal start")
        try {
            try {
                BlackBoxCore.get().onBeforeMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onBeforeMainActivityOnCreate: ${e.message}")
            }

            setContentView(viewBinding.root)
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
            initSpaces()
            initFab()

            checkStoragePermission()

            checkVpnPermission()

            // If the user left the USA VPN on, reconnect it now that the app
            // is open, so clones get the USA IP right away. No-ops when the
            // VPN is off, when no WireGuard config is saved yet, or when VPN
            // permission has not been granted (see vpnPermissionResult).
            TwinSpaceVpn.maybeAutoStart(this)

            try {
                BlackBoxCore.get().onAfterMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onAfterMainActivityOnCreate: ${e.message}")
            }

            // Startup completed: this launch was clean.
            try {
                CrashReporter.markClean(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error marking clean launch: ${e.message}")
            }
            Log.e("TS_BOOT", "MainActivity.initNormal done")
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate: ${e.message}")

            showErrorDialog("Failed to initialize app: ${e.message}")
        }
    }

    private fun showCrashReportDialog() {
        val report = CrashReporter.readReport(this)
        val text = "The app crashed on the last launch. Please share this report so the crash can be fixed.\n\n" + report
        val scroll = ScrollView(this)
        val textView = TextView(this)
        textView.text = text
        textView.setTextIsSelectable(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        textView.setPadding(pad, pad, pad, pad)
        scroll.addView(textView)
        AlertDialog.Builder(this)
            .setTitle("TwinSpace crash report")
            .setView(scroll)
            .setPositiveButton("Share") { _, _ ->
                CrashReporter.shareReport(this, report)
            }
            .setNegativeButton("Clear & retry") { _, _ ->
                CrashReporter.clearReport(this)
                CrashReporter.clearMarker(this)
                initNormal()
            }
            .setCancelable(false)
            .show()
    }

    private fun showNativeCrashDialog() {
        val info = CrashReporter.deviceInfo(this)
        val text = "The app died during startup last time, but no Java crash was recorded, so this looks like a native crash in the engine.\n\n" + info
        val scroll = ScrollView(this)
        val textView = TextView(this)
        textView.text = text
        textView.setTextIsSelectable(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        textView.setPadding(pad, pad, pad, pad)
        scroll.addView(textView)
        AlertDialog.Builder(this)
            .setTitle("TwinSpace died on startup")
            .setView(scroll)
            .setPositiveButton("Share") { _, _ ->
                CrashReporter.shareReport(this, text)
            }
            .setNegativeButton("Clear & retry") { _, _ ->
                CrashReporter.clearMarker(this)
                initNormal()
            }
            .setCancelable(false)
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (this::spacesAdapter.isInitialized) {
            refreshSpaces()
        }
    }

    override fun onDestroy() {
        try {
            BlackBoxCore.get().removeServiceAvailableCallback(serviceAvailableCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing service available callback: ${e.message}")
        }
        super.onDestroy()
    }

    private fun initSpaces() {
        try {
            spacesAdapter = SpacesAdapter(this, this)
            viewBinding.spacesRecycler.layoutManager = LinearLayoutManager(this)
            viewBinding.spacesRecycler.adapter = spacesAdapter
            try {
                BlackBoxCore.get().addServiceAvailableCallback(serviceAvailableCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error registering service available callback: ${e.message}")
            }
            refreshSpaces()
        } catch (e: Exception) {
            Log.e(TAG, "Error in initSpaces: ${e.message}")
        }
    }

    private fun refreshSpaces() {
        try {
            val userIds = BlackBoxCore.get().users.map { it.id }
            spacesAdapter.submitSpaces(userIds)
            if (userIds == lastRefreshedUserIds && spacesLoaded) {
                // Nothing changed since the last load; skip reloading every
                // Space (and every app icon) again.
                return
            }
            lastRefreshedUserIds = userIds
            spacesLoaded = true
            userIds.forEach { spacesAdapter.loadSpace(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in refreshSpaces: ${e.message}")
        }
    }

    /** Kept for compatibility with the old fragment flow; now a full refresh. */
    fun scanUser() {
        refreshSpaces()
    }

    private fun spaceLabel(userId: Int, index: Int): String {
        val remark = AppManager.mRemarkSharedPreferences.getString("Remark$userId", null)
        return if (remark.isNullOrEmpty() || remark == "User $userId") {
            getString(R.string.space_default_name, index + 1)
        } else {
            remark
        }
    }

    private fun initFab() {
        try {
            viewBinding.fab.setOnClickListener {
                try {
                    val userIds = BlackBoxCore.get().users.map { it.id }
                    if (userIds.isEmpty()) {
                        onAddApp(0)
                        return@setOnClickListener
                    }
                    val labels =
                            userIds.mapIndexed { index, userId -> spaceLabel(userId, index) } +
                                    getString(R.string.new_space).trim()
                    AlertDialog.Builder(this)
                            .setTitle(R.string.choose_space)
                            .setItems(labels.toTypedArray()) { _, which ->
                                if (which < userIds.size) {
                                    onAddApp(userIds[which])
                                } else {
                                    onAddApp(spacesAdapter.nextNewUserId())
                                }
                            }
                            .show()
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling FAB click: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initFab: ${e.message}")
        }
    }

    // SpacesAdapter.Callbacks

    override fun onAddApp(userId: Int) {
        try {
            val intent = Intent(this, ListActivity::class.java)
            intent.putExtra("userID", userId)
            apkPathResult.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching ListActivity: ${e.message}")
        }
    }

    override fun onSpacesChanged() {
        refreshSpaces()
    }

    private val apkPathResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                try {
                    if (it.resultCode == RESULT_OK) {
                        it.data?.let { data ->
                            val userId = data.getIntExtra("userID", 0)
                            val source = data.getStringExtra("source")
                            if (source != null) {
                                spacesAdapter.installIntoSpace(userId, source)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling APK path result: ${e.message}")
                }
            }

    private fun checkStoragePermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {

                if (!android.os.Environment.isExternalStorageManager()) {
                    Log.w(TAG, "MANAGE_EXTERNAL_STORAGE permission not granted")
                    showStoragePermissionDialog()
                }
            } else {

                if (androidx.core.content.ContextCompat.checkSelfPermission(
                                this,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                        this,
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(
                            TAG,
                            "Storage permissions not granted on Android ${android.os.Build.VERSION.SDK_INT}"
                    )
                    requestLegacyStoragePermission()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking storage permission: ${e.message}")
        }
    }

    private fun requestLegacyStoragePermission() {
        try {
            androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    STORAGE_PERMISSION_REQUEST_CODE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting storage permission: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() &&
                            grantResults.all {
                                it == android.content.pm.PackageManager.PERMISSION_GRANTED
                            }
            ) {
                Log.d(TAG, "Storage permissions granted")
            } else {
                Log.w(TAG, "Storage permissions denied")
            }
        }
    }

    private fun showStoragePermissionDialog() {
        try {
            MaterialDialog(this).show {
                title(text = "Storage Permission Required")
                message(
                        text =
                                "This app needs 'All Files Access' permission to properly run sandboxed apps. Without this permission, some apps may not work correctly.\n\nPlease grant permission in the next screen."
                )
                positiveButton(text = "Grant Permission") { openAllFilesAccessSettings() }
                negativeButton(text = "Later") { Log.w(TAG, "User postponed storage permission") }
                cancelable(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing storage permission dialog: ${e.message}")
        }
    }

    private fun openAllFilesAccessSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val intent =
                        Intent(
                                android.provider.Settings
                                        .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        )
                intent.data = Uri.parse("package:$packageName")
                storagePermissionResult.launch(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening storage settings: ${e.message}")

            try {
                val intent =
                        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                storagePermissionResult.launch(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Error opening fallback storage settings: ${e2.message}")
            }
        }
    }

    private val storagePermissionResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        if (android.os.Environment.isExternalStorageManager()) {
                            Log.d(TAG, "Storage permission granted!")
                        } else {
                            Log.w(TAG, "Storage permission still not granted")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling storage permission result: ${e.message}")
                }
            }


    private fun checkVpnPermission() {
        try {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {

                Log.d(TAG, "VPN permission not granted, requesting...")
                vpnPermissionResult.launch(vpnIntent)
            } else {

                Log.d(TAG, "VPN permission already granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VPN permission: ${e.message}")
        }
    }

    private val vpnPermissionResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                try {
                    if (result.resultCode == RESULT_OK) {
                        Log.d(TAG, "VPN permission granted!")

                        // First grant: start the USA VPN now if the user left it on.
                        TwinSpaceVpn.maybeAutoStart(this@MainActivity)
                    } else {
                        Log.w(TAG, "VPN permission denied by user")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling VPN permission result: ${e.message}")
                }
            }

    private fun showErrorDialog(message: String) {
        try {
            MaterialDialog(this).show {
                title(text = "Error")
                message(text = message)
                positiveButton(text = "OK") { finish() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error dialog: ${e.message}")
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        try {
            menuInflater.inflate(R.menu.menu_main, menu)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating options menu: ${e.message}")
            return false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        try {
            when (item.itemId) {
                R.id.main_setting -> {
                    SettingActivity.start(this)
                }
                R.id.fake_location -> {

                    val intent = Intent(this, FakeManagerActivity::class.java)
                    intent.putExtra("userID", 0)
                    startActivity(intent)
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling menu item selection: ${e.message}")
            return false
        }
    }
}
