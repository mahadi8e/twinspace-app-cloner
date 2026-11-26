package top.niunaijun.blackboxa.view.main

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore


class ShortcutActivity:AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra("pkg")
        val userID = intent.getIntExtra("userId",0)

        // TwinSpace, 2026-11-27: the shortcut target can be missing
        // (null pkg). Passing it straight to launchApk crashed. Bail
        // out cleanly instead, and never let a failed launch leave the
        // activity hanging.
        if (pkg.isNullOrBlank()) {
            Log.w("ShortcutActivity", "No package in shortcut intent, finishing")
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                BlackBoxCore.get().launchApk(pkg,userID)
            } catch (e: Exception) {
                Log.e("ShortcutActivity", "Failed to launch $pkg: ${e.message}")
            }
            finish()
        }
    }
}