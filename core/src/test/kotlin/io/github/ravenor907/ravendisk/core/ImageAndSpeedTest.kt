package io.github.ravenor907.ravendisk.core

import io.github.ravenor907.ravendisk.core.bench.DriveTest
import io.github.ravenor907.ravendisk.core.image.CorruptImageException
import io.github.ravenor907.ravendisk.core.image.ImageKind
import io.github.ravenor907.ravendisk.core.image.ImageWriter
import io.github.ravenor907.ravendisk.core.image.WriteException
import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.io.MemoryDisk
import io.github.ravenor907.ravendisk.core.job.CancelledException
import io.github.ravenor907.ravendisk.core.job.SpeedMeter
import io.github.ravenor907.ravendisk.core.job.SpeedPhase
import io.github.ravenor907.ravendisk.core.job.SpeedSample
import io.github.ravenor907.ravendisk.core.job.Stage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** Counts reads, to prove a skipped check really reads nothing. */
private class CountingDisk(val inner: BlockDevice) : BlockDevice by inner {
    var reads = 0
    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        reads++
        inner.read(lba, blocks, buf, offset)
    }
}

/** Claims [blockCount] blocks but only stores [store]'s; addresses wrap around like cheap counterfeits. */
private class WrapAroundDisk(val store: MemoryDisk, override val blockCount: Long) : BlockDevice {
    override val blockSize = store.blockSize
    private fun map(lba: Long) = lba % store.blockCount
    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        for (i in 0 until blocks) store.read(map(lba + i), 1, buf, offset + i * blockSize)
    }
    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        for (i in 0 until blocks) store.write(map(lba + i), 1, buf, offset + i * blockSize)
    }
    override fun sync() = store.sync()
}

/** Flips one bit in everything read at or after [from] bytes: a failing stick. */
private class CorruptingDisk(val inner: MemoryDisk, val from: Long) : BlockDevice by inner {
    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        inner.read(lba, blocks, buf, offset)
        val start = lba * blockSize
        val end = start + blocks.toLong() * blockSize
        if (end > from) {
            val i = offset + maxOf(0L, from - start).toInt()
            buf[i] = (buf[i].toInt() xor 1).toByte()
        }
    }
}

class SpeedMeterTest {
    @Test
    fun samplesAreWindowedAndShareOneClock() {
        var now = 0L
        val got = ArrayList<SpeedSample>()
        val m = SpeedMeter({ got.add(it) }, windowMs = 250, clock = { now })
        // 10 MB every 100 ms = 100 MB/s, for a second.
        repeat(10) {
            now += 100_000_000
            m.add(SpeedPhase.WRITE, 10_000_000)
        }
        // Then reading at 200 MB/s.
        repeat(5) {
            now += 100_000_000
            m.add(SpeedPhase.READ, 20_000_000)
        }
        m.flush()
        val w = got.filter { it.phase == SpeedPhase.WRITE }
        val r = got.filter { it.phase == SpeedPhase.READ }
        assertTrue(w.size >= 3)
        w.forEach { assertEquals(100.0, it.mbps, 0.01) }
        assertTrue(r.isNotEmpty())
        r.forEach { assertEquals(200.0, it.mbps, 0.01) }
        // Time only moves forward across phases.
        assertTrue(got.zipWithNext().all { (a, b) -> b.seconds >= a.seconds })
        assertEquals(200.0, m.peak, 0.01)
        assertEquals(1.5, got.last().seconds, 0.001)
    }
}

class DriveTestTest {
    private fun run(dev: BlockDevice, samples: MutableList<SpeedSample>? = null) = DriveTest.run(
        dev, { _, _, _ -> }, {}, { false }, minBytes = 8L shl 20, maxBytes = 16L shl 20, spots = 16, seed = 42,
        sample = { samples?.add(it) },
    )

    @Test
    fun realDrivePassesAndDrawsALiveGraph() {
        val d = MemoryDisk(blockCount = 64L * 2048)
        val live = ArrayList<SpeedSample>()
        val r = run(d, live)
        assertFalse(r.fake)
        assertFalse(r.failing)
        assertEquals(16, r.spots)
        assertTrue(r.testedBytes >= 8L shl 20)
        assertTrue(r.writeMBs > 0 && r.readMBs > 0)
        assertTrue(r.lines().joinToString("\n"), r.lines().any { it.startsWith("Capacity: all 16 markers") })
        // The graph got points as it ran, from every phase, and the report keeps them.
        assertTrue(live.isNotEmpty())
        assertEquals(live, r.samples)
        assertTrue(live.all { it.mbps > 0 })
        assertTrue(SpeedPhase.WRITE in live.map { it.phase })
        assertTrue(SpeedPhase.READ in live.map { it.phase })
        assertTrue(r.writePeak >= r.writeLow && r.writeLow > 0)
    }

    @Test
    fun wrapAroundFakeIsCaught() {
        val r = run(WrapAroundDisk(MemoryDisk(blockCount = 24L * 2048), 64L * 2048))
        assertTrue(r.fake || r.failing)
        assertTrue(r.lines().joinToString("\n"), r.lines().any { "Don't trust it" in it })
        assertFalse(r.lines().any { it.startsWith("Capacity: all") })
    }

    @Test
    fun fakeThatDropsWritesPastItsSizeIsCaught() {
        val store = MemoryDisk(blockCount = 24L * 2048)
        val dropping = object : BlockDevice {
            override val blockSize = 512
            override val blockCount = 64L * 2048
            override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
                for (i in 0 until blocks) {
                    if (lba + i < store.blockCount) store.read(lba + i, 1, buf, offset + i * 512)
                    else java.util.Arrays.fill(buf, offset + i * 512, offset + (i + 1) * 512, 0)
                }
            }
            override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
                for (i in 0 until blocks) if (lba + i < store.blockCount) store.write(lba + i, 1, buf, offset + i * 512)
            }
            override fun sync() = store.sync()
        }
        val r = run(dropping)
        assertTrue(r.fake)
        assertNotNull(r.firstBadSpot)
        assertTrue(r.firstBadSpot!! >= 24L shl 20)
        assertTrue(r.lines().any { it.startsWith("FAKE CAPACITY") })
    }

    @Test
    fun corruptReadsAreReportedAsFailing() {
        val r = run(CorruptingDisk(MemoryDisk(blockCount = 64L * 2048), 3L shl 20))
        assertTrue(r.failing)
    }

    @Test
    fun throughAUsbDrive() {
        val f = FakeUsbDrive(blocks = 64L * 2048, pipeline = true)
        val r = run(io.github.ravenor907.ravendisk.core.io.ScsiDisk(io.github.ravenor907.ravendisk.core.io.BulkOnly(f)).init())
        assertFalse(r.fake || r.failing)
    }

    @Test
    fun stopsWhenAsked() {
        var calls = 0
        try {
            DriveTest.run(MemoryDisk(blockCount = 64L * 2048), { _, _, _ -> }, {}, { ++calls > 2 }, minBytes = 8L shl 20, maxBytes = 16L shl 20, spots = 16)
            fail("should stop")
        } catch (e: CancelledException) {
        }
    }

    @Test
    fun slowdownAfterTheCacheIsSpotted() {
        val r = DriveTest.Report(64_000_000_000, 1 shl 30, listOf(80.0, 79.0, 12.0, 4.0), 30.0, 90.0, 0, 128, 0, null)
        assertNotNull(r.slowdown)
        assertTrue(r.lines().any { "drops to 4 MB/s" in it || "drops to 8 MB/s" in it })
    }
}

class ImageWriterTest {
    private fun write(img: ByteArray, d: BlockDevice, decode: (InputStream) -> InputStream = { it }, raw: ByteArray = img, verify: Boolean = true, log: MutableList<String> = ArrayList(), samples: MutableList<SpeedSample> = ArrayList()) =
        ImageWriter.writeStream({ ByteArrayInputStream(raw) }, raw.size.toLong(), decode, d, null, { _, _, _ -> }, { log.add(it) }, { false }, verify, { samples.add(it) })

    @Test
    fun writesReadsBackAndGraphsIt() {
        val img = Random(1).nextBytes(6 * 1024 * 1024 + 123) // not a whole number of sectors
        val d = MemoryDisk(blockCount = 32L * 2048)
        val log = ArrayList<String>()
        val live = ArrayList<SpeedSample>()
        assertEquals(img.size.toLong(), write(img, d, log = log, samples = live))
        assertArrayEquals(img, d.data.copyOf(img.size))
        assertTrue(log.toString(), log.any { it.startsWith("Wrote 0.01 GB in") && "waiting on the drive" in it })
        assertTrue(log.any { it.startsWith("Every byte on the drive matches the image") })
        assertTrue(live.any { it.phase == SpeedPhase.WRITE } && live.any { it.phase == SpeedPhase.READ })
    }

    @Test
    fun clearsAStaleBackupTableAtTheEnd() {
        val d = MemoryDisk(blockCount = 32L * 2048)
        java.util.Arrays.fill(d.data, d.data.size - 512, d.data.size, 0x45)
        write(Random(2).nextBytes(1 shl 20), d)
        assertTrue(d.data.copyOfRange(d.data.size - 512, d.data.size).all { it.toInt() == 0 })
    }

    @Test
    fun skippingTheCheckReadsNothingBack() {
        val img = Random(3).nextBytes(5 * 1024 * 1024)
        val d = CountingDisk(MemoryDisk(blockCount = 32L * 2048))
        val log = ArrayList<String>()
        write(img, d, verify = false, log = log)
        assertEquals(0, d.reads)
        assertTrue(log.any { "Skipped reading the drive back" in it })
    }

    @Test
    fun tooSmallDriveIsRefused() {
        try {
            write(Random(4).nextBytes(3 shl 20), MemoryDisk(blockCount = 4096))
            fail("should refuse")
        } catch (e: WriteException) {
            assertTrue(e.message!!.contains("too small"))
        }
    }

    @Test
    fun aFailingDriveIsCaughtOnReadBack() {
        try {
            write(Random(5).nextBytes(4 shl 20), CorruptingDisk(MemoryDisk(blockCount = 32L * 2048), 1L shl 20))
            fail("should notice")
        } catch (e: WriteException) {
            assertTrue(e.message!!.contains("didn't store the image correctly"))
        }
    }

    @Test
    fun gzipImagesUnpackWhileWriting() {
        val img = Random(6).nextBytes(3 shl 20)
        val packed = java.io.ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(img) } }.toByteArray()
        assertEquals(ImageKind.GZIP, ImageKind.sniff(packed.copyOf(minOf(packed.size, ImageKind.SNIFF_BYTES))))
        val d = MemoryDisk(blockCount = 16L * 2048)
        write(img, d, ImageKind.decoder(ImageKind.GZIP), packed)
        assertArrayEquals(img, d.data.copyOf(img.size))
    }

    @Test
    fun aDamagedDownloadIsBlamedOnTheFileNotTheDrive() {
        val img = Random(7).nextBytes(2 shl 20)
        val packed = java.io.ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(img) } }.toByteArray()
        val cut = packed.copyOf(packed.size / 2)
        try {
            write(img, MemoryDisk(blockCount = 16L * 2048), ImageKind.decoder(ImageKind.GZIP), cut)
            fail("should fail")
        } catch (e: CorruptImageException) {
        }
    }

    @Test
    fun compareFindsTheFirstDifference() {
        val img = Random(8).nextBytes(5 * 1024 * 1024)
        val d = MemoryDisk(blockCount = 16L * 2048)
        System.arraycopy(img, 0, d.data, 0, img.size)
        assertNull(ImageWriter.compare({ ByteArrayInputStream(img) }, img.size.toLong(), { it }, d, { _, _, _ -> }, {}, { false }))
        d.data[4_321_987] = (d.data[4_321_987].toInt() xor 1).toByte()
        assertEquals(4_321_987L, ImageWriter.compare({ ByteArrayInputStream(img) }, img.size.toLong(), { it }, d, { _, _, _ -> }, {}, { false }))
    }

    @Test
    fun progressFollowsTheFile() {
        val img = Random(9).nextBytes(9 shl 20)
        val stages = ArrayList<Triple<Stage, Long, Long>>()
        ImageWriter.writeStream({ ByteArrayInputStream(img) }, img.size.toLong(), { it }, MemoryDisk(blockCount = 32L * 2048), null, { s, a, b -> stages.add(Triple(s, a, b)) }, {}, { false })
        assertEquals(Triple(Stage.WRITE, img.size.toLong(), img.size.toLong()), stages.last { it.first == Stage.WRITE })
        assertEquals(img.size.toLong(), stages.last().second)
    }
}

class ImageKindTest {
    @Test
    fun sniffsWhatAFileReallyIs() {
        fun at(size: Int, vararg marks: Pair<Int, ByteArray>) = ByteArray(size).also { b -> marks.forEach { (o, m) -> System.arraycopy(m, 0, b, o, m.size) } }
        val cd = "CD001".toByteArray()
        val boot = byteArrayOf(0x55, 0xAA.toByte())
        assertEquals(ImageKind.ISO_HYBRID, ImageKind.sniff(at(ImageKind.SNIFF_BYTES, 32_769 to cd, 510 to boot)))
        assertEquals(ImageKind.ISO_DVD_ONLY, ImageKind.sniff(at(ImageKind.SNIFF_BYTES, 32_769 to cd)))
        assertEquals(ImageKind.DISK, ImageKind.sniff(at(1024, 512 to "EFI PART".toByteArray())))
        assertEquals(ImageKind.DISK, ImageKind.sniff(at(1024, 510 to boot)))
        assertEquals(ImageKind.BZIP2, ImageKind.sniff("BZh91AY".toByteArray()))
        assertEquals(ImageKind.XZ, ImageKind.sniff(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0)))
        assertEquals(ImageKind.ZIP, ImageKind.sniff(byteArrayOf(0x50, 0x4B, 3, 4)))
        assertEquals(ImageKind.ZSTD, ImageKind.sniff(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())))
        assertFalse(ImageKind.ZSTD.writable)
        assertEquals(ImageKind.UNKNOWN, ImageKind.sniff(ByteArray(100)))
        assertNotNull(ImageKind.UNKNOWN.warning)
    }
}
