package top.niunaijun.blackboxa.data

import android.util.Log
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.system.user.BUserInfo
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.bean.GmsInstallBean
import top.niunaijun.blackboxa.location.TwinSpaceLocation
import top.niunaijun.blackboxa.util.getString


class GmsRepository {

    private val TAG = "GmsRepository"

    fun getGmsInstalledList(mInstalledLiveData: MutableLiveData<List<GmsBean>>) {
        val userList = arrayListOf<GmsBean>()

        try {
            // TwinSpace: the Space list comes from the engine's
            // USER_MANAGER service, which may not be ready the instant
            // this screen opens (the black process is still starting).
            // Retry a few times before giving up, like getVmInstallList
            // does for the app list.
            var users: List<BUserInfo>? = null
            var retryCount = 0
            while ((users == null || users.isEmpty()) && retryCount < 3) {
                try {
                    users = BlackBoxCore.get().users
                } catch (e: Exception) {
                    Log.e(TAG, "getGmsInstalledList: error getting users, attempt ${retryCount + 1}: ${e.message}")
                }
                if (users.isNullOrEmpty()) {
                    retryCount++
                    if (retryCount < 3) {
                        try {
                            Thread.sleep(300)
                        } catch (ignored: InterruptedException) {
                        }
                    }
                }
            }
            if (users.isNullOrEmpty()) {
                Log.w(TAG, "getGmsInstalledList: no users found after $retryCount attempts")
            }

            users?.forEach {
                try {
                    val userId = it.id
                    val userName =
                        AppManager.mRemarkSharedPreferences.getString("Remark$userId", "User $userId") ?: "User $userId"
                    val isInstalled = try {
                        BlackBoxCore.get().isInstallGms(userId)
                    } catch (e: Exception) {
                        Log.e(TAG, "getGmsInstalledList: isInstallGms failed for user $userId: ${e.message}")
                        false
                    }
                    val bean = GmsBean(userId, userName, isInstalled)
                    userList.add(bean)
                } catch (e: Exception) {
                    Log.e(TAG, "getGmsInstalledList: error processing user ${it?.id}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getGmsInstalledList: unexpected error: ${e.message}")
        } finally {
            // Always post, even on failure, so the loading indicator
            // dismisses and the screen never stays stuck on loading.
            mInstalledLiveData.postValue(userList)
        }
    }

    fun installGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        val installResult = BlackBoxCore.get().installGms(userID)

        if (installResult.success) {
            // The newly installed Google apps would otherwise stay on
            // "Real Location". Give them the global fake location (only
            // when it is turned on).
            TwinSpaceLocation.applyLocationIfEnabled(BlackBoxCore.getContext())
        }

        val result = if (installResult.success) {
            getString(R.string.install_success)
        } else {
            getString(R.string.install_fail, installResult.msg)
        }

        val bean = GmsInstallBean(userID,installResult.success,result)
        mUpdateInstalledLiveData.postValue(bean)
    }

    fun uninstallGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        var isSuccess = false
        if (BlackBoxCore.get().isInstallGms(userID)) {
            isSuccess = BlackBoxCore.get().uninstallGms(userID)
        }

        val result = if (isSuccess) {
            getString(R.string.uninstall_success)
        } else {
            getString(R.string.uninstall_fail)
        }

        val bean = GmsInstallBean(userID,isSuccess,result)

        mUpdateInstalledLiveData.postValue(bean)
    }
}