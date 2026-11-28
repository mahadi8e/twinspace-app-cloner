#ifndef VIRTUALSPOOF_H
#define VIRTUALSPOOF_H

// TwinSpace, 2026-11-27: install the __system_property_get hook.
// Called explicitly (never from a constructor) from the JNI method
// NativeCore.installVirtualSpoof(), only in guest (clone) processes,
// after NativeCore.init(). package_name is the guest app's package
// (may be null when unknown); Google packages (gms / gsf / vending)
// get only the gsm.* carrier spoof and see the real device identity.
// Safe to call repeatedly (installs at most once); no-ops on API >= 34
// (unverified there) and logs the reason instead of crashing.
void install_virtual_spoof(const char* package_name);

#endif
