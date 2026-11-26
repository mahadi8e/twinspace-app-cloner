package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;


public class VpnCommonProxy {
    @ProxyMethod("setVpnPackageAuthorization")
    public static class setVpnPackageAuthorization extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("prepareVpn")
    public static class PrepareVpn extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    // NOTE (TwinSpace, 2026-11-27): there is deliberately NO establishVpn
    // hook here any more. The old hook rewrote every guest VPN config's
    // user to ProxyVpnService, a stub that opened a TUN (route 0.0.0.0/0)
    // and then never forwarded a single packet — so any guest app that
    // established a VPN silently lost all network traffic (blackhole),
    // while the UI showed the VPN as "connected". The stub is also
    // disabled in the manifest. A guest VPN app now fails cleanly instead
    // of silently blackholing traffic. TwinSpace's own VPN (USA IP) is
    // unrelated — it is the host app's WireGuard tunnel (TwinSpaceVpn).

}
