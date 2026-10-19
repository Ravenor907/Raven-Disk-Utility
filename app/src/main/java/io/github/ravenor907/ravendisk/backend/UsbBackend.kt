package io.github.ravenor907.ravendisk.backend

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.ravenor907.ravendisk.core.io.BulkOnly
import io.github.ravenor907.ravendisk.core.io.NoMediumException
import io.github.ravenor907.ravendisk.core.io.ScsiDisk
import io.github.ravenor907.ravendisk.core.io.UsbTransport
import java.io.Closeable
import java.io.IOException

/** Android's USB host API under the app's own Bulk-Only/SCSI code. */
class AndroidUsbTransport(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : UsbTransport, Closeable {
    // Before Android 9 a single bulk transfer was capped at 16 KiB.
    override val maxTransfer: Int = if (Build.VERSION.SDK_INT >= 28) 64 * 1024 else 16 * 1024

    override fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        conn.bulkTransfer(outEp, data, offset, length, timeoutMs)

    override fun bulkIn(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        conn.bulkTransfer(inEp, data, offset, length, timeoutMs)

    override fun resetRecovery() {
        // Bulk-Only Mass Storage Reset, then CLEAR_FEATURE(ENDPOINT_HALT) on both pipes.
        conn.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, 5000)
        conn.controlTransfer(0x02, 0x01, 0, inEp.address, null, 0, 5000)
        conn.controlTransfer(0x02, 0x01, 0, outEp.address, null, 0, 5000)
    }

    /** USB details for error reports: IDs, interface and packet size. */
    fun describe(): String = "interface ${intf.id} alt ${intf.alternateSetting}, ${outEp.maxPacketSize}-byte packets"

    override fun maxLun(): Int {
        val b = ByteArray(1)
        val r = conn.controlTransfer(0xA1, 0xFE, 0, intf.id, b, 1, 2000)
        return if (r == 1) (b[0].toInt() and 0x0F) else 0
    }

    override fun close() {
        try {
            conn.releaseInterface(intf)
        } finally {
            conn.close()
        }
    }
}

/** One LUN of an open USB device: a disk, an empty slot, or an error. */
sealed class Lun {
    abstract val index: Int

    data class Ready(override val index: Int, val disk: ScsiDisk) : Lun()
    data class Empty(override val index: Int) : Lun()
    data class Failed(override val index: Int, val message: String) : Lun()
}

/** A claimed USB mass storage device. Holding it open keeps Android's driver off the drive. */
class UsbConnection(val device: UsbDevice, private val transport: AndroidUsbTransport, val luns: List<Lun>, val link: String) : Closeable {
    override fun close() = transport.close()
}

class UsbBackend(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager?

    val available: Boolean get() = manager != null

    /** Every attached device with a mass storage interface (Bulk-Only or UAS). No I/O. */
    fun devices(): List<UsbDevice> = manager?.deviceList?.values?.filter { storageInterfaces(it).isNotEmpty() }?.sortedBy { it.deviceName } ?: emptyList()

    fun device(name: String): UsbDevice? = manager?.deviceList?.get(name)

    fun hasPermission(d: UsbDevice) = manager?.hasPermission(d) == true

    fun requestPermission(d: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        manager?.requestPermission(d, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    /** Calls [onChange] on permission answers and on attach/detach. */
    fun registerReceiver(onChange: () -> Unit): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onChange()
        }
        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        return r
    }

    fun open(d: UsbDevice): UsbConnection {
        val intf = botInterface(d) ?: throw IOException(
            if (isUasOnly(d)) "This enclosure only speaks UAS, which Android apps can't use" else "That device isn't a USB drive",
        )
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        if (inEp == null || outEp == null) throw IOException("The drive has no bulk endpoints")
        val conn = manager?.openDevice(d) ?: throw IOException("Android wouldn't open the drive. Unplug it and try again.")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("The drive is busy. Eject it in Android's storage settings, then try again.")
        }
        // Always select the Bulk-Only setting: many USB 3 sticks are left in their UAS setting
        // by Android's driver, and then every bulk transfer fails. Then clear any stalled pipes.
        conn.setInterface(intf)
        conn.controlTransfer(0x02, 0x01, 0, inEp.address, null, 0, 2000)
        conn.controlTransfer(0x02, 0x01, 0, outEp.address, null, 0, 2000)
        val t = AndroidUsbTransport(conn, intf, inEp, outEp)
        try {
            val max = try {
                t.maxLun().coerceIn(0, 7)
            } catch (e: Exception) {
                0
            }
            val luns = (0..max).map { lun -> openLun(d, t, lun) }
            return UsbConnection(d, t, luns, linkSpeed(outEp.maxPacketSize))
        } catch (e: Exception) {
            t.close()
            throw e
        }
    }

    /** Tries a LUN a few ways before giving up: as is, after a mass-storage reset, after a pause. */
    private fun openLun(d: UsbDevice, t: AndroidUsbTransport, lun: Int): Lun {
        var last: IOException? = null
        for (attempt in 0 until 3) {
            try {
                if (attempt > 0) {
                    t.resetRecovery()
                    Thread.sleep(400L * attempt)
                }
                return Lun.Ready(lun, ScsiDisk(BulkOnly(t, lun)).init())
            } catch (e: NoMediumException) {
                return Lun.Empty(lun)
            } catch (e: IOException) {
                last = e
            }
        }
        return Lun.Failed(lun, (last?.message ?: "Couldn't read the drive") + " (USB %04x:%04x, ".format(d.vendorId, d.productId) + t.describe() + ")")
    }

    companion object {
        const val ACTION_PERMISSION = "io.github.ravenor907.ravendisk.USB_PERMISSION"

        fun storageInterfaces(d: UsbDevice): List<UsbInterface> =
            (0 until d.interfaceCount).map { d.getInterface(it) }.filter { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }

        /** Bulk-Only Transport, SCSI command set: the interface the app can drive. */
        fun botInterface(d: UsbDevice): UsbInterface? =
            storageInterfaces(d).firstOrNull { it.interfaceSubclass == 6 && it.interfaceProtocol == 0x50 }

        fun isUasOnly(d: UsbDevice) = botInterface(d) == null && storageInterfaces(d).any { it.interfaceProtocol == 0x62 }

        fun label(d: UsbDevice): String {
            val parts = listOfNotNull(d.manufacturerName?.trim(), d.productName?.trim()).filter { it.isNotEmpty() }
            return if (parts.isEmpty()) "USB drive" else parts.distinct().joinToString(" ")
        }

        /** Bulk packets are 1024 bytes at USB 3 speeds, 512 at USB 2, 64 at USB 1. */
        fun linkSpeed(maxPacket: Int): String = when {
            maxPacket >= 1024 -> "USB 3"
            maxPacket >= 512 -> "USB 2 (480 Mbit/s)"
            else -> "USB 1 (very slow)"
        }

        /** USB-NVMe bridge chips, by vendor:product. Health over these needs vendor commands. */
        private val NVME_BRIDGES = setOf(0x152d to 0x0583, 0x152d to 0x0562, 0x0bda to 0x9210, 0x0bda to 0x9220, 0x174c to 0x2362, 0x174c to 0x2364)

        fun isNvmeBridge(d: UsbDevice) = (d.vendorId to d.productId) in NVME_BRIDGES
    }
}
