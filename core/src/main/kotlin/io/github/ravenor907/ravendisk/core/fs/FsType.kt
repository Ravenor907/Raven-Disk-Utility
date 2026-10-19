package io.github.ravenor907.ravendisk.core.fs

import io.github.ravenor907.ravendisk.core.table.PartitionTypes
import io.github.ravenor907.ravendisk.core.util.Guid

enum class FsType(
    val label: String,
    /** Longest label the filesystem stores, in characters; 0 = no label. */
    val maxLabel: Int,
    val gptType: Guid?,
    val mbrType: Int?,
) {
    FAT12("FAT12", 11, PartitionTypes.MS_BASIC_DATA, 0x01),
    FAT16("FAT16", 11, PartitionTypes.MS_BASIC_DATA, 0x0E),
    FAT32("FAT32", 11, PartitionTypes.MS_BASIC_DATA, 0x0C),
    EXFAT("exFAT", 11, PartitionTypes.MS_BASIC_DATA, 0x07),
    NTFS("NTFS", 32, PartitionTypes.MS_BASIC_DATA, 0x07),
    EXT2("ext2", 16, PartitionTypes.LINUX_FS, 0x83),
    EXT3("ext3", 16, PartitionTypes.LINUX_FS, 0x83),
    EXT4("ext4", 16, PartitionTypes.LINUX_FS, 0x83),
    BTRFS("Btrfs", 255, PartitionTypes.LINUX_FS, 0x83),
    XFS("XFS", 12, PartitionTypes.LINUX_FS, 0x83),
    F2FS("F2FS", 512, PartitionTypes.LINUX_FS, 0x83),
    UDF("UDF", 126, PartitionTypes.MS_BASIC_DATA, 0x07),
    SWAP("Linux swap", 16, PartitionTypes.LINUX_SWAP, 0x82),
    ISO9660("ISO 9660", 32, null, null),
    HFSPLUS("HFS+", 255, PartitionTypes.APPLE_HFS, 0xAF),
    APFS("APFS", 255, PartitionTypes.APPLE_APFS, null),
    LUKS("LUKS encrypted", 48, PartitionTypes.LINUX_LUKS, 0x83),
    BITLOCKER("BitLocker encrypted", 0, PartitionTypes.MS_BASIC_DATA, 0x07),
    LVM2("LVM physical volume", 0, PartitionTypes.LINUX_LVM, 0x8E),
    MDRAID("Linux RAID member", 0, PartitionTypes.LINUX_RAID, 0xFD),
}

enum class FsOp(val label: String) {
    CREATE("Create"),
    INSPECT("Inspect"),
    CHECK("Check"),
    REPAIR("Repair"),
    RESIZE("Resize"),
    LABEL("Label"),
}

/** How an operation is (or isn't) available. */
sealed class Support {
    /** Implemented in the app itself; works on any device the app can write to. */
    object Native : Support()

    /** Needs a tool binary that runs with root; [tool] is what to look for. */
    data class NeedsTool(val tool: String) : Support()

    /** Not supported, with the reason shown to the person. */
    data class No(val reason: String) : Support()
}

/**
 * The filesystem support matrix. This is the single source of truth the UI shows: an operation
 * that isn't [Support.Native] here has no button that pretends otherwise.
 */
object FsSupport {
    private val nativeCreate = setOf(FsType.FAT32, FsType.EXFAT, FsType.SWAP)
    private val nativeLabel = setOf(FsType.FAT12, FsType.FAT16, FsType.FAT32, FsType.EXFAT, FsType.EXT2, FsType.EXT3, FsType.EXT4, FsType.SWAP)
    private val inspectable = FsType.entries.toSet()

    /** Root tools that Android itself ships (or that are commonly present) for each filesystem. */
    private val createTools = mapOf(
        FsType.EXT4 to "mke2fs", FsType.EXT3 to "mke2fs", FsType.EXT2 to "mke2fs", FsType.F2FS to "make_f2fs",
        FsType.FAT16 to "newfs_msdos", FsType.NTFS to "mkntfs", FsType.BTRFS to "mkfs.btrfs", FsType.XFS to "mkfs.xfs",
        FsType.UDF to "mkudffs",
    )
    private val checkTools = mapOf(
        FsType.EXT2 to "e2fsck", FsType.EXT3 to "e2fsck", FsType.EXT4 to "e2fsck", FsType.F2FS to "fsck.f2fs",
        FsType.FAT12 to "fsck_msdos", FsType.FAT16 to "fsck_msdos", FsType.FAT32 to "fsck_msdos", FsType.EXFAT to "fsck.exfat",
        FsType.NTFS to "ntfsfix", FsType.BTRFS to "btrfs", FsType.XFS to "xfs_repair",
    )
    private val resizeTools = mapOf(
        FsType.EXT2 to "resize2fs", FsType.EXT3 to "resize2fs", FsType.EXT4 to "resize2fs", FsType.F2FS to "resize.f2fs",
        FsType.NTFS to "ntfsresize", FsType.BTRFS to "btrfs",
    )

    fun of(fs: FsType, op: FsOp): Support = when (op) {
        FsOp.INSPECT -> if (fs in inspectable) Support.Native else Support.No("Not recognized")
        FsOp.CREATE -> when {
            fs in nativeCreate -> Support.Native
            fs in createTools -> Support.NeedsTool(createTools.getValue(fs))
            else -> Support.No("Creating ${fs.label} isn't supported")
        }
        FsOp.LABEL -> when {
            fs.maxLabel == 0 -> Support.No("${fs.label} has no label")
            fs in nativeLabel -> Support.Native
            else -> Support.No("Changing ${fs.label} labels isn't supported yet")
        }
        FsOp.CHECK, FsOp.REPAIR -> checkTools[fs]?.let { Support.NeedsTool(it) } ?: Support.No("No checker for ${fs.label}")
        FsOp.RESIZE -> when {
            fs == FsType.SWAP -> Support.No("Recreate swap at the new size instead")
            fs == FsType.XFS -> Support.No("XFS can't shrink, and growing needs it mounted")
            else -> resizeTools[fs]?.let { Support.NeedsTool(it) } ?: Support.No("Resizing ${fs.label} isn't supported")
        }
    }

    /** Filesystems the app can create itself, in menu order. */
    val creatable: List<FsType> = listOf(FsType.EXFAT, FsType.FAT32, FsType.SWAP)

    /** Smallest partition each native format accepts, in bytes. */
    fun minBytes(fs: FsType): Long = when (fs) {
        FsType.FAT32 -> 33L * 1024 * 1024 // FAT32 needs at least 65525 clusters
        FsType.EXFAT -> 1L * 1024 * 1024
        FsType.SWAP -> 40L * 1024
        else -> 1L * 1024 * 1024
    }

    /** Largest partition each native format accepts, in bytes. */
    fun maxBytes(fs: FsType): Long = when (fs) {
        FsType.FAT32 -> 2L * 1024 * 1024 * 1024 * 1024 // 2 TiB with 512-byte sectors (32-bit sector count)
        FsType.SWAP -> Long.MAX_VALUE
        else -> Long.MAX_VALUE
    }

    /**
     * Whether the app's own formatter can lay out [fs] in [blocks] blocks of [blockSize] bytes,
     * computed with the formatter's real layout code; null = fine, otherwise the reason.
     * FAT32 on 4 KiB-sector drives, for example, needs at least 256 MiB.
     */
    fun layoutProblem(fs: FsType, blockSize: Int, blocks: Long): String? = try {
        when (fs) {
            FsType.FAT32 -> {
                Fat32.layout(blockSize, blocks)
                null
            }
            FsType.EXFAT -> {
                ExFat.layout(blockSize, 0, blocks)
                null
            }
            FsType.SWAP -> if (blocks * blockSize / Swap.PAGE < 10) "The partition is too small for swap" else null
            else -> "Creating ${fs.label} isn't available in this version"
        }
    } catch (e: IllegalArgumentException) {
        e.message ?: "The partition's size doesn't suit ${fs.label}"
    }

    /** Label validation for native labelers; null = fine. */
    fun labelProblem(fs: FsType, label: String): String? {
        if (label.length > fs.maxLabel) return "${fs.label} labels are at most ${fs.maxLabel} characters"
        return when (fs) {
            FsType.FAT12, FsType.FAT16, FsType.FAT32 -> when {
                label.any { it.code !in 0x20..0x7E } -> "FAT labels can only use plain letters, digits and symbols"
                label.any { it in "\"*+,./:;<=>?[\\]|" } -> "FAT labels can't contain \" * + , . / : ; < = > ? [ \\ ] |"
                else -> null
            }
            FsType.EXFAT -> if (label.any { it.code < 0x20 || it in "\"*/:<>?\\|" }) "exFAT labels can't contain \" * / : < > ? \\ |" else null
            FsType.EXT2, FsType.EXT3, FsType.EXT4, FsType.SWAP ->
                if (label.toByteArray(Charsets.UTF_8).size > 16) "Labels are at most 16 bytes" else null
            else -> null
        }
    }
}
