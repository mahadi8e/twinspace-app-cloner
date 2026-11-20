package top.niunaijun.blackbox.core.system;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.CrashMonitor;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class DaemonService extends Service {
    public static final String TAG = "DaemonService";
    private static final int NOTIFY_ID = BlackBoxCore.getHostPkg().hashCode();
    private static final String CHANNEL_ID = "blackbox_daemon_channel";
    private static final String CHANNEL_NAME = "BlackBox Daemon Service";
    private static final String CHANNEL_DESCRIPTION = "Keeps BlackBox core services running";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "DaemonService onCreate");
        
        
        if (BuildCompat.isOreo()) {
            createNotificationChannel();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "DaemonService onStartCommand");

        // The engine is needed again (app opened or a clone launched), so
        // restart the periodic crash-monitor timers if they were stopped
        // when the app was swiped away. This is idempotent.
        try {
            CrashMonitor.restartMonitoring();
        } catch (Exception e) {
            Log.w(TAG, "Failed to restart crash monitoring: " + e.getMessage());
        }

        try {

            Intent innerIntent = new Intent(this, DaemonInnerService.class);
            startService(innerIntent);


            if (BuildCompat.isOreo()) {
                if (!startForegroundService()) {
                    Log.w(TAG, "Failed to start foreground service, falling back to regular service");
                    // TwinSpace: do not ask Android to restart the daemon on
                    // its own. The engine restarts it on demand when the
                    // user opens TwinSpace or launches a clone.
                    return START_NOT_STICKY;
                }
            }

            Log.d(TAG, "DaemonService started successfully");
            return START_NOT_STICKY;

        } catch (Exception e) {
            Log.e(TAG, "Error starting DaemonService: " + e.getMessage(), e);

            return START_NOT_STICKY;
        }
    }

    /**
     * TwinSpace: called when TwinSpace is swiped away from recents. If no
     * cloned app is running, the daemon (and the periodic crash-monitor
     * timers) stop here, so the engine no longer burns power in the
     * background. If a cloned app IS running, the daemon stays, so the
     * clone keeps working.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "DaemonService onTaskRemoved");
        try {
            if (!isAnyVirtualAppRunning()) {
                Log.d(TAG, "No cloned app running, stopping daemon after task removed");
                try {
                    CrashMonitor.stopMonitoring();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to stop crash monitoring: " + e.getMessage());
                }
                stopSelf();
            } else {
                Log.d(TAG, "A cloned app is still running, keeping daemon");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onTaskRemoved: " + e.getMessage(), e);
        }
        super.onTaskRemoved(rootIntent);
    }

    /**
     * Virtual app processes are named hostPkg + ":p..." (see
     * BProcessManagerService.startProcessLocked). The black service process
     * itself and the host main process do not match that prefix.
     * On any error, assume a clone may be running so we never kill a
     * running cloned app by mistake.
     */
    private boolean isAnyVirtualAppRunning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return true;
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes == null) return true;
            String prefix = BlackBoxCore.getHostPkg() + ":p";
            for (ActivityManager.RunningAppProcessInfo info : processes) {
                if (info.processName != null && info.processName.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Failed to check running cloned apps: " + e.getMessage());
            return true;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "DaemonService onDestroy");
        super.onDestroy();
    }

    
    private void createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription(CHANNEL_DESCRIPTION);
                channel.setShowBadge(false);
                channel.setSound(null, null);
                channel.enableVibration(false);
                
                NotificationManager notificationManager = getSystemService(NotificationManager.class);
                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(channel);
                    Log.d(TAG, "Notification channel created successfully");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create notification channel: " + e.getMessage(), e);
        }
    }

    
    private boolean startForegroundService() {
        try {
            Notification notification = createNotification();
            if (notification != null) {
                startForeground(NOTIFY_ID, notification);
                Log.d(TAG, "Foreground service started successfully");
                return true;
            } else {
                Log.e(TAG, "Failed to create notification");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service: " + e.getMessage(), e);
            return false;
        }
    }

    
    private Notification createNotification() {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BlackBox Core")
                .setContentText("Core services are running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false);
            
            return builder.build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create notification: " + e.getMessage(), e);
            return null;
        }
    }

    
    public static class DaemonInnerService extends Service {
        @Override
        public void onCreate() {
            Log.i(TAG, "DaemonInnerService -> onCreate");
            super.onCreate();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Log.i(TAG, "DaemonInnerService -> onStartCommand");
            
            try {
                
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(NOTIFY_ID);
                    Log.d(TAG, "Notification cancelled successfully");
                }
                
                
                stopSelf();
                return START_NOT_STICKY;
                
            } catch (Exception e) {
                Log.e(TAG, "Error in DaemonInnerService: " + e.getMessage(), e);
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onDestroy() {
            Log.i(TAG, "DaemonInnerService -> onDestroy");
            super.onDestroy();
        }
    }
}
