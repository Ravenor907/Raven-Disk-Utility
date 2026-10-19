package io.github.ravenor907.ravendisk.core

import io.github.ravenor907.ravendisk.core.health.HealthStatus
import io.github.ravenor907.ravendisk.core.health.SatChannel
import io.github.ravenor907.ravendisk.core.health.Smart
import io.github.ravenor907.ravendisk.core.io.BulkOnly
import io.github.ravenor907.ravendisk.core.io.DeviceReadOnlyException
import io.github.ravenor907.ravendisk.core.io.NoMediumException
import io.github.ravenor907.ravendisk.core.io.ScsiDisk
import io.github.ravenor907.ravendisk.core.io.ScsiException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class UsbScsiTest {
    @Before
    fun fast() {
        ScsiDisk.SETTLE_MS = 1
    }

    private fun open(f: FakeUsbDrive, lun: Int = 0) = ScsiDisk(BulkOnly(f, lun)).init()

    @Test
    fun identityAndCapacity() {
        val f = FakeUsbDrive(blocks = 4096, serial = "ABC123", physicalExponent = 3)
        val d = open(f)
        assertEquals(512, d.blockSize)
        assertEquals(4096L, d.blockCount)
        assertEquals(4096, d.physicalBlockSize)
        assertEquals("FakeCo", d.identity.vendor)
        assertEquals("Test Drive 3000", d.identity.product)
        assertEquals("ABC123", d.identity.serial)
        assertTrue(d.identity.removableMedium)
        assertFalse(d.readOnly)
        assertFalse(d.discard.supported)
    }

    /** Seen on a real SSK USB 3.2 stick: short VPD answer, then bulk-in stalls before the status. */
    @Test
    fun clearsTheStallAfterAShortAnswer() {
        val f = FakeUsbDrive(blocks = 4096, serial = "SSK1", stallAfterShort = true)
        val d = open(f)
        assertEquals("SSK1", d.identity.serial)
        assertTrue(f.haltsCleared > 0)
        assertEquals(0, f.resets)
        d.write(0, 1, ByteArray(512) { 7 })
        assertEquals(7, d.readBlocks(0, 1)[0].toInt())
    }

    @Test
    fun readWriteRoundTripAcrossCommands() {
        val f = FakeUsbDrive(blocks = 8192)
        val d = open(f)
        val data = ByteArray(3000 * 512) { (it * 31 + 7).toByte() }
        d.write(100, 3000, data)
        val back = d.readBlocks(100, 3000)
        assertArrayEquals(data, back)
        assertArrayEquals(data.copyOfRange(0, 512), f.disk.copyOfRange(100 * 512, 101 * 512))
    }

    @Test
    fun writeProtectedDriveRefusesWrites() {
        val f = FakeUsbDrive(writeProtected = true)
        val d = open(f)
        assertTrue(d.readOnly)
        try {
            d.write(0, 1, ByteArray(512))
            fail("write should fail")
        } catch (e: DeviceReadOnlyException) {
        }
        assertFalse(f.commands.contains(0x2A))
    }

    @Test
    fun waitsForAFreshlyPluggedDrive() {
        val f = FakeUsbDrive(notReadyCount = 3)
        open(f)
        assertEquals(4, f.commands.count { it == 0x00 })
    }

    @Test
    fun emptyCardReaderSlotReportsNoMedium() {
        val f = FakeUsbDrive(luns = 2, emptyLuns = setOf(1))
        assertEquals(1, f.maxLun())
        open(f, 0)
        try {
            open(f, 1)
            fail("empty slot should throw")
        } catch (e: NoMediumException) {
        }
    }

    @Test
    fun recoversFromAFlakyTransfer() {
        val f = FakeUsbDrive(blocks = 4096)
        val d = open(f)
        f.flakyTransfers = 1
        val data = ByteArray(64 * 512) { it.toByte() }
        d.write(10, 64, data)
        assertArrayEquals(data, d.readBlocks(10, 64))
        assertTrue(f.resets >= 1)
    }

    @Test
    fun outOfRangeIsRefusedBeforeTheWire() {
        val f = FakeUsbDrive(blocks = 1024)
        val d = open(f)
        val before = f.commands.size
        try {
            d.read(1020, 8, ByteArray(8 * 512))
            fail("should refuse")
        } catch (e: java.io.IOException) {
        }
        assertEquals(before, f.commands.size)
    }

    @Test
    fun fourKnDrive() {
        val f = FakeUsbDrive(blockSize = 4096, blocks = 2048)
        val d = open(f)
        assertEquals(4096, d.blockSize)
        val data = ByteArray(4096 * 2) { 0x5A }
        d.write(5, 2, data)
        assertArrayEquals(data, d.readBlocks(5, 2))
    }

    @Test
    fun unmapWhenThinProvisioned() {
        val f = FakeUsbDrive(blocks = 4096, thinProvisioning = true)
        val d = open(f)
        assertTrue(d.discard.supported)
        d.write(0, 4, ByteArray(2048) { 1 })
        d.unmap(0, 4)
        assertEquals(listOf(0L to 4L), f.unmapped)
        assertTrue(d.readBlocks(0, 4).all { it.toInt() == 0 })
    }

    @Test
    fun unmapRefusedWithoutSupport() {
        val d = open(FakeUsbDrive())
        try {
            d.unmap(0, 1)
            fail("should refuse")
        } catch (e: ScsiException) {
        }
    }

    @Test
    fun smartThroughSatBridge() {
        val f = FakeUsbDrive(
            blocks = 4096, rotationRate = 7200,
            ataIdentify = FakeUsbDrive.identify("WDC WD20EZBX", "WD-123", "01.01A01", 7200),
            ataSmart = FakeUsbDrive.smart(listOf(5L, 200, 200, 0), listOf(9L, 97, 97, 2431), listOf(194L, 116, 104, 34), listOf(197L, 200, 200, 2)),
        )
        val d = open(f)
        assertFalse(d.identity.removableMedium)
        assertEquals(7200, d.identity.rotationRate)
        val ident = Smart.parseIdentify(SatChannel(d).identify())
        assertEquals("WDC WD20EZBX", ident.model)
        assertEquals("WD-123", ident.serial)
        val r = Smart.report(SatChannel(d), "test")
        assertEquals(34, r.temperatureC)
        assertEquals(2431L, r.powerOnHours)
        assertEquals(HealthStatus.WARNING, r.status) // 2 pending sectors
        assertTrue(r.items.any { it.name == "Pending sectors" && it.warn })
    }

    @Test
    fun smartFailingVerdictFromRegisters() {
        val f = FakeUsbDrive(
            ataIdentify = FakeUsbDrive.identify("X", "Y", "Z", 1),
            ataSmart = FakeUsbDrive.smart(listOf(5L, 100, 100, 0)),
            smartPassed = false,
        )
        val r = Smart.report(SatChannel(open(f)), "test")
        assertEquals(HealthStatus.FAILING, r.status)
    }

    @Test
    fun flashDriveWithoutSatIsReportedUnavailableNotFaked() {
        val r = Smart.report(SatChannel(open(FakeUsbDrive())), "test")
        assertEquals(HealthStatus.UNKNOWN, r.status)
        assertNull(r.temperatureC)
        assertTrue(r.items.isEmpty())
    }
}
