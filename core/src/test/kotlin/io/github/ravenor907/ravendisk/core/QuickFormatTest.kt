package io.github.ravenor907.ravendisk.core

import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.inspect.Inspector
import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.io.MemoryDisk
import io.github.ravenor907.ravendisk.core.plan.DiskIdentity
import io.github.ravenor907.ravendisk.core.plan.DiskSession
import io.github.ravenor907.ravendisk.core.plan.Outcome
import io.github.ravenor907.ravendisk.core.plan.QuickFormat
import io.github.ravenor907.ravendisk.core.table.TableKind
import io.github.ravenor907.ravendisk.core.table.TableRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickFormatTest {
    private class Session(override val dev: BlockDevice) : DiskSession {
        override fun identity() = DiskIdentity.of(dev, "t", "Test Stick", "SN")
        override fun close() {}
    }

    @Test
    fun eachChoiceMakesOneReadablePartition() {
        for ((fs, _) in QuickFormat.CHOICES) {
            val d = MemoryDisk(blockCount = 300L * 2048) // 300 MiB: big enough for FAT32 too
            java.util.Arrays.fill(d.data, 0, 4096, 0x5A) // junk where the old layout was
            val snap = QuickFormat.snapshot(d, "t", "Test Stick", "SN")
            val r = QuickFormat.run(Session(d), snap, fs, "GAMES")
            assertEquals("$fs: ${r.summary} ${r.checks}", Outcome.SUCCESS, r.outcome)
            val ins = Inspector.inspect(d)
            val t = (ins.table as TableRead.Found).table
            assertEquals(TableKind.MBR, t.kind)
            assertEquals(1, t.partitions.size)
            val f = ins.fs[t.partitions[0].number]
            assertNotNull(f)
            assertEquals(fs, f!!.type)
            assertEquals("GAMES", f.label)
        }
    }

    @Test
    fun tooSmallForFat32IsExplainedNotAttempted() {
        val d = MemoryDisk(blockCount = 16L * 2048)
        val snap = QuickFormat.snapshot(d, "t", "Tiny", null)
        val (ops, problem) = QuickFormat.plan(snap, FsType.FAT32, "")
        assertNull(ops)
        assertNotNull(problem)
        assertNull(QuickFormat.plan(snap, FsType.EXFAT, "").second)
    }

    @Test
    fun aSwappedDriveIsNotWritten() {
        val d = MemoryDisk(blockCount = 64L * 2048)
        val snap = QuickFormat.snapshot(d, "t", "Test Stick", "SN")
        val other = object : DiskSession {
            override val dev: BlockDevice = d
            override fun identity() = DiskIdentity.of(d, "t", "Other Stick", "XX")
            override fun close() {}
        }
        val r = QuickFormat.run(other, snap, FsType.EXFAT, "")
        assertEquals(Outcome.FAILED, r.outcome)
        assertTrue(r.summary, r.summary.contains("different drive"))
        assertTrue(d.data.all { it.toInt() == 0 })
    }
}
