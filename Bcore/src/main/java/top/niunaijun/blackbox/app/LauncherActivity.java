package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.R;
import top.niunaijun.blackbox.utils.Slog;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.animation.OvershootInterpolator;


public class LauncherActivity extends Activity {
    public static final String TAG = "SplashScreen";

    public static final String KEY_INTENT = "launch_intent";
    public static final String KEY_PKG = "launch_pkg";
    public static final String KEY_USER_ID = "launch_user_id";
    private boolean isRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable launchTimeoutRunnable;

    public static void launch(Intent intent, int userId) {
        try {
            Intent splash = new Intent();
            splash.setClass(BlackBoxCore.getContext(), LauncherActivity.class);
            
            splash.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            splash.putExtra(LauncherActivity.KEY_INTENT, intent);
            splash.putExtra(LauncherActivity.KEY_PKG, intent.getPackage());
            splash.putExtra(LauncherActivity.KEY_USER_ID, userId);
            BlackBoxCore.getContext().startActivity(splash);
            Slog.d(TAG, "LauncherActivity.launch() called for package: " + intent.getPackage());
        } catch (Exception e) {
            Slog.e(TAG, "Error in LauncherActivity.launch()", e);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            
            Intent intent = getIntent();
            if (intent == null) {
                Slog.w(TAG, "Intent is null, finishing activity");
                finish();
                return;
            }
            
            Intent launchIntent = intent.getParcelableExtra(KEY_INTENT);
            String packageName = intent.getStringExtra(KEY_PKG);
            int userId = intent.getIntExtra(KEY_USER_ID, 0);

            if (launchIntent == null || packageName == null) {
                Slog.w(TAG, "Missing launch intent or package name, finishing activity");
                finish();
                return;
            }

            Slog.d(TAG, "LauncherActivity.onCreate() for package: " + packageName + ", userId: " + userId);

            
            PackageInfo packageInfo = getPackageInfoWithFallback(packageName, userId);
            
            if (packageInfo == null) {
                Slog.w(TAG, "Package info not available for " + packageName + ", but proceeding with launch");
                
            } else {
                Slog.d(TAG, "Successfully retrieved package info for " + packageName);
            }
            
            
            Drawable drawable = null;
            String appName = packageName;
            try {
                if (packageInfo != null && packageInfo.applicationInfo != null) {
                    PackageManager pm = getPackageManager();
                    drawable = pm.getApplicationIcon(packageInfo.applicationInfo);
                    CharSequence label = pm.getApplicationLabel(packageInfo.applicationInfo);
                    if (label != null) appName = label.toString();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to load app icon or name for " + packageName + ": " + e.getMessage());
            }
            setContentView(R.layout.activity_launcher);
            ImageView iconView = findViewById(R.id.iv_icon);
            TextView nameView = findViewById(R.id.tv_app_name);
            if (nameView != null) {
                nameView.setText(appName);
                nameView.setAlpha(0f);
                nameView.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .setStartDelay(200)
                    .start();
            }
            if (iconView != null && drawable != null) {
                iconView.setImageDrawable(drawable);
                iconView.setScaleX(0.7f);
                iconView.setScaleY(0.7f);
                iconView.setAlpha(0f);
                iconView.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .alpha(1f)
                    .setDuration(350)
                    .setInterpolator(new OvershootInterpolator())
                    .withEndAction(() -> iconView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start())
                    .start();
            }
            
            
            launchAppAsync(launchIntent, userId);

            // TwinSpace, 2026-11-27: the splash used to hang forever when
            // the guest activity could not start (e.g. Chrome — its
            // activity is not in the virtual PM, so startActivityLocked
            // returns 0 silently and no guest ever comes to front to
            // pause this splash). If nothing has paused this splash
            // after 6 s, the launch failed: tell the user and close the
            // splash instead of leaving a white screen forever.
            launchTimeoutRunnable = () -> {
                if (!isRunning) {
                    Slog.e(TAG, "Launch timed out for " + packageName + " — guest never started");
                    try {
                        Toast.makeText(LauncherActivity.this, R.string.launch_failed, Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) { }
                    finish();
                }
            };
            mainHandler.postDelayed(launchTimeoutRunnable, 6000);
            
        } catch (Exception e) {
            Slog.e(TAG, "Critical error in LauncherActivity.onCreate()", e);
            finish();
        }
    }

    
    private PackageInfo getPackageInfoWithFallback(String packageName, int userId) {
        try {
            
            return BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 0, userId);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get package info for " + packageName + " (attempt 1): " + e.getMessage());
            
            try {
                
                return BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 
                    android.content.pm.PackageManager.GET_META_DATA, userId);
            } catch (Exception e2) {
                Slog.w(TAG, "Failed to get package info for " + packageName + " (attempt 2): " + e2.getMessage());
                
                try {
                    
                    android.content.pm.ApplicationInfo appInfo = BlackBoxCore.getBPackageManager()
                        .getApplicationInfo(packageName, 0, userId);
                    
                    if (appInfo != null) {
                        
                        PackageInfo fallbackInfo = new PackageInfo();
                        fallbackInfo.packageName = packageName;
                        fallbackInfo.applicationInfo = appInfo;
                        fallbackInfo.versionCode = 1;
                        fallbackInfo.versionName = "1.0";
                        fallbackInfo.firstInstallTime = System.currentTimeMillis();
                        fallbackInfo.lastUpdateTime = System.currentTimeMillis();
                        
                        Slog.d(TAG, "Created fallback PackageInfo for " + packageName);
                        return fallbackInfo;
                    }
                } catch (Exception e3) {
                    Slog.w(TAG, "Failed to get application info for " + packageName + ": " + e3.getMessage());
                }
            }
        }
        
        return null;
    }

    
    private void launchAppAsync(final Intent launchIntent, final int userId) {
        new Thread(() -> {
            try {
                Slog.d(TAG, "Starting app launch in background thread");
                
                
                Thread.sleep(100);
                
                
                BlackBoxCore.getBActivityManager().startActivity(launchIntent, userId);
                
                Slog.d(TAG, "App launch initiated successfully");
            } catch (Exception e) {
                Log.e("TwinSpaceLaunch", "launchAppAsync FAILED: " + e);
                Slog.e(TAG, "Error launching app", e);
                // TwinSpace, 2026-11-27: the old catch only logged and
                // left the white splash on screen forever. Show the
                // failure and close the splash.
                runOnUiThread(() -> {
                    try {
                        Toast.makeText(LauncherActivity.this, R.string.launch_failed, Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) { }
                    finish();
                });
            }
        }, "AppLaunchThread").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = true;
        // A guest activity came to front, so the launch worked — cancel
        // the timeout that would otherwise close this splash as failed.
        if (launchTimeoutRunnable != null) mainHandler.removeCallbacks(launchTimeoutRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isRunning) {
            finish();
        }
    }
}
