package io.github.ravenor907.ravendisk.core

import io.github.ravenor907.ravendisk.core.io.BulkOnly
import io.github.ravenor907.ravendisk.core.io.UsbTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A USB mass-storage device seen from the wire: parses Bulk-Only CBWs, runs SCSI commands
 * against in-memory disks (one per LUN) and answers with CSWs. Optionally a SATA bridge that
 * answers ATA PASS-THROUGH with canned IDENTIFY/SMART data.
 */
class FakeUsbDrive(
    val blockSize: Int = 512,
    blocks: Long = 8192,
    /** Report this many blocks via READ CAPACITY instead (to fake huge drives). */
    private val reportedBlocks: Long = blocks,
    var writeProtected: Boolean = false,
    var notReadyCount: Int = 0,
    /** LUNs beyond 0 with no medium (an empty card reader slot). */
    private val emptyLuns: Set<Int> = emptySet(),
    private val luns: Int = 1,
    val physicalExponent: Int = 0,
    val thinProvisioning: Boolean = false,
    val serial: String? = "FAKE0001",
    val rotationRate: Int? = null,
    /** IDENTIFY and SMART data the fake bridge returns, or null = not a SATA bridge. */
    var ataIdentify: ByteArray? = null,
    var ataSmart: ByteArray? = null,
    var ataThresholds: ByteArray? = null,
    var smartPassed: Boolean = true,
    override val maxTransfer: Int = 16384,
    /** The next this-many data-phase transfers fail once each, like a flaky cable. */
    var flakyTransfers: Int = 0,
) : UsbTransport {
    val disks = Array(luns) { ByteArray((blocks * blockSize).toInt()) }
    val disk get() = disks[0]
    val commands = ArrayList<Int>()
    val unmapped = ArrayList<Pair<Long, Long>>()
    var resets = 0

    private var pending: Cbw? = null
    private var dataPos = 0
    private var outBuf = ByteArray(0)
    private var inBuf = ByteArray(0)
    private var status = 0
    private var sense = ByteArray(18)
    private var cswReady = false

    private class Cbw(val tag: Int, val length: Int, val dirIn: Boolean, val lun: Int, val cdb: ByteArray)

    override fun maxLun() = luns - 1

    override fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val p = pending
        if (p == null) {
            if (length != 31) return -1
            val b = ByteBuffer.wrap(data, offset, 31).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(offset) != BulkOnly.CBW_SIGNATURE) return -1
            val tag = b.getInt(offset + 4)
            val len = b.getInt(offset + 8)
            val dirIn = data[offset + 12].toInt() and 0x80 != 0
            val lun = data[offset + 13].toInt()
            val cbLen = data[offset + 14].toInt()
            val cbw = Cbw(tag, len, dirIn, lun, data.copyOfRange(offset + 15, offset + 15 + cbLen))
            commands.add(cbw.cdb[0].toInt() and 0xFF)
            pending = cbw
            dataPos = 0
            cswReady = false
            if (dirIn) {
                execute(cbw, null)
                if (len == 0) cswReady = true
            } else {
                outBuf = ByteArray(len)
                if (len == 0) {
                    execute(cbw, outBuf)
                    cswReady = true
                }
            }
            return 31
        }
        if (p.dirIn) return -1
        if (flakyTransfers > 0) {
            flakyTransfers--
            return -1
        }
        System.arraycopy(data, offset, outBuf, dataPos, length)
        dataPos += length
        if (dataPos >= p.length) {
            execute(p, outBuf)
            cswReady = true
        }
        return length
    }

    override fun bulkIn(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val p = pending ?: return -1
        if (!cswReady) {
            val n = minOf(length, inBuf.size - dataPos)
            System.arraycopy(inBuf, dataPos, data, offset, n)
            dataPos += n
            if (dataPos >= p.length || dataPos >= inBuf.size) cswReady = true
            return n
        }
        if (length < 13) return -1
        val csw = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(BulkOnly.CSW_SIGNATURE).putInt(p.tag).putInt(0).put(status.toByte()).array()
        System.arraycopy(csw, 0, data, offset, 13)
        pending = null
        return 13
    }

    override fun resetRecovery() {
        resets++
        pending = null
    }

    private fun fail(key: Int, asc: Int, ascq: Int = 0) {
        status = 1
        sense = ByteArray(18).also {
            it[0] = 0x70
            it[2] = key.toByte()
            it[12] = asc.toByte()
            it[13] = ascq.toByte()
        }
    }

    private fun execute(c: Cbw, out: ByteArray?) {
        status = 0
        inBuf = ByteArray(0)
        val cdb = ByteBuffer.wrap(c.cdb)
        val op = c.cdb[0].toInt() and 0xFF
        val noMedium = c.lun in emptyLuns
        if (noMedium && op in setOf(0x00, 0x25, 0x9E, 0x28, 0x2A, 0x88, 0x8A)) {
            fail(0x2, 0x3A)
            return
        }
        val d = disks[c.lun]
        when (op) {
            0x00 -> if (notReadyCount > 0) {
                notReadyCount--
                fail(0x2, 0x04)
            }
            0x03 -> {
                inBuf = sense.copyOf(c.length)
                sense = ByteArray(18)
            }
            0x12 -> inBuf = inquiry(c.cdb).copyOf(c.length)
            0x1A -> inBuf = ByteArray(c.length).also { it[0] = 3; if (writeProtected) it[2] = 0x80.toByte() }
            0x25 -> inBuf = ByteBuffer.allocate(8)
                .putInt(if (reportedBlocks - 1 > 0xFFFFFFFFL) -1 else (reportedBlocks - 1).toInt())
                .putInt(blockSize).array()
            0x9E -> inBuf = ByteBuffer.allocate(32).putLong(reportedBlocks - 1).putInt(blockSize)
                .put(0).put(physicalExponent.toByte()).put(if (thinProvisioning) 0x80.toByte() else 0).array()
            0x28, 0x88 -> {
                val (lba, n) = if (op == 0x28) (cdb.getInt(2).toLong() and 0xFFFFFFFFL) to (cdb.getShort(7).toInt() and 0xFFFF) else cdb.getLong(2) to cdb.getInt(10)
                val from = (lba * blockSize).toInt()
                inBuf = d.copyOfRange(from, from + n * blockSize)
            }
            0x2A, 0x8A -> {
                if (writeProtected) {
                    fail(0x7, 0x27)
                    return
                }
                val lba = if (op == 0x2A) cdb.getInt(2).toLong() and 0xFFFFFFFFL else cdb.getLong(2)
                System.arraycopy(out!!, 0, d, (lba * blockSize).toInt(), out.size)
            }
            0x35 -> {}
            0x42 -> {
                if (!thinProvisioning) {
                    fail(0x5, 0x20)
                    return
                }
                val p = ByteBuffer.wrap(out!!)
                val lba = p.getLong(8)
                val n = p.getInt(16).toLong() and 0xFFFFFFFFL
                unmapped.add(lba to n)
                java.util.Arrays.fill(d, (lba * blockSize).toInt(), ((lba + n) * blockSize).toInt(), 0)
            }
            0x85 -> ata(c)
            else -> fail(0x5, 0x20)
        }
    }

    private fun inquiry(cdb: ByteArray): ByteArray {
        val evpd = cdb[1].toInt() and 1 != 0
        if (!evpd) {
            return ByteArray(36).also {
                it[1] = if (rotationRate == null) 0x80.toByte() else 0
                "FakeCo  ".toByteArray().copyInto(it, 8)
                "Test Drive 3000 ".toByteArray().copyInto(it, 16)
                "1.00".toByteArray().copyInto(it, 32)
            }
        }
        val page = cdb[2].toInt() and 0xFF
        val supported = buildList {
            add(0x00)
            if (serial != null) add(0x80)
            if (thinProvisioning) add(0xB0)
            if (rotationRate != null) add(0xB1)
        }
        return when (page) {
            0x00 -> ByteArray(4 + supported.size).also { b ->
                b[3] = supported.size.toByte()
                supported.forEachIndexed { i, p -> b[4 + i] = p.toByte() }
            }
            0x80 -> {
                val s = serial!!.toByteArray()
                ByteArray(4 + s.size).also { b -> b[1] = 0x80.toByte(); b[3] = s.size.toByte(); s.copyInto(b, 4) }
            }
            0xB0 -> ByteArray(64).also { b ->
                b[1] = 0xB0.toByte(); b[3] = 60
                ByteBuffer.wrap(b).putInt(20, 65536).putInt(24, 1)
            }
            0xB1 -> ByteArray(64).also { b ->
                b[1] = 0xB1.toByte(); b[3] = 60
                b[4] = (rotationRate!! shr 8).toByte(); b[5] = rotationRate.toByte()
            }
            else -> {
                fail(0x5, 0x24)
                ByteArray(0)
            }
        }
    }

    private fun ata(c: Cbw) {
        val id = ataIdentify ?: run {
            fail(0x5, 0x20)
            return
        }
        val cmd = c.cdb[14].toInt() and 0xFF
        val feature = c.cdb[4].toInt() and 0xFF
        val ck = c.cdb[2].toInt() and 0x20 != 0
        when {
            cmd == 0xEC -> inBuf = id.copyOf(512)
            cmd == 0xB0 && feature == 0xD0 -> inBuf = (ataSmart ?: ByteArray(512)).copyOf(512)
            cmd == 0xB0 && feature == 0xD1 -> inBuf = (ataThresholds ?: ByteArray(512)).copyOf(512)
            cmd == 0xB0 && feature == 0xDA && ck -> {
                // Descriptor-format sense with the ATA status return descriptor.
                status = 1
                sense = ByteArray(32).also {
                    it[0] = 0x72; it[1] = 0x01; it[2] = 0x00; it[3] = 0x1D; it[7] = 14
                    it[8] = 0x09; it[9] = 0x0C
                    it[8 + 9] = if (smartPassed) 0x4F else 0xF4.toByte()
                    it[8 + 11] = if (smartPassed) 0xC2.toByte() else 0x2C
                    it[8 + 13] = 0x50
                }
            }
            else -> fail(0x5, 0x24)
        }
    }

    companion object {
        /** IDENTIFY DEVICE data with SMART supported and enabled. */
        fun identify(model: String, serial: String, firmware: String, rotation: Int): ByteArray {
            val b = ByteArray(512)
            fun str(word: Int, words: Int, s: String) {
                val p = s.padEnd(words * 2)
                for (i in 0 until words) {
                    b[(word + i) * 2] = p[i * 2 + 1].code.toByte()
                    b[(word + i) * 2 + 1] = p[i * 2].code.toByte()
                }
            }
            str(10, 10, serial)
            str(23, 4, firmware)
            str(27, 20, model)
            b[82 * 2] = 1
            b[85 * 2] = 1
            b[217 * 2] = (rotation and 0xFF).toByte()
            b[217 * 2 + 1] = (rotation shr 8).toByte()
            return b
        }

        /** SMART data with (id, value, worst, raw) attributes. */
        fun smart(vararg attrs: List<Long>): ByteArray {
            val b = ByteArray(512)
            attrs.forEachIndexed { i, a ->
                val o = 2 + i * 12
                b[o] = a[0].toByte()
                b[o + 3] = a[1].toByte()
                b[o + 4] = a[2].toByte()
                ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(o + 5, a[3].toInt())
            }
            b[363] = 0
            return b
        }
    }
}
