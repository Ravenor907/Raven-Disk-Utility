package io.github.ravenor907.ravendisk.backend

import android.content.Context
import android.hardware.usb.UsbDevice
import io.github.ravenor907.ravendisk.core.demo.Demo
import io.github.ravenor907.ravendisk.core.demo.DemoDrive
import io.github.ravenor907.ravendisk.core.health.HealthReport
import io.github.ravenor907.ravendisk.core.health.SatChannel
import io.github.ravenor907.ravendisk.core.health.Smart
import io.github.ravenor907.ravendisk.core.inspect.Inspection
import io.github.ravenor907.ravendisk.core.inspect.Inspector
import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.io.ScsiDisk
import io.github.ravenor907.ravendisk.core.model.Connection
import io.github.ravenor907.ravendisk.core.model.DiskInfo
import io.github.ravenor907.ravendisk.core.model.Fact
import io.github.ravenor907.ravendisk.core.model.MediaKind
import io.github.ravenor907.ravendisk.core.model.VolumeInfo
import io.github.ravenor907.ravendisk.core.model.detected
import io.github.ravenor907.ravendisk.core.model.inferred
import io.github.ravenor907.ravendisk.core.model.unavailable
import io.github.ravenor907.ravendisk.core.plan.DiskIdentity
import io.github.ravenor907.ravendisk.core.plan.DiskSession
import io.github.ravenor907.ravendisk.core.plan.DiskSnapshot
import io.github.ravenor907.ravendisk.core.plan.Planner
import io.github.ravenor907.ravendisk.core.table.TableRead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

enum class DriveState {
    /** Listed by Android, the app hasn't been allowed to talk to it yet. */
    NEEDS_PERMISSION,

    /** Android seems to have it mounted; opening it would yank it from Android. */
    ANDROID_MOUNTED,
    READING,
    READY,
    NO_MEDIUM,
    UNSUPPORTED,
    ERROR,
}

/** Something on the dashboard: a USB drive (or card reader slot), or a simulated drive. */
data class Drive(
    val key: String,
    val title: String,
    val state: DriveState,
    val message: String? = null,
    val info: DiskInfo? = null,
    val inspection: Inspection? = null,
    val snapshot: DiskSnapshot? = null,
    val usbName: String? = null,
    val simulated: Boolean = false,
    /** Full troubleshooting report when something failed. */
    val details: String? = null,
) {
    val writable get() = state == DriveState.READY && snapshot != null && !snapshot.readOnly
}

/** A session on a real USB LUN. */
class UsbSession(private val disk: ScsiDisk, private val key: String) : DiskSession {
    override val dev: BlockDevice get() = disk

    override fun identity(): DiskIdentity {
        disk.init() // re-reads INQUIRY, size and write protection from the drive itself
        return DiskIdentity.of(disk, key, "${disk.identity.vendor} ${disk.identity.product}".trim(), disk.identity.serial)
    }

    override fun discard(lba: Long, blocks: Long) = disk.unmap(lba, blocks)
    override fun close() {}
}

class DemoSession(private val d: DemoDrive) : DiskSession {
    override val dev: BlockDevice get() = d.disk
    override fun identity() = DiskIdentity.of(d.disk, d.id, "${d.vendor} ${d.model}", d.serial)
    override fun discard(lba: Long, blocks: Long) {
        // Simulated discard: the blocks read back as zeros afterwards.
        val chunk = 2048L
        var at = lba
        val zeros = ByteArray((chunk * d.disk.blockSize).toInt())
        while (at < lba + blocks) {
            val n = minOf(chunk, lba + blocks - at).toInt()
            d.disk.write(at, n, zeros)
            at += n
        }
    }
    override fun close() {}
}

/**
 * Everything the dashboard shows, kept up to date. Discovery never writes. Opening a USB drive
 * only happens with the person's permission, and not while Android appears to have it mounted
 * unless they say so.
 */
class Drives(private val context: Context, @Volatile var demo: Boolean) {
    companion object {
        const val TAG = "RavenDisk"
    }

    private val usb = UsbBackend(context)
    private val _drives = MutableStateFlow<List<Drive>>(emptyList())
    val drives: StateFlow<List<Drive>> = _drives
    private val _volumes = MutableStateFlow<List<AndroidVolume>>(emptyList())
    val volumes: StateFlow<List<AndroidVolume>> = _volumes

    private val connections = HashMap<String, UsbConnection>()
    private val sessions = HashMap<String, DiskSession>()
    private val planners = HashMap<String, Planner>()
    private val forced = HashSet<String>()
    private val demoDrives: List<DemoDrive> by lazy { Demo.drives().onEach { it.disk.writeDelayMs = 2 } }

    val usbAvailable get() = usb.available

    fun registerReceiver(onChange: () -> Unit) = usb.registerReceiver(onChange)

    fun unregisterReceiver(r: android.content.BroadcastReceiver) {
        try {
            context.unregisterReceiver(r)
        } catch (e: IllegalArgumentException) {
        }
    }

    fun session(key: String): DiskSession? = synchronized(this) { sessions[key] }

    fun drive(key: String): Drive? = _drives.value.firstOrNull { it.key == key }

    /** The queued plan for a drive, created on first use from its latest snapshot. */
    fun planner(key: String): Planner? = synchronized(this) {
        planners[key] ?: drive(key)?.snapshot?.let { Planner(it).also { p -> planners[key] = p } }
    }

    fun discardPlan(key: String) = synchronized(this) { planners.remove(key) }

    fun requestPermission(key: String) {
        drive(key)?.usbName?.let { usb.device(it) }?.let { usb.requestPermission(it) }
    }

    /** Drives the person unmounted from Android while Android's list still showed them as mounted. */
    private val released = HashSet<String>()

    enum class Unmount { DONE, STILL_LISTED, NOT_OPEN }

    /**
     * Takes the drive away from Android. Raven already holds the drive's USB interface (claimed
     * with force, which detaches Android's own storage driver), so Android can no longer read or
     * write it; this waits for Android to notice and drop its mount, then reads the drive again.
     * Blocking: call from a background thread.
     */
    fun unmount(key: String, timeoutMs: Long = 8000): Unmount {
        val d = drive(key) ?: return Unmount.NOT_OPEN
        val name = d.usbName ?: return Unmount.NOT_OPEN
        if (synchronized(this) { connections[name] } == null) return Unmount.NOT_OPEN
        val uuids = mountedUuidsOf(d)
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var clear = false
        while (true) {
            val vols = try { AndroidVolumes.list(context) } catch (e: Exception) { emptyList() }
            clear = vols.none { it.mounted && it.uuid?.uppercase() in uuids }
            if (clear || android.os.SystemClock.elapsedRealtime() > deadline) break
            Thread.sleep(400)
        }
        refresh(setOf(key))
        return if (clear) Unmount.DONE else Unmount.STILL_LISTED
    }

    /** Android still lists the drive, but Raven holds it; the person chose to treat that entry as stale. */
    fun unmountAnyway(key: String) {
        synchronized(this) { released.add(key) }
        refresh(setOf(key))
    }

    private val justTaken = HashSet<String>()

    private fun wasMountedJustNow(name: String) = justTaken.remove(name)

    /** Opening detached Android's storage driver; give Android a moment to drop its mount. */
    private fun waitForAndroidToLetGo(before: List<AndroidVolume>): List<AndroidVolume> {
        var vols = before
        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
        while (vols.any { it.mounted && it.looksLikeUsb } && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(400)
            vols = try { AndroidVolumes.list(context) } catch (e: Exception) { vols }
        }
        _volumes.value = vols
        return vols
    }

    private fun mountedUuidsOf(d: Drive): Set<String> {
        val ins = d.inspection ?: return emptySet()
        val snap = d.snapshot ?: return emptySet()
        val out = HashSet<String>()
        snap.mounted.forEach { n -> ins.fs[n]?.uuid?.uppercase()?.let(out::add) }
        if (snap.deviceMounted) ins.wholeDeviceFs?.uuid?.uppercase()?.let(out::add)
        return out
    }

    /** The person chose to open a drive Android may have mounted. */
    fun force(key: String) = synchronized(this) { drive(key)?.usbName?.let { forced.add(it) } }

    /** Re-reads everything. Blocking: call from a background thread. */
    fun refresh(reinspect: Set<String> = emptySet()) {
        val started = android.os.SystemClock.elapsedRealtime()
        val vols = try {
            AndroidVolumes.list(context)
        } catch (e: Exception) {
            emptyList()
        }
        _volumes.value = vols
        val out = ArrayList<Drive>()
        synchronized(this) {
            val present = usb.devices()
            // Forget devices that went away.
            val names = present.map { it.deviceName }.toSet()
            for (k in connections.keys.toList()) if (k !in names) {
                try {
                    connections.remove(k)?.close()
                } catch (e: Exception) {
                }
                sessions.keys.filter { it.startsWith("usb:$k#") }.forEach { sessions.remove(it); planners.remove(it) }
                released.removeAll { it.startsWith("usb:$k#") }
            }
            forced.retainAll(names)
            for (d in present) out.addAll(usbDrives(d, vols, reinspect))
            if (demo) {
                val t = android.os.SystemClock.elapsedRealtime()
                val built = demoDrives
                val t2 = android.os.SystemClock.elapsedRealtime()
                out.addAll(built.map { demoDrive(it, reinspect) })
                android.util.Log.i(TAG, "simulated drives: built in ${t2 - t} ms, inspected in ${android.os.SystemClock.elapsedRealtime() - t2} ms")
            }
        }
        _drives.value = out
        android.util.Log.i(TAG, "refresh: ${out.size} drive(s), ${vols.size} volume(s) in ${android.os.SystemClock.elapsedRealtime() - started} ms")
    }

    private fun usbDrives(d: UsbDevice, volsIn: List<AndroidVolume>, reinspect: Set<String>): List<Drive> {
        var vols = volsIn
        val name = d.deviceName
        val key = "usb:$name"
        val title = UsbBackend.label(d)
        if (UsbBackend.botInterface(d) == null) {
            return listOf(Drive("$key#0", title, DriveState.UNSUPPORTED, "This enclosure only speaks UAS (USB Attached SCSI), which Android apps can't use. With root it may be reachable in a later version.", usbName = name))
        }
        if (!usb.hasPermission(d)) return listOf(Drive("$key#0", title, DriveState.NEEDS_PERMISSION, "Tap to allow access", usbName = name))
        var conn = connections[name]
        if (conn == null) {
            val androidUsb = vols.filter { it.mounted && it.looksLikeUsb }
            if (androidUsb.isNotEmpty() && name !in forced) {
                return listOf(Drive("$key#0", title, DriveState.ANDROID_MOUNTED, "Android has \"${androidUsb.first().description}\" mounted. Unmount and open takes it from Android so Raven can work on it. If you were copying files to it, let that finish first.", usbName = name))
            }
            if (androidUsb.isNotEmpty()) justTaken.add(name)
            conn = try {
                usb.open(d).also { connections[name] = it }
            } catch (e: UsbOpenException) {
                return listOf(Drive("$key#0", title, DriveState.ERROR, e.message, usbName = name, details = e.details))
            } catch (e: IOException) {
                return listOf(Drive("$key#0", title, DriveState.ERROR, e.message, usbName = name, details = UsbBackend.describe(d, context)))
            }
        }
        if (wasMountedJustNow(name)) vols = waitForAndroidToLetGo(vols)
        val multi = conn.luns.size > 1
        return conn.luns.map { lun ->
            val k = "$key#${lun.index}"
            val t = if (multi) "$title, slot ${lun.index + 1}" else title
            when (lun) {
                is Lun.Empty -> Drive(k, t, DriveState.NO_MEDIUM, "No card in this slot", usbName = name)
                is Lun.Failed -> Drive(k, t, DriveState.ERROR, lun.message, usbName = name)
                is Lun.Ready -> {
                    val prev = _drives.value.firstOrNull { it.key == k }
                    if (prev?.info != null && prev.state == DriveState.READY && k !in reinspect) prev
                    else inspectUsb(k, t, d, conn, lun.disk, vols, multi)
                }
            }
        }
    }

    private fun inspectUsb(key: String, title: String, d: UsbDevice, conn: UsbConnection, disk: ScsiDisk, vols: List<AndroidVolume>, cardReader: Boolean): Drive {
        return try {
            val ins = Inspector.inspect(disk)
            val id = disk.identity
            val nvme = UsbBackend.isNvmeBridge(d)
            // Health: only ask bridges that look like SATA drives (fixed media). Flash drives and
            // card readers don't do SMART, and some misbehave when sent ATA commands.
            val health = when {
                nvme -> HealthReport.unavailable("NVMe health over USB needs bridge-specific commands that aren't supported yet")
                id.removableMedium -> HealthReport.unavailable(if (cardReader) "Card readers don't report health data" else "USB flash drives don't report health data")
                else -> Smart.report(SatChannel(disk), "ATA SMART through the USB-SATA bridge")
            }
            val media: Fact<MediaKind> = when {
                nvme -> inferred(MediaKind.NVME, "USB-NVMe bridge chip")
                id.rotationRate == 1 -> detected(MediaKind.SSD, "Block device characteristics")
                id.rotationRate != null -> detected(MediaKind.HDD, "Block device characteristics (${id.rotationRate} rpm)")
                cardReader || title.contains("reader", true) || title.contains("card", true) -> inferred(MediaKind.SD_CARD, "Card reader")
                id.removableMedium -> inferred(MediaKind.FLASH_DRIVE, "Removable medium bit")
                else -> unavailable("The drive doesn't say")
            }
            val ident = DiskIdentity.of(disk, key, "${id.vendor} ${id.product}".trim(), id.serial)
            val mountedUuids = if (key in released) emptySet() else vols.filter { it.mounted }.mapNotNull { it.uuid?.uppercase() }.toSet()
            val mountedParts = ins.fs.filter { (_, f) -> f?.uuid?.uppercase() in mountedUuids }.keys
            val wholeMounted = ins.wholeDeviceFs?.uuid?.uppercase()?.let { it in mountedUuids } == true
            val info = DiskInfo(
                id = key,
                backend = "USB direct",
                model = title,
                vendor = if (id.vendor.isNotEmpty()) detected(id.vendor, "SCSI INQUIRY") else unavailable("Not reported"),
                serial = id.serial?.let { detected(it, "SCSI serial number page") } ?: d.serialNumberOrNull()?.let { detected(it, "USB descriptor") } ?: unavailable("Not reported"),
                connection = if (cardReader) Connection.USB_CARD_READER else Connection.USB,
                media = media,
                capacityBytes = detected(disk.sizeBytes, "READ CAPACITY"),
                logicalSector = detected(disk.blockSize, "READ CAPACITY"),
                physicalSector = disk.physicalBlockSize?.let { detected(it, "READ CAPACITY(16)") } ?: unavailable("The drive only answers READ CAPACITY(10)"),
                readOnly = detected(disk.readOnly, "MODE SENSE"),
                removable = detected(id.removableMedium, "SCSI INQUIRY"),
                transport = detected("Bulk-Only Transport, ${conn.link}" + (if (cardReader) ", LUN ${disk.lun}" else ""), "USB descriptors"),
                discard = detected(disk.discard.supported, "READ CAPACITY(16) and Block Limits page"),
                table = ins.table,
                volumes = volumesOf(ins),
                health = health,
                mounted = if (mountedParts.isEmpty() && !wholeMounted) detected(emptyList(), "Android's storage volumes") else detected(mountedParts.map { "Partition $it" }, "Android's storage volumes"),
            )
            disk.onNote = { }
            synchronized(this) {
                sessions[key] = UsbSession(disk, key)
                planners.remove(key)
            }
            val snap = DiskSnapshot(ident, disk.physicalBlockSize, ins.table, ins.fs, mountedParts, wholeMounted, disk.readOnly, disk.discard.supported)
            Drive(key, title, DriveState.READY, info = info, inspection = ins, snapshot = snap, usbName = d.deviceName)
        } catch (e: IOException) {
            Drive(key, title, DriveState.ERROR, e.message ?: "Couldn't read the drive", usbName = d.deviceName)
        }
    }

    private fun UsbDevice.serialNumberOrNull(): String? = try {
        serialNumber?.takeIf { it.isNotBlank() }
    } catch (e: SecurityException) {
        null
    }

    private fun demoDrive(dd: DemoDrive, reinspect: Set<String>): Drive {
        val prev = _drives.value.firstOrNull { it.key == dd.id }
        if (prev?.info != null && dd.id !in reinspect) return prev
        val ins = Inspector.inspect(dd.disk)
        val ident = DiskIdentity.of(dd.disk, dd.id, "${dd.vendor} ${dd.model}", dd.serial)
        val sim = "Simulated drive"
        val info = DiskInfo(
            id = dd.id, backend = "Simulated", model = "${dd.vendor} ${dd.model}",
            vendor = detected(dd.vendor, sim), serial = dd.serial?.let { detected(it, sim) } ?: unavailable("Not reported"),
            connection = dd.connection, media = detected(dd.media, sim),
            capacityBytes = detected(dd.disk.sizeBytes, sim), logicalSector = detected(dd.disk.blockSize, sim),
            physicalSector = detected(dd.physicalBlockSize, sim), readOnly = detected(false, sim), removable = detected(dd.media == MediaKind.FLASH_DRIVE, sim),
            transport = detected("Simulated, ${io.github.ravenor907.ravendisk.core.util.Units.size(dd.disk.sizeBytes)} in memory", sim),
            discard = detected(dd.discard, sim), table = ins.table, volumes = volumesOf(ins), health = dd.health,
            mounted = detected(emptyList(), sim),
        )
        synchronized(this) {
            sessions[dd.id] = DemoSession(dd)
            planners.remove(dd.id)
        }
        val snap = DiskSnapshot(ident, dd.physicalBlockSize, ins.table, ins.fs, emptySet(), false, false, dd.discard)
        return Drive(dd.id, "${dd.vendor} ${dd.model}", DriveState.READY, info = info, inspection = ins, snapshot = snap, simulated = true)
    }

    private fun volumesOf(ins: Inspection): List<VolumeInfo> =
        (ins.table as? TableRead.Found)?.table?.sorted?.map { VolumeInfo(it, ins.fs[it.number]) } ?: emptyList()

    fun close() = synchronized(this) {
        connections.values.forEach {
            try {
                it.close()
            } catch (e: Exception) {
            }
        }
        connections.clear()
        sessions.clear()
    }
}
