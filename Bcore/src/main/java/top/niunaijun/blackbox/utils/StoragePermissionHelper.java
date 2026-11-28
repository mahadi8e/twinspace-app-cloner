package top.niunaijun.blackbox.utils;

import android.os.Build;
import android.os.Environment;


public class StoragePermissionHelper {

    private static final String TAG = "StoragePermissionHelper";


    public static boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {

            return true;
        }
    }
}
