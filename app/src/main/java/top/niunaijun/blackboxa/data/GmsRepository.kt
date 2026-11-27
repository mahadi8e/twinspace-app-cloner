package top.niunaijun.blackboxa.data

import android.util.Log
import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.system.accounts.BAccountManagerService
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

    /**
     * TwinSpace, 2026-11-27: diagnostic for the GMS Manager screen --
     * which account-authenticator types the virtual PackageManager found
     * in the in-VA Google install. "com.google" must be in the list for
     * Google sign-in to work; an empty list (or one without com.google)
     * is exactly why tapping "Sign in" in the cloned Play Store does
     * nothing. Runs on the caller's (IO) thread; always posts.
     */
    fun getAuthenticatorTypes(mAuthenticatorTypesLiveData: MutableLiveData<List<String>>) {
        val types = try {
            BAccountManagerService.getCachedAuthenticatorTypes()
        } catch (e: Exception) {
            Log.e(TAG, "getAuthenticatorTypes failed: ${e.message}")
            emptyList<String>()
        }
        mAuthenticatorTypesLiveData.postValue(types)
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
            // The Space grid on MainActivity skips reloading when the
            // user list is unchanged (see refreshSpaces) -- but a GMS
            // install adds apps INSIDE an existing space, so the grid
            // would stay stale until the app is killed and reopened.
            // Mark the spaces dirty so the next refreshSpaces() reloads
            // the app lists live.
            spacesDirty = true
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

        if (isSuccess) {
            // See installGms: a GMS uninstall removes apps from an
            // existing space, so the Space grid must reload live too.
            spacesDirty = true
        }

        val result = if (isSuccess) {
            getString(R.string.uninstall_success)
        } else {
            getString(R.string.uninstall_fail)
        }

        val bean = GmsInstallBean(userID,isSuccess,result)

        mUpdateInstalledLiveData.postValue(bean)
    }

    companion object {
        /**
         * Set when a GMS install/uninstall succeeds (GMS Manager path).
         * MainActivity.refreshSpaces() checks it: a GMS change adds or
         * removes apps inside an existing space without changing the
         * user list, so the "nothing changed" early-return must not
         * skip the reload. Cleared by refreshSpaces() once acted on.
         */
        @Volatile
        var spacesDirty = false
    }
}