#include <sys/system_properties.h>
#include <cstring>
#include "./xdl.h"
#include <android/log.h>
#include <dlfcn.h>
#include "Dobby/dobby.h"
#include "BoxCore.h"
#include "Utils/VirtualSpoof.h"

#define LOG_TAG "VirtualSpoof"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// TwinSpace, 2026-11-27: the __system_property_get hook is installed
// EXPLICITLY, only in guest (clone) processes, via
// NativeCore.installVirtualSpoof() called from BlackBoxCore.doCreate()
// after NativeCore.init(). It used to be installed from a
// __attribute__((constructor)), which ran in EVERY process that loaded
// libblackbox.so (main, :black server, and guests) as soon as the .so
// was loaded -- before any Java code could decide whether the hook was
// wanted. A failing DobbyHook in the host process killed the app after
// the splash with no Java trace. The constructor is gone; nothing in
// this file runs at load time anymore.

struct SpoofedProp {
    const char* key;
    const char* value;
};

SpoofedProp spoofed_props[] = {
        {"ro.product.model", "Pixel 6"},
        {"ro.product.brand", "google"},
        {"ro.product.manufacturer", "Google"},
        {"ro.product.device", "oriole"},
        {"ro.build.fingerprint", "google/oriole/oriole:12/SP1A.210812.015/7679548:user/release-keys"},
        {"ro.build.version.release", "12"},
        {"ro.build.version.security_patch", "2022-01-05"},
        {"ro.serialno", "1A2B3C4D5E6F"},
        {"ro.hardware", "qcom"},
        {"ro.boot.hardware", "qcom"},
        {"ro.product.board", "lahaina"},
        {"ro.product.cpu.abi", "arm64-v8a"},
        {"ro.build.type", "user"},
        {"ro.build.tags", "release-keys"},
        {"ro.kernel.qemu", "0"},
        {"ro.kernel.android.qemud", ""},
        {"ro.hardware.egl", "adreno"},
        {"ro.boot.qemu", "0"},
        // TwinSpace: make the SIM / network look like a US carrier
        // (T-Mobile, MCC+MNC 310260) so clones report the USA. The
        // telephony APIs (getSimCountryIso / getSimOperator /
        // getSimOperatorName / getNetworkOperator / getNetworkOperatorName)
        // read these system properties directly, NOT the telephony binder,
        // so this property hook is the only way to spoof them. The
        // network country (getNetworkCountryIso) is already spoofed
        // through the ITelephony binder hook.
        {"gsm.sim.operator.iso-country", "us"},
        {"gsm.sim.operator.numeric", "310260"},
        {"gsm.sim.operator.alpha", "T-Mobile"},
        {"gsm.operator.iso-country", "us"},
        {"gsm.operator.numeric", "310260"},
        {"gsm.operator.alpha", "T-Mobile"},
    {nullptr, nullptr}
};

// Google packages must see the REAL device (model / fingerprint /
// release). Faking those breaks Play Services' device checks and can
// break sign-in. Only the gsm.* carrier properties are spoofed for
// them, so clones still report a US carrier.
static const char* kGooglePackages[] = {
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        nullptr
};

static bool sInstalled = false;
static bool sIsGooglePackage = false;
static char sPackageName[256] = {0};

static bool is_device_identity_key(const char* key) {
    // Device-identity properties. The gsm.* carrier properties are NOT
    // in this list: they are spoofed for every guest, including Google
    // packages (the USA-carrier feature).
    return strncmp(key, "ro.product.", 11) == 0
            || strncmp(key, "ro.build.", 9) == 0
            || strncmp(key, "ro.serialno", 11) == 0
            || strncmp(key, "ro.hardware", 11) == 0
            || strncmp(key, "ro.boot.", 8) == 0
            || strncmp(key, "ro.kernel.", 10) == 0;
}

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;

int my_system_property_get(const char *name, char *value) {
    if (sIsGooglePackage && is_device_identity_key(name)) {
        // Google package: real device identity, no spoofing.
        if (orig_system_property_get) {
            return orig_system_property_get(name, value);
        }
        value[0] = '\0';
        return 0;
    }
    for (int i = 0; spoofed_props[i].key != nullptr; ++i) {
        if (strcmp(name, spoofed_props[i].key) == 0) {
            strcpy(value, spoofed_props[i].value);
            LOGD("[spoof] %s = %s", name, value);
            return strlen(value);
        }
    }
    if (orig_system_property_get) {
        return orig_system_property_get(name, value);
    }
    value[0] = '\0';
    return 0;
}

void install_virtual_spoof(const char* package_name) {
    if (sInstalled) {
        LOGD("VirtualSpoof: already installed, skipping");
        return;
    }
    // Unverified on API 34+ (the Dobby hook on __system_property_get
    // is not confirmed to work there). Skip rather than risk a native
    // crash in the guest; the Java-side spoofs still apply.
    int api_level = BoxCore::getApiLevel();
    if (api_level >= 34) {
        LOGE("VirtualSpoof: skipped, API %d >= 34 (unverified)", api_level);
        return;
    }
    if (package_name != nullptr && package_name[0] != '\0') {
        strncpy(sPackageName, package_name, sizeof(sPackageName) - 1);
        sPackageName[sizeof(sPackageName) - 1] = '\0';
        for (int i = 0; kGooglePackages[i] != nullptr; ++i) {
            if (strcmp(package_name, kGooglePackages[i]) == 0) {
                sIsGooglePackage = true;
                break;
            }
        }
    } else {
        LOGE("VirtualSpoof: package name unknown, spoofing all properties");
    }
    void* handle = xdl_open("libc.so", XDL_DEFAULT);
    if (handle == nullptr) {
        LOGE("VirtualSpoof: xdl_open(libc.so) failed, hook NOT installed");
        return;
    }
    void* target = xdl_dsym(handle, "__system_property_get", nullptr);
    if (target == nullptr) {
        LOGE("VirtualSpoof: __system_property_get not found, hook NOT installed");
        xdl_close(handle);
        return;
    }
    int ret = DobbyHook(target, (void*)my_system_property_get, (void**)&orig_system_property_get);
    xdl_close(handle);
    if (ret != 0) {
        LOGE("VirtualSpoof: DobbyHook failed (ret=%d), hook NOT installed", ret);
        return;
    }
    sInstalled = true;
    LOGD("VirtualSpoof: __system_property_get hook installed (pkg=%s google=%d api=%d)",
         sPackageName, sIsGooglePackage ? 1 : 0, api_level);
}
