package io.github.ravenor907.ravendisk.core

import io.github.ravenor907.ravendisk.core.demo.Demo
import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.inspect.Inspector
import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.io.SparseDisk
import io.github.ravenor907.ravendisk.core.plan.DiskIdentity
import io.github.ravenor907.ravendisk.core.plan.DiskSession
import io.github.ravenor907.ravendisk.core.plan.DiskSnapshot
import io.github.ravenor907.ravendisk.core.plan.EraseMethod
import io.github.ravenor907.ravendisk.core.plan.Executor
import io.github.ravenor907.ravendisk.core.plan.Op
import io.github.ravenor907.ravendisk.core.plan.Outcome
import io.github.ravenor907.ravendisk.core.plan.Planner
import io.github.ravenor907.ravendisk.core.plan.RunListener
import io.github.ravenor907.ravendisk.core.plan.TextJournal
import io.github.ravenor907.ravendisk.core.table.PartitionTypes
import io.github.ravenor907.ravendisk.core.table.TableKind
import io.github.ravenor907.ravendisk.core.table.TableRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class ExecutorTest {
    private val gib = 1024L * 1024 * 1024 / 512

    private class Session(override val dev: BlockDevice, var serial: String = "SN1") : DiskSession {
        var discarded = ArrayList<Pair<Long, Long>>()
        override fun identity() = DiskIdentity.of(dev, "test", "Test Drive", serial)
        override fun discard(lba: Long, blocks: Long) {
            discarded.add(lba to blocks)
            dev.write(lba, minOf(blocks, 8).toInt(), ByteArray(minOf(blocks, 8).toInt() * dev.blockSize))
        }
        override fun close() {}
    }

    private fun planner(d: BlockDevice, discard: Boolean = false): Planner {
        val i = Inspector.inspect(d)
        return Planner(DiskSnapshot(DiskIdentity.of(d, "test", "Test Drive", "SN1"), 512, i.table, i.fs, discardSupported = discard))
    }

    @Test
    fun newGptWithThreeFormattedPartitions() {
        val d = SparseDisk(blockCount = 32 * gib)
        val p = planner(d)
        p.add(Op.NewTable(TableKind.GPT))
        p.add(Op.Create(2048, 2048 + gib / 2 - 1, FsType.FAT32, "EFI", gptType = PartitionTypes.EFI_SYSTEM, name = "EFI system"))
        p.add(Op.Create(2048 + gib / 2, 2048 + gib / 2 + 2 * gib - 1, FsType.SWAP, "swap"))
        p.add(Op.Create(2048 + gib / 2 + 2 * gib, 32 * gib - 2048, FsType.EXFAT, "Games"))
        val log = ArrayList<String>()
        val r = Executor(Session(d), p, object : RunListener {
            override fun log(line: String) {
                log.add(line)
            }
        }).run()
        assertEquals(r.summary + "\n" + r.checks.joinToString("\n"), Outcome.SUCCESS, r.outcome)
        val after = Inspector.inspect(d)
        val t = (after.table as TableRead.Found).table
        assertEquals(3, t.partitions.size)
        assertEquals(PartitionTypes.EFI_SYSTEM, t.byNumber(1)!!.typeGuid)
        assertEquals("EFI system", t.byNumber(1)!!.name)
        assertEquals(FsType.FAT32, after.fs[1]!!.type)
        assertEquals(FsType.SWAP, after.fs[2]!!.type)
        assertEquals("Games", after.fs[3]!!.label)
        assertTrue(r.checks.all { it.startsWith("✓") })
        assertTrue(log.first().contains("same drive"))
    }

    @Test
    fun identityMismatchWritesNothing() {
        val d = SparseDisk(blockCount = 8 * gib)
        val p = planner(d)
        p.add(Op.NewTable(TableKind.GPT))
        val s = Session(d, serial = "OTHER")
        val stored = d.storedChunks
        val r = Executor(s, p).run()
        assertEquals(Outcome.FAILED, r.outcome)
        assertTrue(r.summary, r.summary.contains("Nothing was written"))
        assertEquals(stored, d.storedChunks)
    }

    @Test
    fun tableChangedSincePlanIsRefused() {
        val drive = Demo.flashDrive()
        val p = planner(drive.disk)
        p.add(Op.Delete(1))
        drive.disk.write(0, 1, ByteArray(512) { 1 }) // someone else changed sector 0
        val r = Executor(Session(drive.disk), p).run()
        assertEquals(Outcome.FAILED, r.outcome)
        assertTrue(r.summary.contains("changed since the plan"))
    }

    @Test
    fun unplugMidwayReportsWhatWasWritten() {
        val d = SparseDisk(blockCount = 8 * gib)
        val p = planner(d)
        p.add(Op.NewTable(TableKind.GPT))
        p.add(Op.Create(2048, 2048 + 2 * gib - 1, FsType.EXFAT, "A"))
        var writes = 0
        d.beforeWrite = { _, _ -> if (++writes > 5) throw IOException("Device disconnected") }
        val r = Executor(Session(d), p).run()
        assertEquals(Outcome.FAILED, r.outcome)
        assertTrue(r.summary, r.summary.contains("Device disconnected"))
        assertTrue(r.summary, r.summary.contains("can't be undone"))
        assertTrue(r.summary, r.summary.contains("partition table was not written"))
        assertTrue(r.steps.last().ok.not())
    }

    @Test
    fun cancelStopsBetweenSteps() {
        val d = SparseDisk(blockCount = 8 * gib)
        val p = planner(d)
        p.add(Op.NewTable(TableKind.GPT))
        p.add(Op.Create(2048, 2048 + gib - 1, FsType.EXFAT, "A"))
        var steps = 0
        val r = Executor(Session(d), p, object : RunListener {
            override fun step(index: Int, count: Int, title: String) {
                steps = index
            }
        }, cancelled = { steps >= 1 }).run()
        assertEquals(Outcome.CANCELLED, r.outcome)
    }

    @Test
    fun cancelBeforeAnythingSaysNothingWritten() {
        val d = SparseDisk(blockCount = 8 * gib)
        val p = planner(d)
        p.add(Op.NewTable(TableKind.GPT))
        val r = Executor(Session(d), p, cancelled = { true }).run()
        assertEquals(Outcome.CANCELLED, r.outcome)
        assertTrue(r.summary.contains("Nothing was written"))
    }

    @Test
    fun relabelAndFormatOnTheDemoSsd() {
        val drive = Demo.portableSsd()
        val p = planner(drive.disk)
        p.add(Op.SetLabel(1, "BOOT"))
        p.add(Op.Format(4, FsType.EXFAT, "Library"))
        p.add(Op.SetName(4, "library"))
        val r = Executor(Session(drive.disk), p).run()
        assertEquals(r.checks.toString(), Outcome.SUCCESS, r.outcome)
        val after = Inspector.inspect(drive.disk)
        assertEquals("BOOT", after.fs[1]!!.label)
        assertEquals("Library", after.fs[4]!!.label)
        assertEquals("library", (after.table as TableRead.Found).table.byNumber(4)!!.name)
        assertEquals("rootfs", after.fs[3]!!.label) // untouched
    }

    @Test
    fun zeroEraseOfAPartitionVerifiesBySampling() {
        val drive = Demo.flashDrive()
        val p = planner(drive.disk)
        // A smaller partition to keep the test fast: shrink by replacing the layout.
        p.add(Op.Delete(1))
        p.add(Op.Create(2048, 2048 + gib / 8 - 1, FsType.EXFAT, "X"))
        val r1 = Executor(Session(drive.disk), p).run()
        assertEquals(Outcome.SUCCESS, r1.outcome)
        val p2 = planner(drive.disk)
        p2.add(Op.Erase(1, EraseMethod.ZERO))
        val r2 = Executor(Session(drive.disk), p2).run()
        assertEquals(r2.checks.toString(), Outcome.SUCCESS, r2.outcome)
        assertNull(Inspector.inspect(drive.disk).fs[1])
    }

    @Test
    fun discardIsReportedAsNotVerifiable() {
        val drive = Demo.portableSsd()
        val p = planner(drive.disk, discard = true)
        p.add(Op.Erase(2, EraseMethod.DISCARD))
        val s = Session(drive.disk)
        val r = Executor(s, p).run()
        assertEquals(Outcome.SUCCESS, r.outcome)
        assertEquals(1, s.discarded.size)
        assertTrue(r.checks.any { it.contains("drive decides") })
    }

    @Test
    fun journalRecordsRunsAndSpotsInterruptions() {
        val f = File.createTempFile("raven-journal", ".log")
        f.delete()
        try {
            val j = TextJournal(f)
            val d = SparseDisk(blockCount = 8 * gib)
            val p = planner(d)
            p.add(Op.NewTable(TableKind.MBR))
            Executor(Session(d), p, journal = j).run()
            val runs = j.runs()
            assertEquals(1, runs.size)
            assertEquals(Outcome.SUCCESS, runs[0].outcome)
            assertTrue(runs[0].device.startsWith("Test Drive"))
            assertTrue(!f.readText().contains("SN1")) // no serials in the log
            assertNull(j.interrupted())
            // A run that never ended (app killed mid-write):
            j.begin("Test Drive, 8 GB", listOf("Delete partition 1"))
            j.step("Write the partition table")
            val i = j.interrupted()
            assertNotNull(i)
            assertEquals("Write the partition table", i!!.steps.last())
            j.acknowledgeInterrupted()
            assertNull(j.interrupted())
        } finally {
            f.delete()
        }
    }

    @Test
    fun demoDrivesInspectAsBuilt() {
        val flash = Inspector.inspect(Demo.flashDrive().disk)
        assertEquals(TableKind.MBR, (flash.table as TableRead.Found).table.kind)
        assertEquals("ULTRA FIT", flash.fs[1]!!.label)
        val ssd = Inspector.inspect(Demo.portableSsd().disk)
        assertEquals(listOf(FsType.FAT32, FsType.SWAP, FsType.EXT4, FsType.EXFAT), (1..4).map { ssd.fs[it]!!.type })
        assertEquals(TableRead.Blank, Inspector.inspect(Demo.sataEnclosure().disk).table)
    }
}
