package top.niunaijun.blackbox.core.system.pm.installer;


import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.NativeUtils;
import top.niunaijun.blackbox.utils.Slog;


public class CopyExecutor implements Executor {

    public static final String TAG = "CopyExecutor";

    /**
     * TwinSpace, 2026-11-28: BISECT SWITCHES for the B6 FLAG_SYSTEM
     * split handling, split into two independent flags so each part
     * can be re-enabled one at a time (one build each) after the
     * split-bisect result. Both default FALSE: a system install
     * copies NO split APKs and extracts NO native libs from splits
     * -- exactly the pre-B6 behaviour, when the cloned Play Store
     * reached its Sign in screen.
     * Step 1 (splits only): set ENABLE_SYSTEM_SPLIT_COPY = true.
     * Step 2 (native-lib extraction): set
     * ENABLE_SYSTEM_SPLIT_NATIVE_LIB = true.
     * Non-system (user-app) installs are unaffected by these flags.
     *
     * 2026-11-29: Step 1 ENABLED. The 01:16 log proves the GMS
     * authenticator's addAccount RETURNS immediately (same ms) without
     * ever calling onResult/onError -- it is a no-op. The likely cause
     * is the missing Auth/Dynamite module: GMS's auth code lives in the
     * DynamiteModulesA/C splits, which were never copied into the guest,
     * so the authenticator fails fast loading the module and returns
     * silently. Copying the host's splits at install time should let it
     * load. NOTE: this only takes effect on a FRESH system install of
     * GMS -- toggle Google services OFF then ON in GMS Manager (or
     * reinstall GMS) after installing this build, or the old
     * split-less install stays. Step 2 (native libs) stays OFF.
     */
    public static final boolean ENABLE_SYSTEM_SPLIT_COPY = true;
    public static final boolean ENABLE_SYSTEM_SPLIT_NATIVE_LIB = false;

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;



        List<String> hostSplits = resolveHostSplits(packageName, option);

        try {
            File libDir = BEnvironment.getAppLibDir(packageName);
            if (!option.isFlag(InstallOption.FLAG_SYSTEM)) {
                NativeUtils.copyNativeLib(new File(ps.pkg.baseCodePath), libDir);
            }
            // TwinSpace, 2026-11-28 (BISECT): the B6 change extracted
            // native libs from split APKs for SYSTEM installs too.
            // Gated by ENABLE_SYSTEM_SPLIT_NATIVE_LIB (default false;
            // step 2 of the re-enable). Non-system installs are
            // unaffected.
            boolean isSystem = option.isFlag(InstallOption.FLAG_SYSTEM);
            if (!isSystem || ENABLE_SYSTEM_SPLIT_NATIVE_LIB) {
                for (String split : hostSplits) {
                    try {
                        NativeUtils.copyNativeLib(new File(split), libDir);
                    } catch (Exception e) {
                        Slog.w(TAG, "failed extracting native libs from split " + split + ": " + e.getMessage());
                    }
                }
            } else {
                android.util.Log.e("TS_BOOT", "CopyExecutor " + packageName + ": skipping split native-lib extraction (system install, ENABLE_SYSTEM_SPLIT_NATIVE_LIB=false)");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
        if (option.isFlag(InstallOption.FLAG_STORAGE)) {

            File origFile = new File(ps.pkg.baseCodePath);
            File newFile = BEnvironment.getBaseApkDir(packageName);
            try {
                if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                    boolean b = FileUtils.renameTo(origFile, newFile);
                    if (!b) {
                        FileUtils.copyFile(origFile, newFile);
                    }
                } else {
                    FileUtils.copyFile(origFile, newFile);
                }
                newFile.setReadOnly();

                ps.pkg.baseCodePath = newFile.getAbsolutePath();


                if (copySplits(packageName, hostSplits) != 0) {
                    return -1;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return -1;
            }
        } else if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
            // TwinSpace, 2026-11-28 (BISECT): the B6 change copied the
            // host's split APKs into the virtual splits dir for system
            // installs. Gated by ENABLE_SYSTEM_SPLIT_COPY (default
            // false; step 1 of the re-enable) -- with it off, a system
            // install copies nothing, exactly like the pre-B6
            // behaviour that reached the Play Store Sign in screen.
            // Flip this flag alone to test whether the split copy is
            // what kills the guest at launch.
            if (ENABLE_SYSTEM_SPLIT_COPY) {
                if (copySplits(packageName, hostSplits) != 0) {
                    return -1;
                }
            } else {
                android.util.Log.e("TS_BOOT", "CopyExecutor " + packageName + ": skipping split copy (system install, ENABLE_SYSTEM_SPLIT_COPY=false)");
            }
        }
        return 0;
    }



    private List<String> resolveHostSplits(String packageName, InstallOption option) {
        List<String> result = new ArrayList<>();
        // TwinSpace, 2026-11-27: this used to return an empty list for
        // FLAG_SYSTEM installs, so GMS (installed via installBySystem)
        // never got its split APKs copied and the guest only saw the
        // base APK. Resolve the host's splits for system installs too;
        // CopyExecutor now copies them into the virtual splits dir,
        // where PackageManagerCompat picks them up (splitSourceDirs).
        try {
            ApplicationInfo hostInfo = BlackBoxCore.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            String[] splits = hostInfo.splitSourceDirs;
            if (splits != null) {
                for (String split : splits) {
                    if (split != null && new File(split).isFile()) {
                        result.add(split);
                    }
                }
            }
        } catch (Throwable ignored) {

        }
        if (!result.isEmpty()) {
            Slog.d(TAG, "found " + result.size() + " split apk(s) for " + packageName);
        }
        // TwinSpace, 2026-11-28 (TS_BOOT): log which splits exist per
        // package, so a bad split on the guest dex path can be seen in
        // logcat when the guest dies at launch.
        android.util.Log.e("TS_BOOT", "CopyExecutor.resolveHostSplits " + packageName + ": " + result.size() + " host splits " + result);
        return result;
    }

    private int copySplits(String packageName, List<String> hostSplits) {
        File splitDir = BEnvironment.getSplitApkDir(packageName);

        FileUtils.deleteDir(splitDir);
        if (hostSplits.isEmpty()) {
            return 0;
        }
        FileUtils.mkdirs(splitDir);
        for (String split : hostSplits) {
            File src = new File(split);
            File dst = BEnvironment.getSplitApkFile(packageName, src.getName());
            try {
                FileUtils.copyFile(src, dst);
                dst.setReadOnly();
            } catch (IOException e) {
                Slog.e(TAG, "failed copying split " + src.getName() + ": " + e.getMessage());
                return -1;
            }
        }
        Slog.d(TAG, "copied " + hostSplits.size() + " split apk(s) for " + packageName);
        return 0;
    }
}
