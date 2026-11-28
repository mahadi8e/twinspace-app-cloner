package top.niunaijun.blackbox.utils;


public class SimpleCrashFix {
    private static final String TAG = "SimpleCrashFix";
    private static boolean sIsInstalled = false;


    public static void installSimpleFix() {
        if (sIsInstalled) {
            Slog.d(TAG, "Simple crash fix already installed");
            return;
        }

        try {
            Slog.d(TAG, "Installing essential crash fix...");


            installGlobalExceptionHandler();


            sIsInstalled = true;
            Slog.d(TAG, "Essential crash fix installed successfully");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to install essential crash fix: " + e.getMessage(), e);
        }
    }


    private static void installGlobalExceptionHandler() {
        try {

            Thread.UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();

            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    // TwinSpace, 2026-11-27: NEVER swallow a crash. The old
                    // handler returned early (without forwarding to the
                    // default handler) for five broad substring-matched
                    // categories — any exception mentioning "Context",
                    // "com.google.android.gms", "WebView", "AttributionSource",
                    // or a social-media app name (Facebook, Telegram, ...).
                    // That killed the thread silently and left the process
                    // (host and guests) lingering in an inconsistent state
                    // instead of crashing/restarting, and it hid genuine
                    // bugs. Now every fatal crash is only logged locally
                    // and always forwarded to the previous handler, so the
                    // process crashes/restarts normally and TwinSpace's own
                    // crash reporter (CrashMonitor) can capture it.
                    // Crash logs are never sent anywhere.
                    Slog.e(TAG, "Fatal crash detected: " + throwable.getMessage());

                    if (currentHandler != null) {
                        currentHandler.uncaughtException(thread, throwable);
                    }
                }
            });

            Slog.d(TAG, "Global exception handler installed successfully");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to install global exception handler: " + e.getMessage(), e);
        }
    }
}
