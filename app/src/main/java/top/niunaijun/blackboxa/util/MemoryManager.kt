package top.niunaijun.blackboxa.util

import android.util.Log
import java.lang.Runtime



object MemoryManager {

    private const val TAG = "MemoryManager"
    private const val MEMORY_THRESHOLD = 0.8 
    private const val CRITICAL_MEMORY_THRESHOLD = 0.9 

    
    fun isMemorySafe(): Boolean {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsage = usedMemory.toDouble() / maxMemory.toDouble()

            memoryUsage < MEMORY_THRESHOLD
        } catch (e: Exception) {
            Log.e(TAG, "Error checking memory: ${e.message}")
            true 
        }
    }

    
    fun isMemoryCritical(): Boolean {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsage = usedMemory.toDouble() / maxMemory.toDouble()

            memoryUsage > CRITICAL_MEMORY_THRESHOLD
        } catch (e: Exception) {
            Log.e(TAG, "Error checking critical memory: ${e.message}")
            false 
        }
    }

    
    fun getMemoryUsagePercentage(): Int {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsage = usedMemory.toDouble() / maxMemory.toDouble()

            (memoryUsage * 100).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting memory usage: ${e.message}")
            0
        }
    }

    
    // TwinSpace: explicit GC is no longer forced. System.gc() (plus the
    // 100ms sleep that followed it) costs CPU and power and usually runs
    // a full collection; the runtime collects when it needs to. Callers
    // in AppsRepository and AppsFragment already check isMemoryCritical()
    // and skip heavy work (icon loading) instead. Kept with the same
    // signature so those callers still compile.
    fun forceGarbageCollectionIfNeeded(): Boolean {
        return try {
            if (isMemoryCritical()) {
                Log.w(
                        TAG,
                        "Memory usage critical (${getMemoryUsagePercentage()}%), skipping explicit GC"
                )
                false
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking memory: ${e.message}")
            false
        }
    }


    fun optimizeMemoryForRecyclerView() {
        try {
            val memoryUsage = getMemoryUsagePercentage()

            if (memoryUsage > 70) {
                Log.d(TAG, "Memory usage high (${memoryUsage}%), skipping explicit GC")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing memory: ${e.message}")
        }
    }

    
    fun shouldSkipIconLoading(): Boolean {
        return try {
            val memoryUsage = getMemoryUsagePercentage()
            memoryUsage > 75 
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if should skip icon loading: ${e.message}")
            false
        }
    }

    
    fun getMemoryInfo(): String {
        return try {
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val usedMemory = totalMemory - freeMemory
            val maxMemory = runtime.maxMemory()

            "Memory: ${usedMemory / 1024 / 1024}MB used / ${maxMemory / 1024 / 1024}MB max (${getMemoryUsagePercentage()}%)"
        } catch (e: Exception) {
            "Memory: Unknown (${e.message})"
        }
    }
}
