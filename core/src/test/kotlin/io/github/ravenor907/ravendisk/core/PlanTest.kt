package io.github.ravenor907.ravendisk.core

import io.github.ravenor907.ravendisk.core.fs.FsInfo
import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.inspect.Inspector
import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.io.SparseDisk
import io.github.ravenor907.ravendisk.core.plan.DiskIdentity
import io.github.ravenor907.ravendisk.core.plan.DiskSnapshot
import io.github.ravenor907.ravendisk.core.plan.EraseMethod
import io.github.ravenor907.ravendisk.core.plan.Op
import io.github.ravenor907.ravendisk.core.plan.PlanException
import io.github.ravenor907.ravendisk.core.plan.Planner
import io.github.ravenor907.ravendisk.core.plan.VolumeChange
import io.github.ravenor907.ravendisk.core.table.Partition
import io.github.ravenor907.ravendisk.core.table.PartitionTable
import io.github.ravenor907.ravendisk.core.table.PartitionTypes
import io.github.ravenor907.ravendisk.core.table.TableKind
import io.github.ravenor907.ravendisk.core.table.TableRead
import io.github.ravenor907.ravendisk.core.table.TableWriter
import io.github.ravenor907.ravendisk.core.util.Guid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlanTest {
    private val gib = 1024L * 1024 * 1024 / 512

    private fun snapOf(d: BlockDevice, mounted: Set<Int> = emptySet(), readOnly: Boolean = false, discard: Boolean = false, phys: Int? = 512): DiskSnapshot {
        val i = Inspector.inspect(d)
        return DiskSnapshot(DiskIdentity.of(d, "test", "Test Drive", "SN1"), phys, i.table, i.fs, mounted, readOnly = readOnly, discardSupported = discard)
    }

    private fun blank(blocks: Long = 64 * gib) = SparseDisk(blockCount = blocks)

    private fun gptDisk(vararg parts: Partition): SparseDisk {
        val d = blank()
        TableWriter.write(d, PartitionTable.emptyGpt(512, d.blockCount).copy(partitions = parts.toList()))
        return d
    }

    private fun refused(p: Planner, op: Op, contains: String) {
        try {
            p.add(op)
            fail("expected refusal: $op")
        } catch (e: PlanException) {
            assertTrue("'${e.message}' should mention '$contains'", e.message!!.contains(contains, ignoreCase = true))
        }
    }

    @Test
    fun blankDriveNeedsATableFirst() {
        val p = Planner(snapOf(blank()))
        refused(p, Op.Create(2048, 4095, null), "partition table first")
        p.add(Op.NewTable(TableKind.GPT))
        p.add(Op.Create(2048, 2048 + gib - 1, FsType.EXFAT, "DATA"))
        assertEquals(1, p.current.volumes.size)
        assertEquals(VolumeChange.NEW, p.current.volumes[0].change)
        assertEquals(PartitionTypes.MS_BASIC_DATA, p.current.volumes[0].partition.typeGuid)
    }

    @Test
    fun refusesOverlapOutOfRangeAndMisalignment() {
        val p = Planner(snapOf(gptDisk(Partition(1, 2048, 2048 + gib - 1, PartitionTypes.LINUX_FS, Guid.random())), phys = 4096))
        refused(p, Op.Create(2048 + gib - 2048, 2048 + 2 * gib, null), "overlaps partition 1")
        refused(p, Op.Create(10, 4000, null), "partition table area")
        refused(p, Op.Create(3 * gib, 64 * gib, null), "past the end")
        refused(p, Op.Create(2048 + gib + 1, 2048 + 2 * gib, null), "physical sectors")
        refused(p, Op.Create(2048 + gib, 2048 + gib + 100, null), "at least 1 MiB")
        assertTrue(p.queue.isEmpty())
    }

    @Test
    fun mbrAllowsFourPrimariesAndNoMoreThan2TiB() {
        val p = Planner(snapOf(blank()))
        p.add(Op.NewTable(TableKind.MBR))
        for (i in 0 until 4) p.add(Op.Create(2048 + i * gib, 2048 + (i + 1) * gib - 1, null))
        refused(p, Op.Create(2048 + 5 * gib, 2048 + 6 * gib - 1, null), "four primary")

        val big = Planner(snapOf(SparseDisk(blockCount = 3L * 1024 * 1024 * 1024 * 2))) // 3 TiB
        refused(big, Op.NewTable(TableKind.MBR), "2 TiB")
    }

    @Test
    fun formatChecksSizeLimitsAndSetsTheType() {
        val d = gptDisk(
            Partition(1, 2048, 2048 + 40959, PartitionTypes.LINUX_FS, Guid.random()), // 20 MiB
            Partition(2, 2048 + 40960, 2048 + 40960 + gib - 1, PartitionTypes.LINUX_FS, Guid.random()),
        )
        val p = Planner(snapOf(d))
        refused(p, Op.Format(1, FsType.FAT32), "at least")
        p.add(Op.Format(2, FsType.FAT32, "BACKUP"))
        val v = p.current.volume(2)!!
        assertEquals(VolumeChange.FORMATTED, v.change)
        assertEquals(PartitionTypes.MS_BASIC_DATA, v.partition.typeGuid)
        refused(p, Op.Format(2, FsType.EXT4), "isn't available")
        refused(p, Op.Format(2, FsType.FAT32, "bad/label"), "can't contain")
    }

    @Test
    fun fat32OnFourKnDrivesNeedsRealLayoutRoom() {
        val d = SparseDisk(blockSize = 4096, blockCount = 1L shl 20) // 4 GiB
        val p = Planner(DiskSnapshot(DiskIdentity.of(d, "t", "T", null), 4096, Inspector.inspect(d).table, emptyMap()))
        p.add(Op.NewTable(TableKind.GPT))
        refused(p, Op.Create(256, 256 + 200L * 256 - 1, FsType.FAT32), "too small for FAT32") // 200 MiB
        p.add(Op.Create(256, 256 + 300L * 256 - 1, FsType.FAT32)) // 300 MiB
    }

    @Test
    fun efiPartitionKeepsItsTypeWhenReformattedAsFat32() {
        val p = Planner(snapOf(gptDisk(Partition(1, 2048, 2048 + gib - 1, PartitionTypes.EFI_SYSTEM, Guid.random()))))
        p.add(Op.Format(1, FsType.FAT32, "EFI"))
        assertEquals(PartitionTypes.EFI_SYSTEM, p.current.volume(1)!!.partition.typeGuid)
    }

    @Test
    fun resizeIsRefusedForFilesystemsAndAllowedForEmptyPartitions() {
        val d = gptDisk(
            Partition(1, 2048, 2048 + gib - 1, PartitionTypes.LINUX_FS, Guid.random()),
            Partition(2, 2048 + gib, 2048 + 2 * gib - 1, PartitionTypes.LINUX_FS, Guid.random()),
        )
        io.github.ravenor907.ravendisk.core.fs.ExFat.format(d, 2048 + gib, gib, "DATA")
        val p = Planner(snapOf(d))
        refused(p, Op.Resize(2, 2048 + gib, 2048 + 3 * gib - 1), "resiz")
        p.add(Op.Resize(1, 2048, 2048 + gib / 2 - 1)) // partition 1 has no filesystem
        refused(p, Op.Resize(1, 2048, 2048 + gib), "overlaps partition 2")
        // A partition created in this plan can be resized freely before it's written.
        p.add(Op.Create(2048 + 4 * gib, 2048 + 5 * gib - 1, FsType.EXFAT))
        val n = p.current.volumes.last().partition.number
        p.add(Op.Resize(n, 2048 + 4 * gib, 2048 + 6 * gib - 1))
    }

    @Test
    fun mountedPartitionsAreProtected() {
        val d = gptDisk(
            Partition(1, 2048, 2048 + gib - 1, PartitionTypes.MS_BASIC_DATA, Guid.random()),
            Partition(2, 2048 + gib, 2048 + 2 * gib - 1, PartitionTypes.MS_BASIC_DATA, Guid.random()),
        )
        val p = Planner(snapOf(d, mounted = setOf(1)))
        refused(p, Op.Delete(1), "mounted")
        refused(p, Op.Format(1, FsType.EXFAT), "mounted")
        refused(p, Op.NewTable(TableKind.GPT), "mounted")
        refused(p, Op.Erase(null, EraseMethod.ZERO), "mounted")
        p.add(Op.Delete(2)) // the other one is fine
    }

    @Test
    fun readOnlyDriveRefusesEverything() {
        val p = Planner(snapOf(gptDisk(), readOnly = true))
        refused(p, Op.NewTable(TableKind.GPT), "write-protected")
        refused(p, Op.Create(2048, 4095 + 2048, null), "write-protected")
    }

    @Test
    fun logicalPartitionsAreReadOnly() {
        val d = blank()
        TableWriter.write(d, PartitionTable.emptyMbr(d.blockCount).copy(partitions = listOf(Partition(1, 2048, 4095, mbrType = 0x83))))
        val snap = snapOf(d)
        val t = (snap.table as TableRead.Found).table
        val withLogical = snap.copy(table = TableRead.Found(t.copy(partitions = t.partitions + Partition(5, 10000, 20000, mbrType = 0x83, logical = true))))
        val p = Planner(withLogical)
        refused(p, Op.Delete(5), "logical")
        refused(p, Op.Create(30000, 40000, null), "logical")
        p.add(Op.NewTable(TableKind.GPT)) // starting over is always possible
    }

    @Test
    fun damagedTablesMustBeReplaced() {
        val d = gptDisk(Partition(1, 2048, 4095, PartitionTypes.LINUX_FS, Guid.random()))
        val snap = snapOf(d)
        val t = (snap.table as TableRead.Found).table.copy(damaged = true, issues = listOf("Partitions 1 and 2 overlap"))
        val p = Planner(snap.copy(table = TableRead.Found(t)))
        refused(p, Op.Delete(1), "damaged")
        p.add(Op.NewTable(TableKind.GPT))
        p.add(Op.Create(2048, 2048 + gib - 1, null))
    }

    @Test
    fun discardNeedsDriveSupport() {
        val d = gptDisk(Partition(1, 2048, 2048 + gib - 1, PartitionTypes.LINUX_FS, Guid.random()))
        refused(Planner(snapOf(d)), Op.Erase(1, EraseMethod.DISCARD), "doesn't support discard")
        val p = Planner(snapOf(d, discard = true))
        p.add(Op.Erase(1, EraseMethod.DISCARD))
        assertEquals(VolumeChange.ERASED, p.current.volume(1)!!.change)
    }

    @Test
    fun deleteUndoAndReplay() {
        val p = Planner(snapOf(gptDisk(Partition(1, 2048, 2048 + gib - 1, PartitionTypes.LINUX_FS, Guid.random()))))
        p.add(Op.Delete(1))
        assertTrue(p.current.volumes.isEmpty())
        refused(p, Op.Delete(1), "doesn't exist")
        assertTrue(p.undo())
        assertEquals(1, p.current.volumes.size)
        p.replay(listOf(Op.Delete(1), Op.Create(2048, 2048 + gib - 1, FsType.SWAP, "swap")))
        assertEquals(2, p.queue.size)
        assertEquals(FsType.SWAP, p.current.volumes.single().fs)
        assertFalse(p.undo().not())
    }

    @Test
    fun labelOnlyForNativeFilesystems() {
        val d = gptDisk(Partition(1, 2048, 2048 + gib - 1, PartitionTypes.MS_BASIC_DATA, Guid.random()))
        val snap = snapOf(d).let { it.copy(fs = mapOf(1 to FsInfo(FsType.NTFS, "Windows"))) }
        val p = Planner(snap)
        refused(p, Op.SetLabel(1, "x"), "isn't supported")
        p.add(Op.Format(1, FsType.EXFAT, "A"))
        p.add(Op.SetLabel(1, "B")) // pending format: label goes into the new filesystem
        val v = p.current.volume(1)!!
        assertEquals("B", v.label)
        assertFalse(v.relabel)
    }

    @Test
    fun gptNamesAndAttributes() {
        val p = Planner(snapOf(gptDisk(Partition(1, 2048, 4095 + 2048, PartitionTypes.LINUX_FS, Guid.random()))))
        p.add(Op.SetName(1, "home"))
        p.add(Op.SetAttributes(1, 1L shl 63))
        refused(p, Op.SetName(1, "x".repeat(37)), "36")
        refused(p, Op.SetBootable(1, true), "Only MBR")
        assertEquals("home", p.current.volume(1)!!.partition.name)
        assertEquals(1L shl 63, p.current.volume(1)!!.partition.attributes)
    }

    @Test
    fun onlyOneActiveMbrPartition() {
        val d = blank()
        TableWriter.write(d, PartitionTable.emptyMbr(d.blockCount).copy(partitions = listOf(
            Partition(1, 2048, 4095 + 2048, mbrType = 0x83, bootable = true), Partition(2, 8192, 8192 + 4095, mbrType = 0x83),
        )))
        val p = Planner(snapOf(d))
        p.add(Op.SetBootable(2, true))
        assertFalse(p.current.volume(1)!!.partition.bootable)
        assertTrue(p.current.volume(2)!!.partition.bootable)
    }

    @Test
    fun placementSnapsToMib() {
        val r = Planner.placeInFree(34L..1_000_000L, 512, 100L * 1024 * 1024)!!
        assertEquals(2048L, r.first)
        assertEquals(2048L + 204800 - 1, r.last)
        val all = Planner.placeInFree(34L..1_000_000L, 512, null)!!
        assertEquals(2048L, all.first)
        assertEquals(999_423L, all.last) // last whole MiB
        assertNull(Planner.placeInFree(34L..2000L, 512, null))
        assertNotNull(Planner.placeInFree(2048L..(2048L + 2047), 512, null))
    }
}
