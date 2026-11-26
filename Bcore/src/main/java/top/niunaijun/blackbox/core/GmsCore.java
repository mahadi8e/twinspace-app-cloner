package top.niunaijun.blackbox.core;

import android.content.pm.PackageManager;

import java.util.HashSet;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;


public class GmsCore {
    private static final String TAG = "GmsCore";

    private static final HashSet<String> GOOGLE_APP = new HashSet<>();
    private static final HashSet<String> GOOGLE_SERVICE = new HashSet<>();
    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add("com.google.android.play.games");
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");

        
        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add("com.google.android.gsf.login");
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    /**
     * The ONLY Google packages installed into a space.
     * Google services run on just these four; everything else in
     * GOOGLE_APP / GOOGLE_SERVICE is a helper, a sync adapter, a
     * backup/partner/setup app or a standalone app, and is NOT added:
     * - com.android.vending: the Play Store itself.
     * - com.google.android.gms: Google Play Services, the core.
     * - com.google.android.gsf: Services Framework; the Play Store
     *   uses it for sign-in and core functions.
     * - com.google.android.gsf.login: Google account login service;
     *   the Play Store signs in through it.
     * Deliberately NOT installed: Play Games, Wear apps, backup,
     * backuptransport, configupdater, contacts/calendar sync adapters,
     * feedback, onetimeinitializer, partnersetup, setupwizard.
     */
    private static final HashSet<String> ESSENTIAL_PACKAGES = new HashSet<>();

    static {
        ESSENTIAL_PACKAGES.add(VENDING_PKG);
        ESSENTIAL_PACKAGES.add(GMS_PKG);
        ESSENTIAL_PACKAGES.add(GSF_PKG);
        ESSENTIAL_PACKAGES.add("com.google.android.gsf.login");
    }

    public static boolean isGoogleService(String packageName) {
        return GOOGLE_SERVICE.contains(packageName);
    }

    public static boolean isGoogleAppOrService(String str) {
        return GOOGLE_APP.contains(str) || GOOGLE_SERVICE.contains(str);
    }

    private static InstallResult installPackages(Set<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            // Only the essential Google packages are installed (see
            // ESSENTIAL_PACKAGES). Everything else is skipped so the
            // Space stays clean and only what Google services need
            // to run is added.
            if (!ESSENTIAL_PACKAGES.contains(packageName)) {
                continue;
            }
            if (blackBoxCore.isInstalled(packageName, userId)) {
                continue;
            }
            try {
                BlackBoxCore.getContext().getPackageManager().getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                // TwinSpace, 2026-11-27: an essential Google package is
                // missing from the host phone. Do NOT silently skip it —
                // InstallResult.success defaults to true, so the old bare
                // continue reported "install success" while the package
                // (e.g. gsf.login) was never installed. Fail honestly.
                return new InstallResult().installError(packageName, "Host is missing " + packageName);
            }
            InstallResult installResult = blackBoxCore.installPackageAsUser(packageName, userId);
            if (!installResult.success) {
                return installResult;
            }
        }
        return new InstallResult();
    }

    private static void uninstallPackages(Set<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            blackBoxCore.uninstallPackageAsUser(packageName, userId);
        }
    }

    public static InstallResult installGApps(int userId) {
        Set<String> googleApps = new HashSet<>();

        googleApps.addAll(GOOGLE_SERVICE);
        googleApps.addAll(GOOGLE_APP);

        InstallResult installResult = installPackages(googleApps, userId);
        if (!installResult.success) {
            uninstallGApps(userId);
            return installResult;
        }
        return installResult;
    }

    public static void uninstallGApps(int userId) {
        uninstallPackages(GOOGLE_SERVICE, userId);
        uninstallPackages(GOOGLE_APP, userId);
    }


    public static boolean isSupportGms() {
        try {
            BlackBoxCore.getPackageManager().getPackageInfo(GMS_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    public static boolean isInstalledGoogleService(int userId) {
        return BlackBoxCore.get().isInstalled(GMS_PKG, userId);
    }
}