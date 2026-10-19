package io.github.ravenor907.ravendisk.backend

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.util.concurrent.TimeUnit

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

/** What this phone offers: USB host, root, and the disk tools Android ships. */
data class PlatformInfo(
    val androidVersion: String,
    val sdk: Int,
    val device: String,
    val abi: String,
    val usbHost: Boolean,
    /** Paths where an su binary exists (doesn't mean it grants root). */
    val suBinaries: List<String>,
    /** Tool name to path, for the tools root operations would use. */
    val tools: Map<String, String?>,
)

sealed class RootCheck {
    object NotChecked : RootCheck()
    object Checking : RootCheck()
    data class Granted(val detail: String) : RootCheck()
    data class Denied(val reason: String) : RootCheck()
}

object Platform {
    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su")
    private val TOOL_DIRS = listOf("/system/bin", "/system/xbin", "/vendor/bin", "/system_ext/bin")
    val TOOLS = listOf("mke2fs", "e2fsck", "resize2fs", "make_f2fs", "fsck.f2fs", "newfs_msdos", "fsck_msdos", "sgdisk", "blkid")

    fun read(context: Context): PlatformInfo = PlatformInfo(
        androidVersion = Build.VERSION.RELEASE ?: "?",
        sdk = Build.VERSION.SDK_INT,
        device = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.distinct().joinToString(" "),
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
        usbHost = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
        suBinaries = SU_PATHS.filter { File(it).exists() },
        tools = TOOLS.associateWith { t -> TOOL_DIRS.map { "$it/$t" }.firstOrNull { File(it).exists() } },
    )

    /**
     * Asks su for root. Shows the root manager's prompt, so it only runs when the person taps the
     * button for it. Runs `id` and nothing else.
     */
    fun checkRoot(): RootCheck = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroy()
            RootCheck.Denied("No answer from su within 20 seconds")
        } else {
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.exitValue() == 0 && out.contains("uid=0")) RootCheck.Granted(out.substringBefore(' ')) else RootCheck.Denied(out.ifEmpty { "su refused (exit ${p.exitValue()})" })
        }
    } catch (e: Exception) {
        RootCheck.Denied("No su binary could be run")
    }
}
