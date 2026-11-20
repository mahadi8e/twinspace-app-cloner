package top.niunaijun.blackbox.app.configuration;

import java.io.File;


public abstract class ClientConfiguration {

    public boolean isHideRoot() {
        return false;
    }



    public abstract String getHostPackageName();

    public boolean isEnableDaemonService() {
        return true;
    }

    public boolean isEnableLauncherActivity() {
        return true;
    }

    
    public boolean isUseVpnNetwork() {
        return false;
    }

    public boolean isDisableFlagSecure() {
        return false;
    }

    
    public boolean requestInstallPackage(File file, int userId) {
        return false;
    }

    
    public String getLogSenderChatId() {
        // TwinSpace: no upstream log destination. Returning null disables
        // BlackBoxCore.sendLogs(), which returns early without a chat id.
        return null;
    }
}
