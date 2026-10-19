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
    /** Send transfers through UsbRequest instead of bulkTransfer (some phones only like one). */
    private val useRequests: Boolean = false,
) : UsbTransport, Closeable {
    /** What the last bulk transfer returned, for error reports. */
    @Volatile
    var lastResult: String = "none"
        private set

    private val started = android.os.SystemClock.elapsedRealtime()
    private val log = ArrayDeque<String>()

    /** Every USB call this connection made, oldest first (last 40), with timings. */
    val trace: List<String> get() = synchronized(log) { log.toList() }

    fun note(s: String) = synchronized(log) {
        log.addLast("+${android.os.SystemClock.elapsedRealtime() - started}ms $s")
        while (log.size > 40) log.removeFirst()
    }

    private fun viaRequest(ep: UsbEndpoint, data: ByteArray, offset: Int, length: Int, timeoutMs: Int, input: Boolean): Int {
        val r = android.hardware.usb.UsbRequest()
        if (!r.initialize(conn, ep)) return -1
        try {
            val buf = java.nio.ByteBuffer.allocate(length)
            if (!input) buf.put(data, offset, length).flip()
            if (!r.queue(buf)) return -1
            val done = try {
                conn.requestWait(timeoutMs.toLong())
            } catch (e: java.util.concurrent.TimeoutException) {
                null
            } ?: return -1
            if (done !== r) return -1
            val n = if (input) buf.position() else length
            if (input) {
                buf.flip()
                buf.get(data, offset, n)
            }
            return n
        } finally {
            r.close()
        }
    }

    // Before Android 9 a single bulk transfer was capped at 16 KiB.
    override val maxTransfer: Int = if (Build.VERSION.SDK_INT >= 28) 64 * 1024 else 16 * 1024

    override fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val r = if (useRequests) viaRequest(outEp, data, offset, length, timeoutMs, false) else conn.bulkTransfer(outEp, data, offset, length, timeoutMs)
        lastResult = "out $length -> $r"
        note("bulk out ${length}B -> $r" + if (length == 31) " (command 0x%02x)".format(data[offset + 15]) else "")
        return r
    }

    override fun bulkIn(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val r = if (useRequests) viaRequest(inEp, data, offset, length, timeoutMs, true) else conn.bulkTransfer(inEp, data, offset, length, timeoutMs)
        lastResult = "in $length -> $r"
        note("bulk in ${length}B -> $r")
        return r
    }

    override fun resetRecovery() {
        // Bulk-Only Mass Storage Reset, then CLEAR_FEATURE(ENDPOINT_HALT) on both pipes.
        val a = conn.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, 5000)
        val b = conn.controlTransfer(0x02, 0x01, 0, inEp.address, null, 0, 5000)
        val c = conn.controlTransfer(0x02, 0x01, 0, outEp.address, null, 0, 5000)
        note("mass storage reset -> $a, clear halt in -> $b, clear halt out -> $c")
    }

    override fun maxLun(): Int {
        val b = ByteArray(1)
        val r = conn.controlTransfer(0xA1, 0xFE, 0, intf.id, b, 1, 2000)
        note("GET MAX LUN -> $r" + if (r == 1) " (max LUN ${b[0]})" else "")
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

/** Opening failed; [details] is a full report for troubleshooting. */
class UsbOpenException(msg: String, val details: String) : IOException(msg)

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
        // Different phones and drives want different handling. Try the gentlest way first (what
        // Handheld Forge does), then progressively more forceful ones, each on a fresh connection.
        val tried = ArrayList<String>()
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        for (mode in OpenMode.entries) {
            // A failed attempt can make the drive reconnect; give Android a moment and look it up again.
            var dev = d
            var conn: UsbDeviceConnection? = null
            for (wait in 0 until 6) {
                dev = manager?.deviceList?.values?.firstOrNull { it.vendorId == d.vendorId && it.productId == d.productId } ?: dev
                conn = manager?.openDevice(dev)
                if (conn != null) break
                Thread.sleep(500)
            }
            if (conn == null) {
                tried.add("${mode.label}: Android wouldn't open the device (openDevice returned nothing; permission ${manager?.hasPermission(dev)})")
                continue
            }
            val claimed = conn.claimInterface(intf, true)
            if (!claimed) {
                tried.add("${mode.label}: Android refused to hand the drive's interface to the app (claimInterface failed; another app or the system holds it)")
                conn.close()
                continue
            }
            val t = AndroidUsbTransport(conn, intf, inEp, outEp, useRequests = mode == OpenMode.ASYNC)
            t.note("opened ${dev.deviceName}, claimed interface ${intf.id}")
            if (mode != OpenMode.PLAIN) {
                // Select the Bulk-Only setting (USB 3 sticks may be left in UAS mode) and clear stalls.
                val si = conn.setInterface(intf)
                val h1 = conn.controlTransfer(0x02, 0x01, 0, inEp.address, null, 0, 2000)
                val h2 = conn.controlTransfer(0x02, 0x01, 0, outEp.address, null, 0, 2000)
                t.note("set interface -> $si, clear halt in -> $h1, clear halt out -> $h2")
            }
            if (mode == OpenMode.RESET || mode == OpenMode.ASYNC) {
                t.resetRecovery()
                Thread.sleep(500)
            }
            try {
                val first = try {
                    Lun.Ready(0, ScsiDisk(BulkOnly(t, 0)).init())
                } catch (e: NoMediumException) {
                    Lun.Empty(0)
                }
                // Only now ask for more LUNs: some drives misbehave if asked before their first command.
                val max = try {
                    t.maxLun().coerceIn(0, 7)
                } catch (e: Exception) {
                    0
                }
                val luns = listOf(first) + (1..max).map { lun -> openLun(t, lun) }
                return UsbConnection(d, t, luns, linkSpeed(outEp.maxPacketSize))
            } catch (e: IOException) {
                tried.add("${mode.label}: ${e.message}\n" + t.trace.joinToString("\n") { "       $it" })
                t.close()
                Thread.sleep(300)
            }
        }
        val first = tried.firstOrNull() ?: ""
        val why = when {
            tried.all { "openDevice" in it || "claimInterface" in it } -> "Android didn't let the app connect to the drive at all."
            "-> -1" in first && first.contains("out 31") -> "The drive refused the very first command. Android's USB link to it isn't working for apps on this phone/drive combination."
            "-> -1" in first -> "The drive stopped responding partway through a command."
            else -> "The drive answered, but not the way a USB drive should."
        }
        throw UsbOpenException(
            why,
            buildString {
                appendLine("Raven Disk Utility ${appVersion} report, ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}")
                appendLine()
                appendLine("What was tried, in order (each on a fresh connection):")
                tried.forEachIndexed { i, x -> appendLine("  ${i + 1}. $x") }
                appendLine()
                append(describe(d, context))
            },
        )
    }

    private enum class OpenMode(val label: String) {
        PLAIN("plain"),
        SELECT("select setting"),
        RESET("reset"),
        ASYNC("async"),
    }

    private fun openLun(t: AndroidUsbTransport, lun: Int): Lun = try {
        Lun.Ready(lun, ScsiDisk(BulkOnly(t, lun)).init())
    } catch (e: NoMediumException) {
        Lun.Empty(lun)
    } catch (e: IOException) {
        Lun.Failed(lun, e.message ?: "Couldn't read this slot")
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

        /** Everything Android reports about a USB device, for copy-and-paste troubleshooting. */
        fun describe(d: UsbDevice, context: Context? = null): String = buildString {
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}")
            appendLine("Build: ${Build.DISPLAY}, kernel ${System.getProperty("os.version")}, ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
            if (context != null) {
                val m = context.getSystemService(Context.USB_SERVICE) as UsbManager?
                appendLine("USB host feature: ${context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)}, permission for this drive: ${m?.hasPermission(d)}")
                appendLine("All USB devices: " + (m?.deviceList?.values?.joinToString { "%04x:%04x %s".format(it.vendorId, it.productId, it.productName ?: "") } ?: "none"))
                val vols = try {
                    AndroidVolumes.list(context).filter { !it.primary }
                } catch (e: Exception) {
                    emptyList()
                }
                appendLine("Android's removable volumes: " + (vols.joinToString { "${it.description} [${it.state}${it.fsType?.let { f -> ", $f" } ?: ""}]" }.ifEmpty { "none" }))
            }
            appendLine("Device: %04x:%04x  %s / %s".format(d.vendorId, d.productId, d.manufacturerName ?: "?", d.productName ?: "?"))
            appendLine("Class ${d.deviceClass}/${d.deviceSubclass}/${d.deviceProtocol}, ${d.configurationCount} configuration(s), path ${d.deviceName}")
                appendLine("USB version ${d.version}, speed hint: ${(0 until d.interfaceCount).flatMap { i -> (0 until d.getInterface(i).endpointCount).map { d.getInterface(i).getEndpoint(it).maxPacketSize } }.maxOrNull()?.let { linkSpeed(it) } ?: "?"}")
            for (i in 0 until d.interfaceCount) {
                val f = d.getInterface(i)
                val kind = when {
                    f.interfaceClass != 8 -> "other"
                    f.interfaceProtocol == 0x50 -> "Bulk-Only"
                    f.interfaceProtocol == 0x62 -> "UAS"
                    else -> "mass storage, protocol 0x%02x".format(f.interfaceProtocol)
                }
                appendLine("Interface ${f.id} alt ${f.alternateSetting}: class ${f.interfaceClass}/${f.interfaceSubclass}/0x%02x ($kind)".format(f.interfaceProtocol))
                for (e in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(e)
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        else -> "type ${ep.type}"
                    }
                    appendLine("  endpoint 0x%02x %s %s, %d-byte packets".format(ep.address, if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out", type, ep.maxPacketSize))
                }
            }
        }

        fun isNvmeBridge(d: UsbDevice) = (d.vendorId to d.productId) in NVME_BRIDGES
    }
}
