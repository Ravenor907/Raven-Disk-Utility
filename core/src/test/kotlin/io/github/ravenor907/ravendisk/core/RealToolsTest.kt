package io.github.ravenor907.ravendisk.core

import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.fs.Probe
import io.github.ravenor907.ravendisk.core.inspect.Inspector
import io.github.ravenor907.ravendisk.core.io.FileDisk
import io.github.ravenor907.ravendisk.core.plan.DiskIdentity
import io.github.ravenor907.ravendisk.core.plan.DiskSession
import io.github.ravenor907.ravendisk.core.plan.DiskSnapshot
import io.github.ravenor907.ravendisk.core.plan.Executor
import io.github.ravenor907.ravendisk.core.plan.Op
import io.github.ravenor907.ravendisk.core.plan.Outcome
import io.github.ravenor907.ravendisk.core.plan.Planner
import io.github.ravenor907.ravendisk.core.table.PartitionTypes
import io.github.ravenor907.ravendisk.core.table.TableKind
import io.github.ravenor907.ravendisk.core.table.TableRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Cross-checks against the real Linux tools, in both directions. CI sets the environment
 * variables (see .github/workflows/android.yml); locally these tests are skipped.
 *
 * RAVEN_FIXTURES: a directory of images made by mkfs/sgdisk plus expect.tsv
 *   (image, partition number or 0, blkid TYPE, LABEL, UUID). The app's reader must agree.
 * RAVEN_EXPORT: a directory to write images made by the app's own code, which CI then checks
 *   with sgdisk --verify, fsck.vfat, fsck.exfat, blkid and by mounting them.
 */
class RealToolsTest {
    private val blkidTypes = mapOf(
        "vfat" to setOf(FsType.FAT12, FsType.FAT16, FsType.FAT32), "exfat" to setOf(FsType.EXFAT), "ntfs" to setOf(FsType.NTFS),
        "ext2" to setOf(FsType.EXT2), "ext3" to setOf(FsType.EXT3), "ext4" to setOf(FsType.EXT4), "btrfs" to setOf(FsType.BTRFS),
        "xfs" to setOf(FsType.XFS), "f2fs" to setOf(FsType.F2FS), "udf" to setOf(FsType.UDF), "swap" to setOf(FsType.SWAP),
        "iso9660" to setOf(FsType.ISO9660), "crypto_LUKS" to setOf(FsType.LUKS),
    )

    /** Types whose label and identifier the probe claims to read. */
    private val labelsRead = setOf(
        FsType.FAT12, FsType.FAT16, FsType.FAT32, FsType.EXFAT, FsType.NTFS, FsType.EXT2, FsType.EXT3, FsType.EXT4,
        FsType.BTRFS, FsType.XFS, FsType.F2FS, FsType.SWAP, FsType.LUKS,
    )

    @Test
    fun readsImagesMadeByRealTools() {
        val dir = System.getenv("RAVEN_FIXTURES")?.let { File(it) }
        assumeTrue(dir != null && File(dir, "expect.tsv").exists())
        val problems = ArrayList<String>()
        var checked = 0
        for (line in File(dir, "expect.tsv").readLines().filter { it.isNotBlank() }) {
            val f = line.split('\t')
            val (image, part, type) = Triple(f[0], f[1].toInt(), f[2])
            val label = f.getOrElse(3) { "" }
            val uuid = f.getOrElse(4) { "" }
            FileDisk.open(File(dir, image)).use { d ->
                val info = if (part == 0) {
                    Probe.probe(d, 0, d.blockCount)
                } else {
                    val ins = Inspector.inspect(d)
                    val t = (ins.table as? TableRead.Found)?.table
                    if (t == null) {
                        problems.add("$image: no partition table read")
                        return@use
                    }
                    ins.fs[part]
                }
                val want = blkidTypes[type]
                when {
                    info == null -> problems.add("$image#$part: not recognized (blkid says $type)")
                    want == null -> problems.add("$image#$part: test has no mapping for $type")
                    info.type !in want -> problems.add("$image#$part: read ${info.type}, blkid says $type")
                    info.type in labelsRead && (info.label ?: "") != label -> problems.add("$image#$part: label '${info.label}', blkid says '$label'")
                    info.type in labelsRead && uuid.isNotEmpty() && !info.uuid.equals(uuid, ignoreCase = true) -> problems.add("$image#$part: id '${info.uuid}', blkid says '$uuid'")
                }
                checked++
            }
        }
        println("Checked $checked filesystems made by real tools")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertTrue("only $checked fixtures", checked >= (System.getenv("RAVEN_MIN_FIXTURES")?.toInt() ?: 10))
    }

    private class FileSession(override val dev: FileDisk) : DiskSession {
        override fun identity() = DiskIdentity.of(dev, "file", "Image", null)
        override fun close() = dev.close()
    }

    private fun build(file: File, mib: Long, blockSize: Int = 512, plan: (Planner, Long) -> Unit) {
        file.delete()
        FileSession(FileDisk(file, mib * 1024 * 1024 / blockSize, blockSize)).use { s ->
            val ins = Inspector.inspect(s.dev)
            val p = Planner(DiskSnapshot(s.identity(), blockSize, ins.table, ins.fs))
            plan(p, 1024L * 1024 / blockSize)
            val r = Executor(s, p).run()
            assertEquals("${file.name}: ${r.summary} ${r.checks}", Outcome.SUCCESS, r.outcome)
        }
    }

    @Test
    fun exportImagesForRealTools() {
        val dir = System.getenv("RAVEN_EXPORT")?.let { File(it) }
        assumeTrue(dir != null)
        dir!!.mkdirs()
        val expect = StringBuilder()
        // GPT: EFI (FAT32), data (exFAT), swap, unformatted.
        build(File(dir, "gpt.img"), 512) { p, m ->
            p.add(Op.NewTable(TableKind.GPT))
            p.add(Op.Create(m, 101 * m - 1, io.github.ravenor907.ravendisk.core.fs.FsType.FAT32, "EFI", gptType = PartitionTypes.EFI_SYSTEM, name = "EFI system"))
            p.add(Op.Create(101 * m, 301 * m - 1, FsType.EXFAT, "Raven Data", name = "data"))
            p.add(Op.Create(301 * m, 365 * m - 1, FsType.SWAP, "swap"))
            p.add(Op.Create(365 * m, 500 * m - 1, null, name = "spare"))
        }
        expect.append("gpt.img\t1\tvfat\tEFI\n").append("gpt.img\t2\texfat\tRaven Data\n").append("gpt.img\t3\tswap\tswap\n")
        // MBR: bootable FAT32 + exFAT.
        build(File(dir, "mbr.img"), 256) { p, m ->
            p.add(Op.NewTable(TableKind.MBR))
            p.add(Op.Create(m, 65 * m - 1, FsType.FAT32, "BOOT"))
            p.add(Op.SetBootable(1, true))
            p.add(Op.Create(65 * m, 255 * m - 1, FsType.EXFAT, "Stuff"))
        }
        expect.append("mbr.img\t1\tvfat\tBOOT\n").append("mbr.img\t2\texfat\tStuff\n")
        // 4 KiB logical sectors (checked through a loop device with -b 4096).
        // FAT32 needs 65525 clusters of at least one 4 KiB sector each: 256 MiB minimum here.
        build(File(dir, "gpt4k.img"), 512, 4096) { p, m ->
            p.add(Op.NewTable(TableKind.GPT))
            p.add(Op.Create(m, 128 * m - 1, FsType.EXFAT, "FourK"))
            p.add(Op.Create(128 * m, 511 * m - 1, FsType.FAT32, "FAT4K"))
        }
        expect.append("gpt4k.img\t1\texfat\tFourK\n").append("gpt4k.img\t2\tvfat\tFAT4K\n")
        // Relabel in place, then change the table around existing data.
        build(File(dir, "edit.img"), 256) { p, m ->
            p.add(Op.NewTable(TableKind.GPT))
            p.add(Op.Create(m, 100 * m - 1, FsType.EXFAT, "Before"))
            p.add(Op.Create(100 * m, 200 * m - 1, FsType.FAT32, "OLD"))
        }
        FileSession(FileDisk.open(File(dir, "edit.img"))).use { s ->
            val ins = Inspector.inspect(s.dev)
            val p = Planner(DiskSnapshot(s.identity(), 512, ins.table, ins.fs))
            p.add(Op.SetLabel(1, "After"))
            p.add(Op.SetLabel(2, "NEW"))
            p.add(Op.SetName(1, "renamed"))
            p.add(Op.Create(200 * 2048, 255 * 2048 - 1, FsType.SWAP, "late"))
            val r = Executor(s, p).run()
            assertEquals(r.checks.toString(), Outcome.SUCCESS, r.outcome)
        }
        expect.append("edit.img\t1\texfat\tAfter\n").append("edit.img\t2\tvfat\tNEW\n").append("edit.img\t3\tswap\tlate\n")
        File(dir, "expect.tsv").writeText(expect.toString())
    }
}
