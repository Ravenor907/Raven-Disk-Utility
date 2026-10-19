package io.github.ravenor907.ravendisk.core

import io.github.ravenor907.ravendisk.core.fs.ExFat
import io.github.ravenor907.ravendisk.core.fs.Fat32
import io.github.ravenor907.ravendisk.core.fs.FsOp
import io.github.ravenor907.ravendisk.core.fs.FsSupport
import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.fs.Labels
import io.github.ravenor907.ravendisk.core.fs.Probe
import io.github.ravenor907.ravendisk.core.fs.Support
import io.github.ravenor907.ravendisk.core.fs.Swap
import io.github.ravenor907.ravendisk.core.io.FileDisk
import io.github.ravenor907.ravendisk.core.io.MemoryDisk
import io.github.ravenor907.ravendisk.core.io.SparseDisk
import io.github.ravenor907.ravendisk.core.util.Guid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FsTest {
    @Test
    fun fat32FormatAndProbe() {
        val d = SparseDisk(blockCount = 4L * 1024 * 1024) // 2 GiB
        val l = Fat32.format(d, 2048, d.blockCount - 2048, "Raven", serial = 0x12345678)
        // Data region starts on a cluster boundary (the partition itself is 1 MiB aligned).
        assertEquals(0L, (2048 + l.dataStart) % l.sectorsPerCluster)
        val info = Probe.probe(d, 2048, d.blockCount - 2048)!!
        assertEquals(FsType.FAT32, info.type)
        assertEquals("RAVEN", info.label)
        assertEquals("1234-5678", info.uuid)
        assertEquals((d.blockCount - 2048) * 512, info.sizeBytes)
        assertEquals((l.clusters - 1) * l.clusterBytes, info.freeBytes)
        assertTrue(info.freeIsHint)
        assertTrue(l.clusters >= Fat32.MIN_CLUSTERS)
    }

    @Test
    fun fat32ClusterSizes() {
        assertEquals(4096, Fat32.layout(512, 4L * 1024 * 1024).clusterBytes) // 2 GiB
        assertEquals(32768, Fat32.layout(512, 128L * 1000 * 1000 * 1000 / 512).clusterBytes) // 128 GB
        // Smaller than the default allows: cluster size steps down to keep 65525 clusters.
        val small = Fat32.layout(512, 80_000)
        assertTrue(small.clusters >= Fat32.MIN_CLUSTERS)
        try {
            Fat32.layout(512, 40_000)
            fail("20 MB is too small for FAT32")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun exfatFormatAndProbeCountsFreeSpace() {
        val d = SparseDisk(blockCount = 8L * 1024 * 1024) // 4 GiB
        val l = ExFat.format(d, 2048, d.blockCount - 2048, "Games", serial = 0x0BADF00D)
        val info = Probe.probe(d, 2048, d.blockCount - 2048)!!
        assertEquals(FsType.EXFAT, info.type)
        assertEquals("Games", info.label)
        assertEquals("0BAD-F00D", info.uuid)
        assertEquals((l.clusterCount - l.usedClusters) * l.clusterBytes, info.freeBytes)
        assertEquals(32768, info.clusterBytes)
    }

    @Test
    fun exfatOnFourKnSectors() {
        val d = SparseDisk(blockSize = 4096, blockCount = 262144) // 1 GiB
        ExFat.format(d, 256, d.blockCount - 256, "K4")
        val info = Probe.probe(d, 256, d.blockCount - 256)!!
        assertEquals(FsType.EXFAT, info.type)
        assertEquals("K4", info.label)
    }

    @Test
    fun swapFormatAndProbe() {
        val d = MemoryDisk(blockCount = 65536)
        val u = Guid.parse("01234567-89ab-cdef-0123-456789abcdef")
        Swap.format(d, 2048, 8192, "myswap", u)
        val info = Probe.probe(d, 2048, 8192)!!
        assertEquals(FsType.SWAP, info.type)
        assertEquals("myswap", info.label)
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", info.uuid)
        assertEquals(8192L * 512, info.sizeBytes)
    }

    @Test
    fun relabelFatExfatSwap() {
        val d = SparseDisk(blockCount = 4L * 1024 * 1024)
        Fat32.format(d, 2048, 1_000_000, "")
        assertNull(Probe.probe(d, 2048, 1_000_000)!!.label)
        Labels.set(d, 2048, 1_000_000, FsType.FAT32, "new one")
        assertEquals("NEW ONE", Probe.probe(d, 2048, 1_000_000)!!.label)
        Labels.set(d, 2048, 1_000_000, FsType.FAT32, "")
        assertNull(Probe.probe(d, 2048, 1_000_000)!!.label)

        ExFat.format(d, 1_002_048, 2_000_000, "A")
        Labels.set(d, 1_002_048, 2_000_000, FsType.EXFAT, "Längre namn")
        assertEquals("Längre namn", Probe.probe(d, 1_002_048, 2_000_000)!!.label)

        Swap.format(d, 3_100_000, 8192, "s1")
        Labels.set(d, 3_100_000, 8192, FsType.SWAP, "s2")
        assertEquals("s2", Probe.probe(d, 3_100_000, 8192)!!.label)
    }

    @Test
    fun labelRules() {
        assertNotNull(FsSupport.labelProblem(FsType.FAT32, "TWELVE CHARS"))
        assertNotNull(FsSupport.labelProblem(FsType.FAT32, "A/B"))
        assertNotNull(FsSupport.labelProblem(FsType.FAT32, "ÅÄÖ"))
        assertNull(FsSupport.labelProblem(FsType.FAT32, "BACKUP 01"))
        assertNotNull(FsSupport.labelProblem(FsType.EXFAT, "a:b"))
        assertNull(FsSupport.labelProblem(FsType.EXFAT, "Mixed Case"))
        assertNotNull(FsSupport.labelProblem(FsType.EXT4, "ééééééééé")) // 18 bytes in UTF-8
    }

    @Test
    fun supportMatrixNeverClaimsWhatIsNotBuilt() {
        // Native create must match what the executor implements.
        for (fs in FsType.entries) {
            val native = FsSupport.of(fs, FsOp.CREATE) == Support.Native
            assertEquals("create ${fs.label}", fs in setOf(FsType.FAT32, FsType.EXFAT, FsType.SWAP), native)
            // No checker or repairer is built in yet.
            assertTrue(FsSupport.of(fs, FsOp.CHECK) != Support.Native)
            assertTrue(FsSupport.of(fs, FsOp.RESIZE) != Support.Native)
        }
        assertEquals(Support.Native, FsSupport.of(FsType.EXT4, FsOp.LABEL))
        assertTrue(FsSupport.of(FsType.NTFS, FsOp.LABEL) is Support.No)
    }

    @Test
    fun probeIgnoresGarbage() {
        val d = MemoryDisk(blockCount = 4096)
        val r = java.util.Random(1)
        val junk = ByteArray(4096 * 512).also { r.nextBytes(it) }
        d.write(0, 4096, junk)
        assertNull(Probe.probe(d, 0, 4096))
        assertNull(Probe.probe(d, 0, 4)) // too small to hold anything
    }

    /** ext4 made by the real mke2fs, relabeled by the app, then checked by e2fsck (metadata checksums). */
    @Test
    fun relabelExt4MadeByMke2fs() {
        val mkfs = listOf("/sbin/mkfs.ext4", "/usr/sbin/mkfs.ext4").firstOrNull { File(it).canExecute() }
        val fsck = listOf("/sbin/e2fsck", "/usr/sbin/e2fsck").firstOrNull { File(it).canExecute() }
        assumeTrue(mkfs != null && fsck != null)
        val img = File.createTempFile("raven-ext4", ".img")
        try {
            img.delete()
            run(mkfs!!, "-q", "-F", "-L", "before", "-O", "metadata_csum", img.path, "64M")
            FileDisk.open(img).use { d ->
                val before = Probe.probe(d, 0, d.blockCount)!!
                assertEquals(FsType.EXT4, before.type)
                assertEquals("before", before.label)
                Labels.set(d, 0, d.blockCount, FsType.EXT4, "after ✓")
                assertEquals("after ✓", Probe.probe(d, 0, d.blockCount)!!.label)
            }
            run(fsck!!, "-fn", img.path)
        } finally {
            img.delete()
        }
    }

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw AssertionError("${cmd.joinToString(" ")} failed:\n$out")
    }
}
