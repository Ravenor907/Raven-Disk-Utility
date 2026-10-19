package io.github.ravenor907.ravendisk.core

import io.github.ravenor907.ravendisk.core.fs.ExFat
import io.github.ravenor907.ravendisk.core.io.MemoryDisk
import io.github.ravenor907.ravendisk.core.io.SparseDisk
import io.github.ravenor907.ravendisk.core.table.Align
import io.github.ravenor907.ravendisk.core.table.Partition
import io.github.ravenor907.ravendisk.core.table.PartitionTable
import io.github.ravenor907.ravendisk.core.table.PartitionTypes
import io.github.ravenor907.ravendisk.core.table.TableKind
import io.github.ravenor907.ravendisk.core.table.TableRead
import io.github.ravenor907.ravendisk.core.table.TableReader
import io.github.ravenor907.ravendisk.core.table.TableWriter
import io.github.ravenor907.ravendisk.core.util.Crc
import io.github.ravenor907.ravendisk.core.util.Guid
import io.github.ravenor907.ravendisk.core.util.hex
import io.github.ravenor907.ravendisk.core.util.le
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TableTest {
    private fun found(r: TableRead) = (r as? TableRead.Found)?.table ?: throw AssertionError("expected a table, got $r")

    private fun gptWith(d: io.github.ravenor907.ravendisk.core.io.BlockDevice, vararg parts: Partition): PartitionTable {
        val t = PartitionTable.emptyGpt(d.blockSize, d.blockCount, Guid.parse("11111111-2222-3333-4444-555555555555")).copy(partitions = parts.toList())
        TableWriter.write(d, t)
        return t
    }

    @Test
    fun guidOnDiskByteOrder() {
        // The EFI system partition type as it appears on disk (UEFI spec, mixed endian).
        assertEquals("28732ac11ff8d211ba4b00a0c93ec93b", PartitionTypes.EFI_SYSTEM.toBytes().hex())
        assertEquals(PartitionTypes.EFI_SYSTEM, Guid.fromBytes(PartitionTypes.EFI_SYSTEM.toBytes()))
        val r = Guid.random()
        assertEquals(r, Guid.fromBytes(r.toBytes()))
        assertEquals('4', r.text[14]) // version 4
    }

    @Test
    fun crc32cKnownVector() {
        assertEquals(0xE3069283L, Crc.crc32c("123456789".toByteArray()))
    }

    @Test
    fun gptRoundTrip() {
        val d = MemoryDisk(blockCount = 262144) // 128 MiB
        val p1 = Partition(1, 2048, 206847, PartitionTypes.EFI_SYSTEM, Guid.random(), "EFI system", attributes = 1)
        val p2 = Partition(2, 206848, 262110, PartitionTypes.LINUX_FS, Guid.random(), "rootfs ✓")
        gptWith(d, p1, p2)
        val t = found(TableReader.read(d))
        assertEquals(TableKind.GPT, t.kind)
        assertEquals(34L, t.firstUsable)
        assertEquals(262144L - 34, t.lastUsable)
        assertEquals(listOf(p1, p2), t.partitions)
        assertTrue(t.issues.isEmpty())
        assertTrue(t.protectiveMbr)
        assertEquals("EFI system", t.partitions[0].typeName())
    }

    @Test
    fun protectiveMbrCoversDisk() {
        val d = MemoryDisk(blockCount = 65536)
        gptWith(d)
        val s = d.readBlocks(0, 1)
        assertEquals(0xEE, s[446 + 4].toInt() and 0xFF)
        assertEquals(1, s.le().getInt(446 + 8))
        assertEquals(65535, s.le().getInt(446 + 12))
        assertEquals(0x55, s[510].toInt() and 0xFF)
    }

    @Test
    fun damagedPrimaryFallsBackToBackup() {
        val d = MemoryDisk(blockCount = 65536)
        val p = Partition(1, 2048, 40000, PartitionTypes.MS_BASIC_DATA, Guid.random(), "data")
        gptWith(d, p)
        d.write(1, 1, ByteArray(512)) // destroy the primary header
        val t = found(TableReader.read(d))
        assertEquals(listOf(p), t.partitions)
        assertTrue(t.issues.any { it.contains("primary GPT is damaged") })
        assertFalse(t.damaged) // rewriting repairs it
    }

    @Test
    fun corruptEntriesWithoutBackupAreMarkedDamaged() {
        val d = MemoryDisk(blockCount = 65536)
        gptWith(d, Partition(1, 2048, 40000, PartitionTypes.MS_BASIC_DATA, Guid.random(), "data"))
        d.write(65535, 1, ByteArray(512)) // backup header gone
        val e = d.readBlocks(2, 1)
        e[100] = (e[100] + 1).toByte()
        d.write(2, 1, e) // primary entries no longer match their CRC
        val t = found(TableReader.read(d))
        assertTrue(t.damaged)
    }

    @Test
    fun fourKnGpt() {
        val d = MemoryDisk(blockSize = 4096, blockCount = 16384) // 64 MiB
        val p = Partition(1, 256, 16000, PartitionTypes.LINUX_FS, Guid.random(), "x")
        gptWith(d, p)
        val t = found(TableReader.read(d))
        assertEquals(6L, t.firstUsable) // 1 header + 4 entry blocks + MBR
        assertEquals(listOf(p), t.partitions)
    }

    @Test
    fun mbrRoundTripKeepsBootCodeAndSignature() {
        val d = MemoryDisk(blockCount = 65536)
        val boot = ByteArray(512) { (it % 251).toByte() }
        d.write(0, 1, boot)
        val t = PartitionTable.emptyMbr(d.blockCount, 0xCAFEBABEL).copy(
            partitions = listOf(
                Partition(1, 2048, 20479, mbrType = 0x0C, bootable = true),
                Partition(3, 20480, 65535, mbrType = 0x83),
            ),
        )
        TableWriter.write(d, t)
        val back = found(TableReader.read(d))
        assertEquals(TableKind.MBR, back.kind)
        assertEquals(0xCAFEBABEL, back.diskSignature)
        assertEquals(t.partitions, back.partitions)
        val s = d.readBlocks(0, 1)
        assertTrue(s.copyOfRange(0, 440).contentEquals(boot.copyOfRange(0, 440)))
    }

    @Test
    fun switchingToMbrClearsGptHeaders() {
        val d = MemoryDisk(blockCount = 65536)
        gptWith(d, Partition(1, 2048, 4095, PartitionTypes.LINUX_FS, Guid.random()))
        TableWriter.write(d, PartitionTable.emptyMbr(d.blockCount).copy(partitions = listOf(Partition(1, 2048, 4095, mbrType = 0x83))))
        val t = found(TableReader.read(d))
        assertEquals(TableKind.MBR, t.kind)
        assertEquals(null, TableReader.gptHeaderAt(d, 65535))
    }

    @Test
    fun readsLogicalPartitions() {
        val d = MemoryDisk(blockCount = 65536)
        val s = ByteArray(512)
        fun entry(sector: ByteArray, slot: Int, type: Int, start: Long, count: Long) {
            val o = 446 + slot * 16
            sector[o + 4] = type.toByte()
            sector.le().putInt(o + 8, start.toInt()).putInt(o + 12, count.toInt())
            sector[510] = 0x55; sector[511] = 0xAA.toByte()
        }
        entry(s, 0, 0x83, 2048, 8192)
        entry(s, 1, 0x05, 10240, 40960) // extended 10240..51199
        d.write(0, 1, s)
        val e1 = ByteArray(512)
        entry(e1, 0, 0x07, 2048, 10000) // logical 5 at 12288
        entry(e1, 1, 0x05, 20480, 20480) // next EBR at 30720
        d.write(10240, 1, e1)
        val e2 = ByteArray(512)
        entry(e2, 0, 0x83, 2048, 8192) // logical 6 at 32768
        d.write(30720, 1, e2)
        val t = found(TableReader.read(d))
        assertTrue(t.hasLogical)
        val l5 = t.byNumber(5)!!
        val l6 = t.byNumber(6)!!
        assertEquals(12288L, l5.firstLba)
        assertEquals(32768L, l6.firstLba)
        assertTrue(l5.logical && l6.logical)
    }

    @Test
    fun ebrLoopIsDetected() {
        val d = MemoryDisk(blockCount = 65536)
        val s = ByteArray(512)
        s[446 + 4] = 0x05; s.le().putInt(446 + 8, 2048).putInt(446 + 12, 60000)
        s[510] = 0x55; s[511] = 0xAA.toByte()
        d.write(0, 1, s)
        fun ebr(at: Long, nextRel: Int) {
            val e = ByteArray(512)
            e[446 + 4] = 0x83.toByte(); e.le().putInt(446 + 8, 63).putInt(446 + 12, 100)
            e[462 + 4] = 0x05; e.le().putInt(462 + 8, nextRel).putInt(462 + 12, 200)
            e[510] = 0x55; e[511] = 0xAA.toByte()
            d.write(at, 1, e)
        }
        ebr(2048, 10000) // first EBR -> second
        ebr(12048, 10000) // second EBR -> itself
        val t = found(TableReader.read(d))
        assertEquals(2, t.partitions.count { it.logical })
        assertTrue(t.issues.any { it.contains("loops") })
    }

    @Test
    fun blankAndSuperfloppy() {
        val d = SparseDisk(blockCount = 1 shl 20)
        assertEquals(TableRead.Blank, TableReader.read(d))
        ExFat.format(d, 0, d.blockCount, "WHOLE")
        assertEquals(TableRead.Superfloppy, TableReader.read(d))
    }

    @Test
    fun freeRegions() {
        val t = PartitionTable.emptyGpt(512, 100_000).copy(
            partitions = listOf(
                Partition(1, 2048, 10239, PartitionTypes.LINUX_FS),
                Partition(2, 20480, 30719, PartitionTypes.LINUX_FS),
            ),
        )
        val free = t.freeRegions()
        assertEquals(listOf(34L until 2048L, 10240L until 20480L, 30720L..(100_000L - 34)), free)
    }

    @Test
    fun alignmentHelpers() {
        assertEquals(2048L, Align.mibBlocks(512))
        assertEquals(256L, Align.mibBlocks(4096))
        assertEquals(4096L, Align.up(2049, 2048))
        assertEquals(2048L, Align.down(4095, 2048))
        assertEquals("1 MiB aligned", Align.describe(2048, 512, 4096))
        assertEquals("Aligned to the physical sector, not to 1 MiB", Align.describe(64, 512, 4096))
        assertEquals("Not aligned to the physical sector (slower writes)", Align.describe(63, 512, 4096))
    }

    @Test
    fun writerRefusesPartitionsOutsideUsableArea() {
        val d = MemoryDisk(blockCount = 65536)
        val t = PartitionTable.emptyGpt(512, d.blockCount).copy(partitions = listOf(Partition(1, 10, 100, PartitionTypes.LINUX_FS)))
        try {
            TableWriter.write(d, t)
            fail("should refuse")
        } catch (e: IllegalArgumentException) {
        }
        assertTrue(d.writes == 0)
    }

    @Test
    fun hugeDriveGptOnSparseDisk() {
        val blocks = 8L * 1000 * 1000 * 1000 * 1000 / 512 // 8 TB
        val d = SparseDisk(blockCount = blocks)
        val p = Partition(1, 2048, blocks - 2048, PartitionTypes.LINUX_FS, Guid.random(), "big")
        gptWith(d, p)
        val t = found(TableReader.read(d))
        assertEquals(p, t.partitions.single())
        assertEquals(blocks - 34, t.lastUsable)
    }
}
