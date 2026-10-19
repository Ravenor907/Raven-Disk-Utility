package io.github.ravenor907.ravendisk.backend

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/** A volume Android has mounted, as the platform APIs describe it. Read-only information. */
data class AndroidVolume(
    val id: String,
    val description: String,
    val primary: Boolean,
    val removable: Boolean,
    val emulated: Boolean,
    val state: String,
    /** Filesystem serial ("1234-ABCD") for cards and USB drives; null for internal storage. */
    val uuid: String?,
    val path: String?,
    val totalBytes: Long?,
    val freeBytes: Long?,
    /** From the app's own mount table, when Android exposes it. */
    val fsType: String?,
) {
    val mounted get() = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
    val readOnly get() = state == Environment.MEDIA_MOUNTED_READ_ONLY

    /** Best guess at what kind of volume this is; vold's descriptions name SD cards as such. */
    val looksLikeSdCard get() = removable && Regex("\\bSD\\b|card", RegexOption.IGNORE_CASE).containsMatchIn(description)
    val looksLikeUsb get() = removable && !looksLikeSdCard
}

object AndroidVolumes {
    fun list(context: Context): List<AndroidVolume> {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager? ?: return emptyList()
        val mounts = mountTypes()
        return sm.storageVolumes.mapIndexed { i, v ->
            val dir: File? = if (Build.VERSION.SDK_INT >= 30) v.directory else null
            var total: Long? = null
            var free: Long? = null
            if (v.isPrimary) {
                try {
                    val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                    total = ssm.getTotalBytes(StorageManager.UUID_DEFAULT)
                    free = ssm.getFreeBytes(StorageManager.UUID_DEFAULT)
                } catch (e: Exception) {
                }
            }
            if (total == null && dir != null) {
                try {
                    val s = StatFs(dir.path)
                    total = s.totalBytes
                    free = s.availableBytes
                } catch (e: Exception) {
                }
            }
            val uuid = v.uuid
            AndroidVolume(
                id = uuid ?: if (v.isPrimary) "primary" else "volume$i",
                description = v.getDescription(context) ?: "Storage",
                primary = v.isPrimary,
                removable = v.isRemovable,
                emulated = v.isEmulated,
                state = v.state ?: Environment.MEDIA_UNKNOWN,
                uuid = uuid,
                path = dir?.path,
                totalBytes = total,
                freeBytes = free,
                fsType = uuid?.let { u -> mounts.firstOrNull { it.first.contains(u) }?.second },
            )
        }
    }

    /** (mount point, type) pairs from /proc/self/mounts, skipping FUSE/sdcardfs views. */
    private fun mountTypes(): List<Pair<String, String>> = try {
        File("/proc/self/mounts").readLines().mapNotNull { line ->
            val f = line.split(' ')
            if (f.size < 3) return@mapNotNull null
            val type = f[2]
            if (type.startsWith("fuse") || type == "sdcardfs" || type == "tmpfs" || type == "esdfs") null else f[1] to type
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** What this phone offers for external drives. */
data class PlatformInfo(
    val androidVersion: String,
    val sdk: Int,
    val device: String,
    val abi: String,
    val usbHost: Boolean,
)

object Platform {
    fun read(context: Context): PlatformInfo = PlatformInfo(
        androidVersion = Build.VERSION.RELEASE ?: "?",
        sdk = Build.VERSION.SDK_INT,
        device = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.distinct().joinToString(" "),
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
        usbHost = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
    )
}
