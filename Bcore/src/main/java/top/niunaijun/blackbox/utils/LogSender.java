package top.niunaijun.blackbox.utils;

import java.io.File;


public class LogSender {
    private static final String TAG = "LogSender";

    /**
     * TwinSpace: log sending is disabled. Crash logs and device info must
     * never leave the device, so this never opens a network connection and
     * there is no upload endpoint or destination left in the code. Kept
     * with the same signature so existing callers still compile.
     *
     * @return a non-null error string, because nothing was sent.
     */
    public static String send(String chatId, File logFile, String caption) {
        Slog.w(TAG, "Log sending is disabled, not uploading logs");
        return "Log sending is disabled";
    }
}
